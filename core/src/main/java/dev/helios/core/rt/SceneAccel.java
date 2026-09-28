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

import static dev.helios.core.vk.HeliosStack.stackPush;
import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Acceleration structures for the loaded world: one BLAS per non-empty chunk section (built once
 * when the section's mesh changes), one BLAS of entity model quads rebuilt every frame (shadow
 * casters only), and a TLAS rebuilt every frame from all of them, translated relative to the camera anchor.
 *
 * <p>Lifetime: one frame may be in flight on the GPU while the game calls {@link #upload} and
 * {@link #remove}. Replaced and removed resources are therefore retired and destroyed in the next
 * {@link #record}, which the renderer only calls after the previous frame's fence has signalled.
 */
public final class SceneAccel implements AutoCloseable {
    /** Bytes per geometry table entry: uvec2 vertexAddress, uint opaqueQuads, uint pad. */
    public static final int GEOMETRY_ENTRY_SIZE = 16;
    public static final int MASK_WORLD = 0x01;
    public static final int MASK_SHADOW_CASTER = 0x02;
    public static final int MAX_SHADOW_QUADS = 32768;

    private static final int INSTANCE_SIZE = VkAccelerationStructureInstanceKHR.SIZEOF;

    private static final class Section {
        final int sx, sy, sz;
        final int opaqueQuads, cutoutQuads;
        GpuBuffer staging;
        final GpuBuffer vertices;
        GpuBuffer asBuffer;
        long blas = VK_NULL_HANDLE;
        long blasAddress;

        Section(int sx, int sy, int sz, int opaqueQuads, int cutoutQuads, GpuBuffer staging, GpuBuffer vertices) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.opaqueQuads = opaqueQuads;
            this.cutoutQuads = cutoutQuads;
            this.staging = staging;
            this.vertices = vertices;
        }
    }

    private final VulkanContext ctx;
    private final Map<Long, Section> sections = new HashMap<>();
    private final List<Section> pending = new ArrayList<>();
    private final List<Object> retired = new ArrayList<>();

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

    // Entity shadow casters: quads (4 xyz vertices each) relative to an origin, rebuilt every frame.
    private final float[] shadowVertices = new float[MAX_SHADOW_QUADS * 12];
    private int shadowQuads;
    private double shadowOriginX, shadowOriginY, shadowOriginZ;
    private final GpuBuffer shadowVertexBuffer;
    private GpuBuffer shadowBlasBuffer;
    private long shadowBlas = VK_NULL_HANDLE;
    private long shadowBlasAddress;

    public SceneAccel(VulkanContext ctx) {
        this.ctx = ctx;
        ensureInstanceCapacity(1024);
        ensureIndexCapacity(MAX_SHADOW_QUADS);
        shadowVertexBuffer = GpuBuffer.hostVisible(ctx, (long) MAX_SHADOW_QUADS * 4 * 12,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
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

        GpuBuffer staging = GpuBuffer.hostVisible(ctx, geometry.sizeBytes(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        geometry.writeTo(staging.mapped());
        staging.flush();
        // Device-local: hit shaders fetch vertex attributes from here for every ray hit.
        GpuBuffer vertices = GpuBuffer.deviceLocal(ctx, geometry.sizeBytes(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        Section s = new Section(sx, sy, sz, geometry.opaqueQuadCount(), geometry.cutoutQuadCount(), staging, vertices);
        sections.put(key(sx, sy, sz), s);
        pending.add(s);
    }

    public void remove(int sx, int sy, int sz) {
        Section old = sections.remove(key(sx, sy, sz));
        if (old != null) {
            pending.remove(old);
            retire(old);
        }
    }

    public void clear() {
        sections.values().forEach(this::retire);
        sections.clear();
        pending.clear();
    }

    /**
     * Sets this frame's shadow-only geometry (entity models): {@code quadCount} quads of 4 xyz
     * vertices each, relative to the world-space origin.
     */
    public void setShadowGeometry(float[] vertices, int quadCount, double originX, double originY, double originZ) {
        shadowQuads = Math.min(quadCount, MAX_SHADOW_QUADS);
        System.arraycopy(vertices, 0, shadowVertices, 0, shadowQuads * 12);
        shadowOriginX = originX;
        shadowOriginY = originY;
        shadowOriginZ = originZ;
    }

    public long tlas() {
        return tlas;
    }

    public GpuBuffer geometryTable() {
        return geometryTable;
    }

    /**
     * Records vertex uploads, BLAS builds for changed sections and shadow casters, and a full TLAS
     * build, ending with a barrier. May recreate the TLAS/geometry table: callers must re-bind both
     * descriptors every frame. The previous frame must have completed on the GPU.
     */
    public void record(VkCommandBuffer cmd, long anchorX, long anchorY, long anchorZ) {
        destroyRetired();

        int scratchAlign = ctx.rtProperties.minScratchOffsetAlignment();
        int maxQuads = pending.stream().mapToInt(s -> Math.max(s.opaqueQuads, s.cutoutQuads)).max().orElse(0);
        ensureIndexCapacity(maxQuads);
        ensureInstanceCapacity(sections.size() + 1);

        try (MemoryStack stack = stackPush()) {
            // Copy staged vertices to device memory.
            for (Section s : pending) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(s.vertices.size);
                vkCmdCopyBuffer(cmd, s.staging.handle, s.vertices.handle, region);
            }
            if (!pending.isEmpty()) Barriers.full(cmd);

            // Sizes and AS allocation.
            long[] scratchOffsets = new long[pending.size()];
            long scratchTotal = 0;
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
            long shadowScratchOffset = scratchTotal;
            if (shadowQuads > 0) scratchTotal += align(prepareShadowBlas(stack, sizes), scratchAlign);
            long tlasScratchOffset = scratchTotal;
            scratchTotal += align(tlasSizes(stack, sizes), scratchAlign);
            ensureScratch(scratchTotal);

            // BLAS builds use distinct scratch ranges, so no barriers between them.
            for (int i = 0; i < pending.size(); i++) {
                Section s = pending.get(i);
                try (MemoryStack inner = stack.push()) {
                    var info = blasBuildInfo(inner, s);
                    long scratchAddress = scratch.deviceAddress() + scratchOffsets[i];
                    info.get(0).dstAccelerationStructure(s.blas).scratchData(d -> d.deviceAddress(scratchAddress));
                    vkCmdBuildAccelerationStructuresKHR(cmd, info, inner.pointers(buildRanges(inner, s)));
                }
                retired.add(s.staging);
                s.staging = null;
            }
            boolean builtBlas = !pending.isEmpty();
            pending.clear();
            if (shadowQuads > 0) {
                recordShadowBlas(stack, cmd, scratch.deviceAddress() + shadowScratchOffset);
                builtBlas = true;
            }
            if (builtBlas) Barriers.full(cmd);

            writeInstances(stack, anchorX, anchorY, anchorZ);
            recordTlasBuild(stack, cmd, scratch.deviceAddress() + tlasScratchOffset);
        }
        Barriers.full(cmd);
    }

    // ------------------------------------------------------------ sections

    private VkAccelerationStructureBuildGeometryInfoKHR.Buffer blasBuildInfo(MemoryStack stack, Section s) {
        int count = (s.opaqueQuads > 0 ? 1 : 0) + (s.cutoutQuads > 0 ? 1 : 0);
        VkAccelerationStructureGeometryKHR.Buffer geoms = VkAccelerationStructureGeometryKHR.calloc(count, stack);
        int g = 0;
        long vbAddress = s.vertices.deviceAddress();
        long indexAddress = indexBuffer.deviceAddress();
        if (s.opaqueQuads > 0) {
            triangles(geoms.get(g++), vbAddress, SectionGeometry.VERTEX_STRIDE, s.opaqueQuads * 4, indexAddress,
                    VK_GEOMETRY_OPAQUE_BIT_KHR);
        }
        if (s.cutoutQuads > 0) {
            long cutoutAddress = vbAddress + (long) s.opaqueQuads * SectionGeometry.VERTICES_PER_QUAD * SectionGeometry.VERTEX_STRIDE;
            triangles(geoms.get(g), cutoutAddress, SectionGeometry.VERTEX_STRIDE, s.cutoutQuads * 4, indexAddress,
                    VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        }
        return buildInfo(stack, geoms, count, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(
            MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geoms, int count, int type, int flags) {
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                .type(type)
                .flags(flags)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(geoms)
                .geometryCount(count);
    }

    private static void triangles(VkAccelerationStructureGeometryKHR geom, long vertexAddress, int stride, int vertexCount,
                                  long indexAddress, int flags) {
        geom.sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(flags);
        geom.geometry().triangles()
                .sType$Default()
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(stride)
                .maxVertex(vertexCount - 1)
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
        s.blasAddress = asAddress(s.blas);
    }

    private long asAddress(long as) {
        try (MemoryStack stack = stackPush()) {
            return vkGetAccelerationStructureDeviceAddressKHR(ctx.device,
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(as));
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

    // ------------------------------------------------------ shadow casters

    private VkAccelerationStructureBuildGeometryInfoKHR.Buffer shadowBuildInfo(MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        triangles(geom.get(0), shadowVertexBuffer.deviceAddress(), 12, MAX_SHADOW_QUADS * 4,
                indexBuffer.deviceAddress(), VK_GEOMETRY_OPAQUE_BIT_KHR);
        return buildInfo(stack, geom, 1, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR);
    }

    /** Allocates the (max-size) shadow caster BLAS once; returns its scratch size. */
    private long prepareShadowBlas(MemoryStack stack, VkAccelerationStructureBuildSizesInfoKHR sizes) {
        vkGetAccelerationStructureBuildSizesKHR(ctx.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                shadowBuildInfo(stack).get(0), stack.ints(MAX_SHADOW_QUADS * 2), sizes);
        if (shadowBlas == VK_NULL_HANDLE) {
            shadowBlasBuffer = GpuBuffer.deviceLocal(ctx, sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, 256);
            shadowBlas = createAs(shadowBlasBuffer, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            shadowBlasAddress = asAddress(shadowBlas);
        }
        return sizes.buildScratchSize();
    }

    private void recordShadowBlas(MemoryStack stack, VkCommandBuffer cmd, long scratchAddress) {
        shadowVertexBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(shadowVertices, 0, shadowQuads * 12);
        shadowVertexBuffer.flush();
        try (MemoryStack inner = stack.push()) {
            var info = shadowBuildInfo(inner);
            info.get(0).dstAccelerationStructure(shadowBlas).scratchData(d -> d.deviceAddress(scratchAddress));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, inner)
                    .primitiveCount(shadowQuads * 2);
            vkCmdBuildAccelerationStructuresKHR(cmd, info, inner.pointers(range));
        }
    }

    // ----------------------------------------------------------------- TLAS

    private VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuildInfo(MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
        long address = instances.deviceAddress();
        geom.get(0).geometry().instances()
                .sType$Default()
                .arrayOfPointers(false)
                .data(d -> d.deviceAddress(address));
        return buildInfo(stack, geom, 1, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
    }

    /** Ensures the TLAS can hold every instance; returns its build scratch size. */
    private long tlasSizes(MemoryStack stack, VkAccelerationStructureBuildSizesInfoKHR sizes) {
        int capacity = instanceCapacity;
        vkGetAccelerationStructureBuildSizesKHR(ctx.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                tlasBuildInfo(stack).get(0), stack.ints(capacity), sizes);
        if (tlas == VK_NULL_HANDLE || tlasCapacity < capacity) {
            if (tlas != VK_NULL_HANDLE) {
                retired.add(new AsHandle(tlas, tlasBuffer));
            }
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
            translation(transform, s.sx * 16L - ax, s.sy * 16L - ay, s.sz * 16L - az);
            inst.get(i).transform().matrix(transform);
            inst.get(i).instanceCustomIndex(i)
                    .mask(MASK_WORLD)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(s.blasAddress);
            table.putLong(i * GEOMETRY_ENTRY_SIZE, s.vertices.deviceAddress());
            table.putInt(i * GEOMETRY_ENTRY_SIZE + 8, s.opaqueQuads);
            table.putInt(i * GEOMETRY_ENTRY_SIZE + 12, 0);
            i++;
        }
        if (shadowQuads > 0) {
            // Only shadow rays (mask 0xFF) see this instance, and they skip closest-hit shading, so it
            // needs no geometry table entry.
            transform.clear();
            transform.put(1).put(0).put(0).put((float) (shadowOriginX - ax))
                    .put(0).put(1).put(0).put((float) (shadowOriginY - ay))
                    .put(0).put(0).put(1).put((float) (shadowOriginZ - az)).flip();
            inst.get(i).transform().matrix(transform);
            inst.get(i).instanceCustomIndex(0)
                    .mask(MASK_SHADOW_CASTER)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR | VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR)
                    .accelerationStructureReference(shadowBlasAddress);
            i++;
        }
        instanceCount = i;
        instances.flush();
        geometryTable.flush();
    }

    private static void translation(FloatBuffer m, long x, long y, long z) {
        m.clear();
        m.put(1).put(0).put(0).put(x)
                .put(0).put(1).put(0).put(y)
                .put(0).put(0).put(1).put(z).flip();
    }

    private void recordTlasBuild(MemoryStack stack, VkCommandBuffer cmd, long scratchAddress) {
        var info = tlasBuildInfo(stack);
        info.get(0).dstAccelerationStructure(tlas).scratchData(d -> d.deviceAddress(scratchAddress));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                .primitiveCount(instanceCount);
        vkCmdBuildAccelerationStructuresKHR(cmd, info, stack.pointers(range));
    }

    // ------------------------------------------------------------- buffers

    private void ensureIndexCapacity(int quads) {
        if (indexBuffer != null && quads <= indexQuadCapacity) return;
        int capacity = Math.max(quads, indexQuadCapacity * 2);
        if (indexBuffer != null) retired.add(indexBuffer);
        indexBuffer = GpuBuffer.hostVisible(ctx, (long) capacity * SectionGeometry.INDICES_PER_QUAD * 4,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        IntBuffer idx = indexBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int q = 0; q < capacity; q++) {
            int b = q * 4;
            // Must match the vertex fetch in raytracing.glsl: tri 0 = (0,1,2), tri 1 = (0,2,3).
            idx.put(b).put(b + 1).put(b + 2).put(b).put(b + 2).put(b + 3);
        }
        indexBuffer.flush();
        indexQuadCapacity = capacity;
    }

    private void ensureInstanceCapacity(int count) {
        if (instances != null && count <= instanceCapacity) return;
        int capacity = Math.max(count, instanceCapacity * 2);
        if (instances != null) {
            retired.add(instances);
            retired.add(geometryTable);
        }
        instances = GpuBuffer.hostVisible(ctx, (long) capacity * INSTANCE_SIZE,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, 16);
        geometryTable = GpuBuffer.hostVisible(ctx, (long) capacity * GEOMETRY_ENTRY_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        instanceCapacity = capacity;
    }

    private void ensureScratch(long size) {
        if (scratch != null && scratch.size >= size) return;
        long newSize = Math.max(size, scratch == null ? 1 << 20 : scratch.size * 2);
        if (scratch != null) retired.add(scratch);
        scratch = GpuBuffer.deviceLocal(ctx, newSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                ctx.rtProperties.minScratchOffsetAlignment());
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    // ------------------------------------------------------------ lifetime

    private record AsHandle(long as, GpuBuffer buffer) {
    }

    private void retire(Section s) {
        if (s.blas != VK_NULL_HANDLE) retired.add(new AsHandle(s.blas, s.asBuffer));
        if (s.staging != null) retired.add(s.staging);
        retired.add(s.vertices);
    }

    private void destroyRetired() {
        for (Object r : retired) {
            if (r instanceof AsHandle(long as, GpuBuffer buffer)) {
                vkDestroyAccelerationStructureKHR(ctx.device, as, null);
                buffer.close();
            } else if (r instanceof GpuBuffer b) {
                b.close();
            }
        }
        retired.clear();
    }

    @Override
    public void close() {
        clear();
        destroyRetired();
        if (tlas != VK_NULL_HANDLE) {
            vkDestroyAccelerationStructureKHR(ctx.device, tlas, null);
            tlasBuffer.close();
        }
        if (shadowBlas != VK_NULL_HANDLE) {
            vkDestroyAccelerationStructureKHR(ctx.device, shadowBlas, null);
            shadowBlasBuffer.close();
        }
        if (scratch != null) scratch.close();
        indexBuffer.close();
        instances.close();
        geometryTable.close();
        shadowVertexBuffer.close();
    }
}
