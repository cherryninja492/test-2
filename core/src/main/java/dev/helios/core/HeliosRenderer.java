package dev.helios.core;

import dev.helios.core.dlss.DlssBridge;
import dev.helios.core.dlss.DlssUpscaler;
import dev.helios.core.geometry.SectionGeometry;
import dev.helios.core.lighting.LightGrid;
import dev.helios.core.math.FrameUniforms;
import dev.helios.core.math.Jitter;
import dev.helios.core.math.UpscaleQuality;
import dev.helios.core.math.UpscalerBackend;
import dev.helios.core.rt.RayTracingPipeline;
import dev.helios.core.rt.SceneAccel;
import dev.helios.core.vk.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static dev.helios.core.rt.RayTracingPipeline.*;
import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Hardware ray traced renderer for block worlds. Game-agnostic: the Fabric module feeds it chunk
 * section meshes, the block atlas and camera state, and composites its output into Minecraft's
 * OpenGL framebuffer through shared memory.
 *
 * <p>Per frame: build changed BLAS + TLAS, path trace at render resolution (direct light,
 * demodulated indirect light, albedo, specular, normal/depth, motion, depth), temporal accumulation
 * and à-trous denoising of the indirect light, modulation, upscaling (DLSS or built-in TAAU) to
 * display resolution and tonemapping into an image shared with OpenGL.
 *
 * <p>Frames are pipelined: {@link #renderFrame} returns right after submitting. When OpenGL supports
 * {@code GL_EXT_semaphore} ({@link #exportSemaphores}), GPU semaphores order Vulkan and GL work;
 * otherwise {@link #renderFrame} waits for the GPU before returning. All methods must be called from
 * one thread (Minecraft's render thread).
 */
public final class HeliosRenderer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("Helios");

    /** A GPU image OpenGL should import with {@code GL_EXT_memory_object}. */
    public record SharedImage(long handle, long allocationSize, int width, int height) {
    }

    /**
     * @param color        RGBA8, display resolution, tonemapped and gamma encoded, row 0 = top
     * @param depth        RG32F, render resolution, OpenGL window-space depth of the opaque surface
     *                     (1 = sky) and of the first water surface in front of it (1 = none); row 0 = top
     * @param waterVeil    RGBA16F, render resolution: HDR light a water surface adds over entities
     *                     beneath it (rgb) and the share of them that shows through (a)
     * @param win32Handles true if handles are Win32 NT handles, false for POSIX fds
     */
    public record SharedTargets(SharedImage color, SharedImage depth, SharedImage waterVeil, boolean win32Handles) {
    }

    /**
     * Semaphores for GL interop: GL waits on {@code vulkanDone} before reading the shared images and
     * signals {@code glDone} afterwards (then calls {@link #markGlSignaled}).
     */
    public record SharedSemaphores(long vulkanDone, long glDone, boolean win32Handles) {
    }

    /** GPU time of the last completed frame, in milliseconds. */
    public record GpuTimings(float accelerationAndTrace, float denoise, float upscaleAndTonemap, float total) {
    }

    private static final int USAGE_STORAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int MAX_HISTORY = 32;
    private static final int TIMESTAMPS = 4;

    // Modulate pass descriptor sets, by indirect-light source.
    private static final int MOD_FROM_A = 0, MOD_FROM_B = 1, MOD_FROM_HISTORY0 = 2, MOD_FROM_RAW = 4;

    private final RendererSettings settings;
    private final VulkanContext ctx;
    private final SceneAccel scene;
    private final RayTracingPipeline rtPipeline;
    private final long descriptorPool;
    private final long rtSet;
    private final GpuBuffer frameUbo;
    private final long linearSampler;
    private long atlasSampler = VK_NULL_HANDLE;
    private final ComputePass temporal, atrous, modulate, taau, tonemap;
    private final DlssUpscaler dlss;
    private final VkCommandBuffer cmd;
    private final long fence;
    private final long queryPool;
    private final FrameUniforms uniforms = new FrameUniforms();
    private final ByteBuffer push = MemoryUtil.memAlloc(32).order(ByteOrder.LITTLE_ENDIAN);

    // Point lights
    private final LightGrid lightGrid = new LightGrid();
    private GpuBuffer lightBuffer, lightCellBuffer, lightIndexBuffer;
    private boolean pointLightsEnabled;

    // Entity lighting probes
    public static final int MAX_PROBES = 512;
    private final float[] probePositions = new float[MAX_PROBES * 3];
    private int probeCount;
    private double probeOriginX, probeOriginY, probeOriginZ;
    private final GpuBuffer probeInput, probeOutput;
    private int probesInFlight;
    private final float[] probeResults = new float[MAX_PROBES * 2];
    private int probeResultCount;

    private SharedSemaphore vulkanDone, glDone;
    private boolean glSignaled;
    private boolean frameInFlight;
    private GpuTimings timings = new GpuTimings(0, 0, 0, 0);

    private GpuImage atlas;
    private record AtlasRegion(int level, int x, int y, int width, int height, int[] rgba) {
    }
    private final List<AtlasRegion> pendingAtlasRegions = new ArrayList<>();
    private GpuBuffer atlasStaging;

    // Render resolution
    private GpuImage indirect, direct, specular, albedo, normalDepth, prevNormalDepth, motion, depth, waterVeil;
    private final GpuImage[] history = new GpuImage[2];
    private GpuImage atrousA, atrousB, denoised;
    // Display resolution
    private final GpuImage[] taauHistory = new GpuImage[2];
    private GpuImage dlssOutput, output;

    private int displayW, displayH, renderW, renderH;
    private UpscalerBackend activeBackend;
    private UpscaleQuality activeQuality;
    private long frameIndex;
    private int parity;
    private boolean resetHistory = true;

    /**
     * @param glDeviceUuid      {@code GL_DEVICE_UUID_EXT}, so Vulkan runs on the same GPU as OpenGL (nullable)
     * @param dataDir           writable directory (DLSS logs/cache)
     * @param nativeSearchDirs  where to look for {@code helios_dlss} and the NVIDIA DLSS runtime
     */
    public HeliosRenderer(RendererSettings settings, byte[] glDeviceUuid, Path dataDir, List<Path> nativeSearchDirs) {
        this.settings = settings;
        if (settings.upscaler == UpscalerBackend.DLSS && !DlssBridge.load(nativeSearchDirs)) {
            LOG.info("DLSS unavailable (" + DlssBridge.loadError() + "); using TAAU");
        }
        List<String> instanceExts = DlssUpscaler.requiredInstanceExtensions();
        List<String> deviceExts = DlssUpscaler.requiredDeviceExtensions();
        // LWJGL's VkInstance/VkDevice constructors need more than the render thread's 64 KiB stack.
        ctx = HeliosStack.callWithLargeStack("Helios Vulkan init",
                () -> new VulkanContext(glDeviceUuid, instanceExts, deviceExts, settings.validation));
        LOG.info("Helios ray tracing on " + ctx.deviceName);

        DlssUpscaler dlssCandidate = null;
        if (DlssBridge.isLoaded()) {
            Path runtimeDir = nativeSearchDirs.isEmpty() ? dataDir : nativeSearchDirs.get(0);
            dlssCandidate = DlssUpscaler.create(ctx, dataDir, runtimeDir);
            if (dlssCandidate == null) LOG.warning("DLSS not supported on this system: " + DlssUpscaler.lastError());
        }
        dlss = dlssCandidate;

        scene = new SceneAccel(ctx);
        descriptorPool = Descriptors.createPool(ctx, 32);
        linearSampler = Descriptors.sampler(ctx, VK_FILTER_LINEAR);
        frameUbo = GpuBuffer.hostVisible(ctx, FrameUniforms.SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);

        int storage = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        int sampled = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            rtPipeline = new RayTracingPipeline(ctx, compiler);
            temporal = new ComputePass(ctx, compiler, descriptorPool, "temporal.comp", 16, 2,
                    storage, storage, storage, storage, storage, storage);
            atrous = new ComputePass(ctx, compiler, descriptorPool, "atrous.comp", 16, 4,
                    storage, storage, storage);
            modulate = new ComputePass(ctx, compiler, descriptorPool, "modulate.comp", 16, 5,
                    storage, storage, storage, storage, storage);
            taau = new ComputePass(ctx, compiler, descriptorPool, "taau.comp", 32, 2,
                    sampled, storage, sampled, storage);
            tonemap = new ComputePass(ctx, compiler, descriptorPool, "tonemap.comp", 16, 3,
                    storage, storage);
        }
        rtSet = Descriptors.allocate(ctx, descriptorPool, rtPipeline.setLayout);
        Descriptors.buffer(ctx, rtSet, BINDING_FRAME, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, frameUbo);

        lightBuffer = GpuBuffer.hostVisible(ctx, 1024L * LightGrid.LIGHT_STRIDE * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        lightCellBuffer = GpuBuffer.hostVisible(ctx, LightGrid.CELL_COUNT * 8L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        lightIndexBuffer = GpuBuffer.hostVisible(ctx, 16384L * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        probeInput = GpuBuffer.hostVisible(ctx, MAX_PROBES * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        probeOutput = GpuBuffer.readback(ctx, MAX_PROBES * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        Descriptors.buffer(ctx, rtSet, BINDING_PROBES_IN, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, probeInput);
        Descriptors.buffer(ctx, rtSet, BINDING_PROBES_OUT, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, probeOutput);
        bindLightBuffers();

        cmd = Commands.allocate(ctx);
        fence = Commands.createFence(ctx);
        queryPool = createQueryPool();

        ByteBuffer white = MemoryUtil.memAlloc(4).putInt(0, -1);
        uploadAtlas(1, 1, List.of(white));
        MemoryUtil.memFree(white);
    }

    private long createQueryPool() {
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(TIMESTAMPS);
            var p = stack.mallocLong(1);
            VkCheck.check(vkCreateQueryPool(ctx.device, info, null, p), "vkCreateQueryPool");
            return p.get(0);
        }
    }

    // ------------------------------------------------------------------ scene

    public void uploadSection(int sx, int sy, int sz, SectionGeometry geometry) {
        scene.upload(sx, sy, sz, geometry);
    }

    public void removeSection(int sx, int sy, int sz) {
        scene.remove(sx, sy, sz);
        lightGrid.removeSection(sx, sy, sz);
    }

    /**
     * Light-emitting blocks of a section: {@link LightGrid#INPUT_STRIDE} floats each
     * (section-local x, y, z, intensity 0-1, r, g, b).
     */
    public void setSectionLights(int sx, int sy, int sz, float[] lights, int count) {
        lightGrid.setSectionLights(sx, sy, sz, lights, count);
    }

    /**
     * Entity lighting probes for this frame (xyz relative to a world-space origin). Results arrive
     * one frame later through {@link #probeResults()}.
     */
    public void setProbes(float[] positions, int count, double originX, double originY, double originZ) {
        probeCount = Math.min(count, MAX_PROBES);
        System.arraycopy(positions, 0, probePositions, 0, probeCount * 3);
        probeOriginX = originX;
        probeOriginY = originY;
        probeOriginZ = originZ;
    }

    /** Per probe of the last completed frame: sun visibility (0-1), water depth above (blocks). */
    public float[] probeResults() {
        return probeResults;
    }

    public int probeResultCount() {
        return probeResultCount;
    }

    public void clearSections() {
        scene.clear();
        lightGrid.clear();
        resetHistory = true;
        uniforms.reset();
    }

    public int sectionCount() {
        return scene.sectionCount();
    }

    /**
     * Shadow-only geometry for this frame (entity models): quads of 4 xyz vertices, relative to a
     * world-space origin (usually the camera).
     */
    public void setShadowGeometry(float[] vertices, int quadCount, double originX, double originY, double originZ) {
        scene.setShadowGeometry(vertices, quadCount, originX, originY, originZ);
    }

    /**
     * Uploads Minecraft's block atlas (RGBA8, sRGB, row 0 = v 0) with its mip chain: level i is
     * {@code max(1, width >> i) x max(1, height >> i)}.
     */
    public void uploadAtlas(int width, int height, List<ByteBuffer> levels) {
        ctx.waitIdle();
        int mipLevels = levels.size();
        GpuImage image = GpuImage.createMipmapped(ctx, width, height, VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, mipLevels);
        long total = 0;
        for (int i = 0; i < mipLevels; i++) total += levelBytes(width, height, i);
        try (GpuBuffer staging = GpuBuffer.hostVisible(ctx, total, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            long[] offsets = new long[mipLevels];
            long offset = 0;
            for (int i = 0; i < mipLevels; i++) {
                offsets[i] = offset;
                MemoryUtil.memCopy(MemoryUtil.memAddress(levels.get(i)), staging.mappedAddress() + offset, levelBytes(width, height, i));
                offset += levelBytes(width, height, i);
            }
            staging.flush();
            Commands.immediate(ctx, c -> {
                Barriers.transition(c, image.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, mipLevels);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(mipLevels, stack);
                    for (int i = 0; i < mipLevels; i++) {
                        int level = i;
                        regions.get(i)
                                .bufferOffset(offsets[i])
                                .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1))
                                .imageExtent(e -> e.set(Math.max(1, width >> level), Math.max(1, height >> level), 1));
                    }
                    vkCmdCopyBufferToImage(c, staging.handle, image.image, VK_IMAGE_LAYOUT_GENERAL, regions);
                }
                Barriers.full(c);
            });
        }
        if (atlas != null) atlas.close();
        if (atlasSampler != VK_NULL_HANDLE) vkDestroySampler(ctx.device, atlasSampler, null);
        pendingAtlasRegions.clear();
        atlas = image;
        atlasSampler = Descriptors.sampler(ctx, VK_FILTER_NEAREST, mipLevels - 1);
        Descriptors.sampledImage(ctx, rtSet, BINDING_ATLAS, atlas, atlasSampler);
    }

    /**
     * Queues an update of part of the atlas (animated textures: water, lava, fire...). Pixels are
     * RGBA8 packed as Minecraft's NativeImage ints (R in the low byte). Applied in the next frame.
     */
    public void updateAtlasRegion(int level, int x, int y, int width, int height, int[] rgba) {
        if (atlas == null || level >= atlas.mipLevels) return;
        int lw = Math.max(1, atlas.width >> level), lh = Math.max(1, atlas.height >> level);
        if (x < 0 || y < 0 || x + width > lw || y + height > lh) return;
        pendingAtlasRegions.add(new AtlasRegion(level, x, y, width, height, rgba));
    }

    /** Drops queued region updates (e.g. the whole atlas is being re-uploaded). */
    public void clearAtlasRegionUpdates() {
        pendingAtlasRegions.clear();
    }

    private void recordAtlasRegionUpdates() {
        if (pendingAtlasRegions.isEmpty()) return;
        long bytes = 0;
        for (AtlasRegion r : pendingAtlasRegions) bytes += (long) r.width * r.height * 4;
        if (atlasStaging == null || atlasStaging.size < bytes) {
            if (atlasStaging != null) atlasStaging.close(); // the previous frame has completed
            atlasStaging = GpuBuffer.hostVisible(ctx, Math.max(bytes, 1 << 16), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        }
        java.nio.IntBuffer dst = atlasStaging.mapped().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(pendingAtlasRegions.size(), stack);
            long offset = 0;
            for (int i = 0; i < pendingAtlasRegions.size(); i++) {
                AtlasRegion r = pendingAtlasRegions.get(i);
                dst.put(r.rgba, 0, r.width * r.height);
                regions.get(i)
                        .bufferOffset(offset)
                        .imageSubresource(sub -> sub.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(r.level).layerCount(1))
                        .imageOffset(o -> o.set(r.x, r.y, 0))
                        .imageExtent(e -> e.set(r.width, r.height, 1));
                offset += (long) r.width * r.height * 4;
            }
            atlasStaging.flush();
            vkCmdCopyBufferToImage(cmd, atlasStaging.handle, atlas.image, VK_IMAGE_LAYOUT_GENERAL, regions);
        }
        pendingAtlasRegions.clear();
        Barriers.full(cmd);
    }

    private static long levelBytes(int width, int height, int level) {
        return (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
    }

    // ---------------------------------------------------------------- targets

    public UpscalerBackend activeBackend() {
        return dlss != null && settings.upscaler == UpscalerBackend.DLSS ? UpscalerBackend.DLSS : UpscalerBackend.TAAU;
    }

    public boolean isDlssAvailable() {
        return dlss != null;
    }

    public String deviceName() {
        return ctx.deviceName;
    }

    public int renderWidth() {
        return renderW;
    }

    public int renderHeight() {
        return renderH;
    }

    public GpuTimings gpuTimings() {
        return timings;
    }

    public boolean usesGpuSync() {
        return vulkanDone != null;
    }

    /** True if {@link #resize} must be called (and the shared images re-imported) before the next frame. */
    public boolean needsResize(int displayWidth, int displayHeight) {
        return output == null || displayWidth != displayW || displayHeight != displayH
                || activeBackend() != activeBackend || settings.quality != activeQuality;
    }

    /** Recreates all render targets. Returns fresh handles; previously exported images become invalid. */
    public SharedTargets resize(int displayWidth, int displayHeight) {
        waitForPreviousFrame();
        ctx.waitIdle();
        destroyTargets();
        displayW = displayWidth;
        displayH = displayHeight;
        activeBackend = activeBackend();
        activeQuality = settings.quality;

        int[] size = null;
        if (activeBackend == UpscalerBackend.DLSS) {
            size = dlss.optimalRenderSize(displayW, displayH, activeQuality.ngxPerfQuality);
        }
        renderW = size != null && size[0] > 0 ? size[0] : activeQuality.renderWidth(displayW);
        renderH = size != null && size[1] > 0 ? size[1] : activeQuality.renderHeight(displayH);

        indirect = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        direct = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        specular = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        albedo = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        normalDepth = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        prevNormalDepth = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        motion = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        depth = GpuImage.createExportable(ctx, renderW, renderH, VK_FORMAT_R32G32_SFLOAT, USAGE_STORAGE);
        waterVeil = GpuImage.createExportable(ctx, renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT, USAGE_STORAGE);
        history[0] = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        history[1] = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        atrousA = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        atrousB = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        denoised = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        taauHistory[0] = image(displayW, displayH, VK_FORMAT_R16G16B16A16_SFLOAT);
        taauHistory[1] = image(displayW, displayH, VK_FORMAT_R16G16B16A16_SFLOAT);
        dlssOutput = image(displayW, displayH, VK_FORMAT_R16G16B16A16_SFLOAT);
        output = GpuImage.createExportable(ctx, displayW, displayH, VK_FORMAT_R8G8B8A8_UNORM, USAGE_STORAGE);

        List<GpuImage> all = allTargets();
        Commands.immediate(ctx, c -> {
            try (MemoryStack stack = stackPush()) {
                VkClearColorValue black = VkClearColorValue.calloc(stack);
                for (GpuImage img : all) {
                    Barriers.transition(c, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                    vkCmdClearColorImage(c, img.image, VK_IMAGE_LAYOUT_GENERAL, black, Barriers.colorRange(stack));
                }
            }
            Barriers.full(c);
        });

        writeTargetDescriptors();
        if (activeBackend == UpscalerBackend.DLSS) {
            dlss.prepare(ctx, renderW, renderH, displayW, displayH, activeQuality.ngxPerfQuality);
        }
        resetHistory = true;
        uniforms.reset();
        LOG.info("Helios targets: render " + renderW + "x" + renderH + " -> display " + displayW + "x" + displayH
                + " via " + activeBackend);

        boolean win32 = ctx.externalHandleType == VulkanContext.ExternalHandleType.OPAQUE_WIN32;
        return new SharedTargets(
                new SharedImage(output.exportHandle(), output.exportSize, displayW, displayH),
                new SharedImage(depth.exportHandle(), depth.exportSize, renderW, renderH),
                new SharedImage(waterVeil.exportHandle(), waterVeil.exportSize, renderW, renderH),
                win32);
    }

    /**
     * Creates the GL interop semaphores (once). Returns null if the device cannot export
     * semaphores; frames are then synchronized with CPU waits.
     */
    public SharedSemaphores exportSemaphores() {
        if (!ctx.externalSemaphores) return null;
        if (vulkanDone == null) {
            vulkanDone = new SharedSemaphore(ctx);
            glDone = new SharedSemaphore(ctx);
        }
        return new SharedSemaphores(vulkanDone.export(), glDone.export(),
                ctx.externalHandleType == VulkanContext.ExternalHandleType.OPAQUE_WIN32);
    }

    /** Stops using the interop semaphores (e.g. GL could not import them). */
    public void disableGpuSync() {
        ctx.waitIdle();
        if (vulkanDone != null) {
            vulkanDone.close();
            glDone.close();
            vulkanDone = glDone = null;
        }
        glSignaled = false;
    }

    /** Called after OpenGL signalled {@code glDone}: the next frame waits for it on the GPU. */
    public void markGlSignaled() {
        glSignaled = true;
    }

    private GpuImage image(int w, int h, int format) {
        return GpuImage.create(ctx, w, h, format, USAGE_STORAGE);
    }

    private List<GpuImage> allTargets() {
        List<GpuImage> list = new ArrayList<>(List.of(indirect, direct, specular, albedo, normalDepth, prevNormalDepth,
                motion, depth, waterVeil, history[0], history[1], atrousA, atrousB, denoised, taauHistory[0], taauHistory[1],
                dlssOutput, output));
        list.removeIf(java.util.Objects::isNull);
        return list;
    }

    private void writeTargetDescriptors() {
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_INDIRECT, indirect);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_ALBEDO, albedo);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_NORMAL_DEPTH, normalDepth);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_MOTION, motion);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_DEPTH, depth);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_DIRECT, direct);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_SPECULAR, specular);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_WATER_VEIL, waterVeil);

        for (int p = 0; p < 2; p++) {
            long set = temporal.set(p);
            Descriptors.storageImage(ctx, set, 0, indirect);
            Descriptors.storageImage(ctx, set, 1, normalDepth);
            Descriptors.storageImage(ctx, set, 2, motion);
            Descriptors.storageImage(ctx, set, 3, prevNormalDepth);
            Descriptors.storageImage(ctx, set, 4, history[1 - p]);
            Descriptors.storageImage(ctx, set, 5, history[p]);
        }

        // À-trous: 0/1 = history[p] -> A, 2 = A -> B, 3 = B -> A.
        GpuImage[][] atrousIo = {{history[0], atrousA}, {history[1], atrousA}, {atrousA, atrousB}, {atrousB, atrousA}};
        for (int i = 0; i < atrousIo.length; i++) {
            long set = atrous.set(i);
            Descriptors.storageImage(ctx, set, 0, atrousIo[i][0]);
            Descriptors.storageImage(ctx, set, 1, normalDepth);
            Descriptors.storageImage(ctx, set, 2, atrousIo[i][1]);
        }

        GpuImage[] modulateInputs = {atrousA, atrousB, history[0], history[1], indirect};
        for (int i = 0; i < modulateInputs.length; i++) {
            long set = modulate.set(i);
            Descriptors.storageImage(ctx, set, 0, modulateInputs[i]);
            Descriptors.storageImage(ctx, set, 1, albedo);
            Descriptors.storageImage(ctx, set, 2, denoised);
            Descriptors.storageImage(ctx, set, 3, direct);
            Descriptors.storageImage(ctx, set, 4, specular);
        }

        for (int p = 0; p < 2; p++) {
            long set = taau.set(p);
            Descriptors.sampledImage(ctx, set, 0, denoised, linearSampler);
            Descriptors.storageImage(ctx, set, 1, motion);
            Descriptors.sampledImage(ctx, set, 2, taauHistory[1 - p], linearSampler);
            Descriptors.storageImage(ctx, set, 3, taauHistory[p]);
        }

        GpuImage[] tonemapInputs = {taauHistory[0], taauHistory[1], dlssOutput};
        for (int i = 0; i < tonemapInputs.length; i++) {
            long set = tonemap.set(i);
            Descriptors.storageImage(ctx, set, 0, tonemapInputs[i]);
            Descriptors.storageImage(ctx, set, 1, output);
        }
    }

    // ------------------------------------------------------------------ frame

    /**
     * Blocks until the previous frame has finished on the GPU. Call before changing scene content
     * (section uploads/removals, shadow casters); {@link #renderFrame} calls it too.
     */
    public void waitForPreviousFrame() {
        if (!frameInFlight) return;
        Commands.waitAndReset(ctx, fence);
        frameInFlight = false;
        readTimings();
        if (probesInFlight > 0) {
            probeOutput.invalidate();
            java.nio.FloatBuffer out = probeOutput.mapped().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            for (int i = 0; i < probesInFlight; i++) {
                probeResults[i * 2] = out.get(i * 4);
                probeResults[i * 2 + 1] = out.get(i * 4 + 1);
            }
        }
        probeResultCount = probesInFlight;
        probesInFlight = 0;
    }

    private void updateLightGrid(FrameInput in) {
        int camSx = (int) Math.floor(in.cameraX() / 16.0);
        int camSy = (int) Math.floor(in.cameraY() / 16.0);
        int camSz = (int) Math.floor(in.cameraZ() / 16.0);
        if (lightGrid.build(camSx, camSy, camSz)) {
            int lights = lightGrid.lightCount();
            if (lightBuffer.size < (long) lights * LightGrid.LIGHT_STRIDE * 4) {
                lightBuffer.close();
                lightBuffer = GpuBuffer.hostVisible(ctx, (long) lights * LightGrid.LIGHT_STRIDE * 8, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
            if (lightIndexBuffer.size < lightGrid.indexCount() * 4L) {
                lightIndexBuffer.close();
                lightIndexBuffer = GpuBuffer.hostVisible(ctx, lightGrid.indexCount() * 8L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
            lightBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(lightGrid.lights(), 0, lights * LightGrid.LIGHT_STRIDE);
            lightCellBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(lightGrid.cells());
            lightIndexBuffer.mapped().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(lightGrid.indices(), 0, lightGrid.indexCount());
            lightBuffer.flush();
            lightCellBuffer.flush();
            lightIndexBuffer.flush();
            bindLightBuffers();
            pointLightsEnabled = lights > 0;
        }
        uniforms.setLightGrid(lightGrid.originBlockX(), lightGrid.originBlockY(), lightGrid.originBlockZ(), pointLightsEnabled);
    }

    private void bindLightBuffers() {
        Descriptors.buffer(ctx, rtSet, BINDING_LIGHTS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, lightBuffer);
        Descriptors.buffer(ctx, rtSet, BINDING_LIGHT_CELLS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, lightCellBuffer);
        Descriptors.buffer(ctx, rtSet, BINDING_LIGHT_INDICES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, lightIndexBuffer);
    }

    private void writeProbes() {
        java.nio.FloatBuffer dst = probeInput.mapped().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float ox = (float) (probeOriginX - uniforms.anchorX());
        float oy = (float) (probeOriginY - uniforms.anchorY());
        float oz = (float) (probeOriginZ - uniforms.anchorZ());
        for (int i = 0; i < probeCount; i++) {
            dst.put(i * 4, probePositions[i * 3] + ox);
            dst.put(i * 4 + 1, probePositions[i * 3 + 1] + oy);
            dst.put(i * 4 + 2, probePositions[i * 3 + 2] + oz);
            dst.put(i * 4 + 3, 0f);
        }
        probeInput.flush();
    }

    public void renderFrame(FrameInput in) {
        if (output == null) throw new IllegalStateException("resize() must be called before renderFrame()");
        waitForPreviousFrame();

        uniforms.update(in.cameraX(), in.cameraY(), in.cameraZ(), in.viewRotation(), in.projection());
        updateLightGrid(in);
        float[] jitter = Jitter.offset(frameIndex, Jitter.phaseCount(renderW, displayW));
        int flags = (resetHistory ? FrameUniforms.FLAG_ACCUMULATE_RESET : 0)
                | (in.cameraUnderwater() ? FrameUniforms.FLAG_CAMERA_UNDERWATER : 0);
        uniforms.write(frameUbo.mapped(), jitter, renderW, renderH, in.lightDirection(), in.lightIntensity(),
                in.skyColor(), in.rain(), (int) frameIndex, settings.maxBounces, Math.max(1, settings.samplesPerPixel),
                flags, FrameUniforms.pixelAngle(in.projection(), renderH));
        frameUbo.flush();

        Commands.begin(cmd);
        vkCmdResetQueryPool(cmd, queryPool, 0, TIMESTAMPS);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);

        recordAtlasRegionUpdates();
        scene.record(cmd, uniforms.anchorX(), uniforms.anchorY(), uniforms.anchorZ());
        Descriptors.accelerationStructure(ctx, rtSet, BINDING_TLAS, scene.tlas());
        Descriptors.buffer(ctx, rtSet, BINDING_GEOMETRY_TABLE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, scene.geometryTable());

        rtPipeline.trace(cmd, rtSet, renderW, renderH);
        if (probeCount > 0) {
            writeProbes();
            rtPipeline.traceProbes(cmd, rtSet, probeCount);
            probesInFlight = probeCount;
        }
        Barriers.full(cmd);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);

        int modulateSet = MOD_FROM_RAW;
        if (settings.denoiser) {
            temporal.dispatch(cmd, parity, push().putInt(renderW).putInt(renderH).putInt(resetHistory ? 1 : 0)
                    .putFloat(MAX_HISTORY).flip(), renderW, renderH);
            Barriers.full(cmd);
            int iterations = Math.max(0, Math.min(5, settings.denoiserIterations));
            for (int i = 0; i < iterations; i++) {
                int set = i == 0 ? parity : (i % 2 == 1 ? 2 : 3);
                atrous.dispatch(cmd, set, push().putInt(renderW).putInt(renderH).putInt(1 << i).putInt(0).flip(),
                        renderW, renderH);
                Barriers.full(cmd);
            }
            modulateSet = iterations == 0 ? MOD_FROM_HISTORY0 + parity : (iterations % 2 == 1 ? MOD_FROM_A : MOD_FROM_B);
        }
        modulate.dispatch(cmd, modulateSet, push().putInt(renderW).putInt(renderH).putInt(0).putInt(0).flip(),
                renderW, renderH);
        Barriers.full(cmd);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 2);

        int tonemapSet;
        if (activeBackend == UpscalerBackend.DLSS) {
            int mode = settings.dlssJitterMode & 3;
            float jx = mode >= 2 ? -jitter[0] : jitter[0];
            float jy = mode == 1 || mode == 2 ? -jitter[1] : jitter[1];
            dlss.evaluate(cmd, denoised, depth, motion, dlssOutput, jx, jy, resetHistory);
            tonemapSet = 2;
        } else {
            float blend = renderW == displayW ? 0.1f : 0.06f;
            taau.dispatch(cmd, parity, push()
                    .putFloat(renderW).putFloat(renderH).putFloat(displayW).putFloat(displayH)
                    .putFloat(jitter[0]).putFloat(jitter[1]).putInt(resetHistory ? 1 : 0).putFloat(blend).flip(),
                    displayW, displayH);
            tonemapSet = parity;
        }
        Barriers.full(cmd);

        tonemap.dispatch(cmd, tonemapSet, push().putInt(displayW).putInt(displayH).putFloat(settings.exposure).putInt(0).flip(),
                displayW, displayH);
        copyImage(normalDepth, prevNormalDepth);
        Barriers.full(cmd);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 3);

        if (vulkanDone != null) {
            Commands.submit(ctx, cmd, fence, glSignaled ? glDone.handle : VK_NULL_HANDLE, vulkanDone.handle);
            glSignaled = false;
            frameInFlight = true;
        } else {
            // No GPU-side interop: GL may only read the shared images once Vulkan is done.
            Commands.submit(ctx, cmd, fence, VK_NULL_HANDLE, VK_NULL_HANDLE);
            frameInFlight = true;
            waitForPreviousFrame();
        }
        parity ^= 1;
        frameIndex++;
        resetHistory = false;
    }

    private void readTimings() {
        if (ctx.timestampPeriod <= 0f) return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer ts = stack.mallocLong(TIMESTAMPS);
            if (vkGetQueryPoolResults(ctx.device, queryPool, 0, TIMESTAMPS, ts, 8, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
                return;
            }
            float ms = ctx.timestampPeriod / 1.0e6f;
            timings = new GpuTimings((ts.get(1) - ts.get(0)) * ms, (ts.get(2) - ts.get(1)) * ms,
                    (ts.get(3) - ts.get(2)) * ms, (ts.get(3) - ts.get(0)) * ms);
        }
    }

    private ByteBuffer push() {
        return push.clear();
    }

    private void copyImage(GpuImage src, GpuImage dst) {
        try (MemoryStack stack = stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack)
                    .srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .extent(e -> e.set(src.width, src.height, 1));
            vkCmdCopyImage(cmd, src.image, VK_IMAGE_LAYOUT_GENERAL, dst.image, VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private void destroyTargets() {
        if (output == null) return;
        allTargets().forEach(GpuImage::close);
        indirect = direct = specular = albedo = normalDepth = prevNormalDepth = motion = depth = waterVeil = null;
        atrousA = atrousB = denoised = dlssOutput = output = null;
        history[0] = history[1] = taauHistory[0] = taauHistory[1] = null;
    }

    @Override
    public void close() {
        ctx.waitIdle();
        destroyTargets();
        if (dlss != null) dlss.close();
        scene.close();
        if (atlas != null) atlas.close();
        if (atlasStaging != null) atlasStaging.close();
        temporal.close();
        atrous.close();
        modulate.close();
        taau.close();
        tonemap.close();
        rtPipeline.close();
        frameUbo.close();
        lightBuffer.close();
        lightCellBuffer.close();
        lightIndexBuffer.close();
        probeInput.close();
        probeOutput.close();
        if (vulkanDone != null) {
            vulkanDone.close();
            glDone.close();
        }
        vkDestroySampler(ctx.device, linearSampler, null);
        if (atlasSampler != VK_NULL_HANDLE) vkDestroySampler(ctx.device, atlasSampler, null);
        vkDestroyQueryPool(ctx.device, queryPool, null);
        vkDestroyDescriptorPool(ctx.device, descriptorPool, null);
        vkDestroyFence(ctx.device, fence, null);
        MemoryUtil.memFree(push);
        ctx.close();
    }
}
