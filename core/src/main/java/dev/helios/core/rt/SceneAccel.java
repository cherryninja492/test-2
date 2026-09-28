package dev.helios.core.rt;

import dev.helios.core.geometry.SectionGeometry;
import dev.helios.core.vk.Barriers;
import dev.helios.core.vk.GpuBuffer;
import dev.helios.core.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Acceleration structures for the loaded world: one BLAS per non-empty chunk section (built once
 * when the section's mesh changes) and a TLAS rebuilt every frame from all sections, translated
 * relative to the camera anchor.
 *
 * <p>Threading/lifetime: all methods run on the render thread, and the renderer waits for the GPU at
 * the end of every frame, so resources can be destroyed immediately when a section is replaced.
 */
public final class SceneAccel implements AutoCloseable {
    /** Bytes per geometry table entry: uvec2 vertexAddress, uint opaqueQuads, uint pad. */
    public static final int GEOMETRY_ENTRY_SIZE = 16;
    private static final int INSTANCE_SIZE = VkAccelerationStructureInstanceKHR.SIZEOF;

    private static final class Section {
        final int sx, sy, sz;
        final int opaqueQuads, cutoutQuads;
        final GpuBuffer vertices;
        GpuBuffer asBuffer;
        long blas = VK_NULL_HANDLE;
        long blasAddress;

        Section(int sx, int sy, int sz, int opaqueQuads, int cutoutQuads, GpuBuffer vertices) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.opaqueQuads = opaqueQuads;
            this.cutoutQuads = cutoutQuads;
            this.vertices = vertices;
        }
    }

    private final VulkanContext ctx;
    private final Map<Long, Section> sections = new HashMap<>();
    private final List<Section> pending = new ArrayList<>();

    private GpuBuffer indexBuffer;
    private int indexQuadCapacity;
    private GpuBuffer scratch;

    private GpuBuffer instances;
    private GpuBuffer geometryTable;
    private int instanceCapacity;
    private GpuBuffer tlasBuffer;
    private long tlas = VK_NULL_HANDLE;
    private int tlasCapacity;
    private int instanceCount;

    public SceneAccel(VulkanContext ctx) {
        this.ctx = ctx;
        ensureInstanceCapacity(1024);
        ensureIndexCapacity(4096);
    }

    public static long key(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Replaces the geometry of a section. An empty geometry removes it. */
    public void upload(int sx, int sy, int sz, SectionGeometry geometry) {
        remove(sx, sy, sz);
        if (geometry.isEmpty()) return;

        GpuBuffer vb = GpuBuffer.hostVisible(ctx, geometry.sizeBytes(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ByteBuffer mapped = vb.mapped();
        geometry.writeTo(mapped);
        vb.flush();

        Section s = new Section(sx, sy, sz, geometry.opaqueQuadCount(), geometry.cutoutQuadCount(), vb);
        sections.put(key(sx, sy, sz), s);
        pending.add(s);
    }

    public void remove(int sx, int sy, int sz) {
        Section old = sections.remove(key(sx, sy, sz));
        if (old != null) {
            pending.remove(old);
            destroy(old);
        }
    }

    public void clear() {
        sections.values().forEach(this::destroy);
        sections.clear();
        pending.clear();
    }

    public long tlas() {
        return tlas;
    }

    public GpuBuffer geometryTable() {
        return geometryTable;
    }

    /**
     * Records BLAS builds for changed sections and a full TLAS build. Ends with a barrier so the
     * results are visible to ray tracing. May recreate the TLAS/geometry table: callers must
     * re-bind both descriptors every frame.
     */
    public void record(VkCommandBuffer cmd, long anchorX, long anchorY, long anchorZ) {
        int scratchAlign = ctx.rtProperties.minScratchOffsetAlignment();
        int maxQuads = pending.stream().mapToInt(s -> Math.max(s.opaqueQuads, s.cutoutQuads)).max().orElse(0);
        ensureIndexCapacity(maxQuads);
        ensureInstanceCapacity(sections.size());

        // Pass 1: sizes and AS allocation.
        long[] scratchOffsets = new long[pending.size()];
        long scratchTotal = 0;
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            for (int i = 0; i < pending.size(); i++) {
                Section s = pending.get(i);
                try (MemoryStack inner = stack.push()) {
                    var info = blasBuildInfo(inner, s);
                    vkGetAccelerationStructureBuildSizesKHR(ctx.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                            info.get(0), primitiveCounts(inner, s), sizes);
                }
                createBlas(s, sizes.accelerationStructureSize());
                scratchOffsets[i] = scratchTotal;
                scratchTotal += align(sizes.buildScratchSize(), scratchAlign);
            }
            long tlasScratchOffset = scratchTotal;
            scratchTotal += align(tlasSizes(stack, sizes), scratchAlign);
            ensureScratch(scratchTotal);

            // Pass 2: record BLAS builds. Distinct scratch ranges, so no barriers between them.
            for (int i = 0; i < pending.size(); i++) {
                Section s = pending.get(i);
                try (MemoryStack inner = stack.push()) {
                    var info = blasBuildInfo(inner, s);
                    long scratchAddress = scratch.deviceAddress() + scratchOffsets[i];
                    info.get(0).dstAccelerationStructure(s.blas).scratchData(d -> d.deviceAddress(scratchAddress));
                    vkCmdBuildAccelerationStructuresKHR(cmd, info, inner.pointers(buildRanges(inner, s)));
                }
            }
            pending.clear();
            if (scratchOffsets.length > 0) Barriers.full(cmd);

            writeInstances(stack, anchorX, anchorY, anchorZ);
            recordTlasBuild(stack, cmd, scratch.deviceAddress() + tlasScratchOffset);
        }
        Barriers.full(cmd);
    }

    private VkAccelerationStructureBuildGeometryInfoKHR.Buffer blasBuildInfo(MemoryStack stack, Section s) {
        int count = (s.opaqueQuads > 0 ? 1 : 0) + (s.cutoutQuads > 0 ? 1 : 0);
        VkAccelerationStructureGeometryKHR.Buffer geoms = VkAccelerationStructureGeometryKHR.calloc(count, stack);
        int g = 0;
        long vbAddress = s.vertices.deviceAddress();
        if (s.opaqueQuads > 0) {
            triangles(geoms.get(g++), vbAddress, s.opaqueQuads, VK_GEOMETRY_OPAQUE_BIT_KHR);
        }
        if (s.cutoutQuads > 0) {
            long cutoutAddress = vbAddress + (long) s.opaqueQuads * SectionGeometry.VERTICES_PER_QUAD * SectionGeometry.VERTEX_STRIDE;
            triangles(geoms.get(g), cutoutAddress, s.cutoutQuads, VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        }
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(geoms)
                .geometryCount(count);
    }

    private void triangles(VkAccelerationStructureGeometryKHR geom, long vertexAddress, int quads, int flags) {
        long indexAddress = indexBuffer.deviceAddress();
        geom.sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(flags);
        geom.geometry().triangles()
                .sType$Default()
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(SectionGeometry.VERTEX_STRIDE)
                .maxVertex(quads * SectionGeometry.VERTICES_PER_QUAD - 1)
                .indexType(VK_INDEX_TYPE_UINT32)
                .vertexData(d -> d.deviceAddress(vertexAddress))
                .indexData(d -> d.deviceAddress(indexAddress));
    }

    private static IntBuffer primitiveCounts(MemoryStack stack, Section s) {
        IntBuffer counts = stack.mallocInt(2);
        if (s.opaqueQuads > 0) counts.put(s.opaqueQuads * 2);
        if (s.cutoutQuads > 0) counts.put(s.cutoutQuads * 2);
        return counts.flip();
    }

    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer buildRanges(MemoryStack stack, Section s) {
        int count = (s.opaqueQuads > 0 ? 1 : 0) + (s.cutoutQuads > 0 ? 1 : 0);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(count, stack);
        int g = 0;
        if (s.opaqueQuads > 0) ranges.get(g++).primitiveCount(s.opaqueQuads * 2);
        if (s.cutoutQuads > 0) ranges.get(g).primitiveCount(s.cutoutQuads * 2);
        return ranges;
    }

    private void createBlas(Section s, long size) {
        s.asBuffer = GpuBuffer.deviceLocal(ctx, size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, 256);
        s.blas = createAs(s.asBuffer, size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        try (MemoryStack stack = stackPush()) {
            s.blasAddress = vkGetAccelerationStructureDeviceAddressKHR(ctx.device,
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(s.blas));
        }
    }

    private long createAs(GpuBuffer buffer, long size, int type) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureCreateInfoKHR info = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(buffer.handle)
                    .size(size)
                    .type(type);
            var p = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(ctx.device, info, null, p), "vkCreateAccelerationStructureKHR");
            return p.get(0);
        }
    }

    private VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuildInfo(MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
        long address = instances.deviceAddress();
        geom.get(0).geometry().instances()
                .sType$Default()
                .arrayOfPointers(false)
                .data(d -> d.deviceAddress(address));
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(geom)
                .geometryCount(1);
    }

    /** Ensures the TLAS can hold every section; returns its build scratch size. */
    private long tlasSizes(MemoryStack stack, VkAccelerationStructureBuildSizesInfoKHR sizes) {
        int capacity = instanceCapacity;
        vkGetAccelerationStructureBuildSizesKHR(ctx.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                tlasBuildInfo(stack).get(0), stack.ints(capacity), sizes);
        if (tlas == VK_NULL_HANDLE || tlasCapacity < capacity) {
            destroyTlas();
            tlasBuffer = GpuBuffer.deviceLocal(ctx, sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, 256);
            tlas = createAs(tlasBuffer, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            tlasCapacity = capacity;
        }
        return sizes.buildScratchSize();
    }

    private void writeInstances(MemoryStack stack, long ax, long ay, long az) {
        VkAccelerationStructureInstanceKHR.Buffer inst =
                VkAccelerationStructureInstanceKHR.create(instances.mappedAddress(), instanceCapacity);
        ByteBuffer table = geometryTable.mapped().order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer transform = stack.mallocFloat(12);
        int i = 0;
        for (Section s : sections.values()) {
            if (s.blas == VK_NULL_HANDLE) continue;
            float tx = (float) (s.sx * 16L - ax);
            float ty = (float) (s.sy * 16L - ay);
            float tz = (float) (s.sz * 16L - az);
            transform.clear();
            transform.put(1).put(0).put(0).put(tx)
                    .put(0).put(1).put(0).put(ty)
                    .put(0).put(0).put(1).put(tz).flip();
            VkAccelerationStructureInstanceKHR instance = inst.get(i);
            instance.transform().matrix(transform);
            instance.instanceCustomIndex(i)
                    .mask(0xFF)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(s.blasAddress);
            table.putLong(i * GEOMETRY_ENTRY_SIZE, s.vertices.deviceAddress());
            table.putInt(i * GEOMETRY_ENTRY_SIZE + 8, s.opaqueQuads);
            table.putInt(i * GEOMETRY_ENTRY_SIZE + 12, 0);
            i++;
        }
        instanceCount = i;
        instances.flush();
        geometryTable.flush();
    }

    private void recordTlasBuild(MemoryStack stack, VkCommandBuffer cmd, long scratchAddress) {
        var info = tlasBuildInfo(stack);
        info.get(0).dstAccelerationStructure(tlas).scratchData(d -> d.deviceAddress(scratchAddress));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                .primitiveCount(instanceCount);
        vkCmdBuildAccelerationStructuresKHR(cmd, info, stack.pointers(range));
    }

    private void ensureIndexCapacity(int quads) {
        if (indexBuffer != null && quads <= indexQuadCapacity) return;
        int capacity = Math.max(quads, indexQuadCapacity * 2);
        if (indexBuffer != null) indexBuffer.close();
        indexBuffer = GpuBuffer.hostVisible(ctx, (long) capacity * SectionGeometry.INDICES_PER_QUAD * 4,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        IntBuffer idx = indexBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int q = 0; q < capacity; q++) {
            int b = q * 4;
            // Must match the vertex fetch in common.glsl: tri 0 = (0,1,2), tri 1 = (0,2,3).
            idx.put(b).put(b + 1).put(b + 2).put(b).put(b + 2).put(b + 3);
        }
        indexBuffer.flush();
        indexQuadCapacity = capacity;
    }

    private void ensureInstanceCapacity(int count) {
        if (instances != null && count <= instanceCapacity) return;
        int capacity = Math.max(count, instanceCapacity * 2);
        if (instances != null) {
            instances.close();
            geometryTable.close();
        }
        instances = GpuBuffer.hostVisible(ctx, (long) capacity * INSTANCE_SIZE,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, 16);
        geometryTable = GpuBuffer.hostVisible(ctx, (long) capacity * GEOMETRY_ENTRY_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        instanceCapacity = capacity;
    }

    private void ensureScratch(long size) {
        if (scratch != null && scratch.size >= size) return;
        if (scratch != null) scratch.close();
        scratch = GpuBuffer.deviceLocal(ctx, Math.max(size, scratch == null ? 1 << 20 : scratch.size * 2),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, ctx.rtProperties.minScratchOffsetAlignment());
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private void destroy(Section s) {
        if (s.blas != VK_NULL_HANDLE) vkDestroyAccelerationStructureKHR(ctx.device, s.blas, null);
        if (s.asBuffer != null) s.asBuffer.close();
        s.vertices.close();
    }

    private void destroyTlas() {
        if (tlas != VK_NULL_HANDLE) {
            vkDestroyAccelerationStructureKHR(ctx.device, tlas, null);
            tlasBuffer.close();
            tlas = VK_NULL_HANDLE;
        }
    }

    @Override
    public void close() {
        clear();
        destroyTlas();
        if (scratch != null) scratch.close();
        indexBuffer.close();
        instances.close();
        geometryTable.close();
    }
}
