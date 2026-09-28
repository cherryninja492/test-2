package dev.helios.core;

import dev.helios.core.dlss.DlssBridge;
import dev.helios.core.dlss.DlssUpscaler;
import dev.helios.core.geometry.SectionGeometry;
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
 * <p>Per frame: build changed BLAS + TLAS, path trace at render resolution (demodulated
 * illumination, albedo, normal/depth, motion, depth), temporal accumulation, à-trous wavelet
 * denoising, re-modulation, upscaling (DLSS or built-in TAAU) to display resolution and tonemapping
 * into an image exported to OpenGL.
 *
 * <p>All methods must be called from the thread owning the renderer (Minecraft's render thread).
 * {@link #renderFrame} waits for the GPU before returning.
 */
public final class HeliosRenderer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("Helios");

    /** A GPU image OpenGL should import with {@code GL_EXT_memory_object}. */
    public record SharedImage(long handle, long allocationSize, int width, int height) {
    }

    /**
     * @param color       RGBA8, display resolution, tonemapped and gamma encoded, row 0 = top
     * @param depth       R32F, render resolution, OpenGL window-space depth (1 = sky), row 0 = top
     * @param win32Handles true if handles are Win32 NT handles, false for POSIX fds
     */
    public record SharedTargets(SharedImage color, SharedImage depth, boolean win32Handles) {
    }

    private static final int USAGE_STORAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int MAX_HISTORY = 32;

    // Modulate pass descriptor sets, by illumination source.
    private static final int MOD_FROM_A = 0, MOD_FROM_B = 1, MOD_FROM_HISTORY0 = 2, MOD_FROM_RAW = 4;

    private final RendererSettings settings;
    private final VulkanContext ctx;
    private final SceneAccel scene;
    private final RayTracingPipeline rtPipeline;
    private final long descriptorPool;
    private final long rtSet;
    private final GpuBuffer frameUbo;
    private final long nearestSampler, linearSampler;
    private final ComputePass temporal, atrous, modulate, taau, tonemap;
    private final DlssUpscaler dlss;
    private final VkCommandBuffer cmd;
    private final long fence;
    private final FrameUniforms uniforms = new FrameUniforms();
    private final ByteBuffer push = MemoryUtil.memAlloc(32).order(ByteOrder.LITTLE_ENDIAN);

    private GpuImage atlas;

    // Render resolution
    private GpuImage illumination, albedo, normalDepth, prevNormalDepth, motion, depth;
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
        ctx = new VulkanContext(glDeviceUuid, DlssUpscaler.requiredInstanceExtensions(),
                DlssUpscaler.requiredDeviceExtensions(), settings.validation);
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
        nearestSampler = Descriptors.sampler(ctx, VK_FILTER_NEAREST);
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
                    storage, storage, storage);
            taau = new ComputePass(ctx, compiler, descriptorPool, "taau.comp", 32, 2,
                    sampled, storage, sampled, storage);
            tonemap = new ComputePass(ctx, compiler, descriptorPool, "tonemap.comp", 16, 3,
                    storage, storage);
        }
        rtSet = Descriptors.allocate(ctx, descriptorPool, rtPipeline.setLayout);
        Descriptors.buffer(ctx, rtSet, BINDING_FRAME, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, frameUbo);

        cmd = Commands.allocate(ctx);
        fence = Commands.createFence(ctx);

        ByteBuffer white = MemoryUtil.memAlloc(4).putInt(0, -1);
        uploadAtlas(1, 1, white);
        MemoryUtil.memFree(white);
    }

    // ------------------------------------------------------------------ scene

    public void uploadSection(int sx, int sy, int sz, SectionGeometry geometry) {
        scene.upload(sx, sy, sz, geometry);
    }

    public void removeSection(int sx, int sy, int sz) {
        scene.remove(sx, sy, sz);
    }

    public void clearSections() {
        scene.clear();
        resetHistory = true;
        uniforms.reset();
    }

    public int sectionCount() {
        return scene.sectionCount();
    }

    /** Uploads Minecraft's block atlas (RGBA8, sRGB, row 0 = v 0). */
    public void uploadAtlas(int width, int height, ByteBuffer rgba) {
        ctx.waitIdle();
        GpuImage image = GpuImage.create(ctx, width, height, VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = GpuBuffer.hostVisible(ctx, (long) width * height * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), staging.mappedAddress(), (long) width * height * 4);
            staging.flush();
            Commands.immediate(ctx, c -> {
                Barriers.transition(c, image.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .imageExtent(e -> e.set(width, height, 1));
                    vkCmdCopyBufferToImage(c, staging.handle, image.image, VK_IMAGE_LAYOUT_GENERAL, region);
                }
                Barriers.full(c);
            });
        }
        if (atlas != null) atlas.close();
        atlas = image;
        Descriptors.sampledImage(ctx, rtSet, BINDING_ATLAS, atlas, nearestSampler);
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

    /** True if {@link #resize} must be called (and the shared images re-imported) before the next frame. */
    public boolean needsResize(int displayWidth, int displayHeight) {
        return output == null || displayWidth != displayW || displayHeight != displayH
                || activeBackend() != activeBackend || settings.quality != activeQuality;
    }

    /** Recreates all render targets. Returns fresh handles; previously exported images become invalid. */
    public SharedTargets resize(int displayWidth, int displayHeight) {
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

        illumination = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        albedo = image(renderW, renderH, VK_FORMAT_R16G16B16A16_SFLOAT);
        normalDepth = image(renderW, renderH, VK_FORMAT_R32G32B32A32_SFLOAT);
        prevNormalDepth = image(renderW, renderH, VK_FORMAT_R32G32B32A32_SFLOAT);
        motion = image(renderW, renderH, VK_FORMAT_R16G16_SFLOAT);
        depth = GpuImage.createExportable(ctx, renderW, renderH, VK_FORMAT_R32_SFLOAT, USAGE_STORAGE);
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
                win32);
    }

    private GpuImage image(int w, int h, int format) {
        return GpuImage.create(ctx, w, h, format, USAGE_STORAGE);
    }

    private List<GpuImage> allTargets() {
        List<GpuImage> list = new ArrayList<>(List.of(illumination, albedo, normalDepth, prevNormalDepth, motion, depth,
                history[0], history[1], atrousA, atrousB, denoised, taauHistory[0], taauHistory[1], dlssOutput, output));
        list.removeIf(java.util.Objects::isNull);
        return list;
    }

    private void writeTargetDescriptors() {
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_ILLUMINATION, illumination);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_ALBEDO, albedo);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_NORMAL_DEPTH, normalDepth);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_MOTION, motion);
        Descriptors.storageImage(ctx, rtSet, BINDING_OUT_DEPTH, depth);

        for (int p = 0; p < 2; p++) {
            long set = temporal.set(p);
            Descriptors.storageImage(ctx, set, 0, illumination);
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

        GpuImage[] modulateInputs = {atrousA, atrousB, history[0], history[1], illumination};
        for (int i = 0; i < modulateInputs.length; i++) {
            long set = modulate.set(i);
            Descriptors.storageImage(ctx, set, 0, modulateInputs[i]);
            Descriptors.storageImage(ctx, set, 1, albedo);
            Descriptors.storageImage(ctx, set, 2, denoised);
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

    public void renderFrame(FrameInput in) {
        if (output == null) throw new IllegalStateException("resize() must be called before renderFrame()");

        uniforms.update(in.cameraX(), in.cameraY(), in.cameraZ(), in.viewRotation(), in.projection());
        float[] jitter = Jitter.offset(frameIndex, Jitter.phaseCount(renderW, displayW));
        uniforms.write(frameUbo.mapped(), jitter, renderW, renderH, in.lightDirection(), in.lightIntensity(),
                in.skyColor(), in.rain(), (int) frameIndex, settings.maxBounces, Math.max(1, settings.samplesPerPixel),
                resetHistory ? FrameUniforms.FLAG_ACCUMULATE_RESET : 0);
        frameUbo.flush();

        Commands.begin(cmd);
        scene.record(cmd, uniforms.anchorX(), uniforms.anchorY(), uniforms.anchorZ());
        Descriptors.accelerationStructure(ctx, rtSet, BINDING_TLAS, scene.tlas());
        Descriptors.buffer(ctx, rtSet, BINDING_GEOMETRY_TABLE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, scene.geometryTable());

        rtPipeline.trace(cmd, rtSet, renderW, renderH);
        Barriers.full(cmd);

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

        int tonemapSet;
        if (activeBackend == UpscalerBackend.DLSS) {
            dlss.evaluate(cmd, denoised, depth, motion, dlssOutput, jitter[0], jitter[1], resetHistory);
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

        Commands.submitAndWait(ctx, cmd, fence);
        parity ^= 1;
        frameIndex++;
        resetHistory = false;
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
        illumination = albedo = normalDepth = prevNormalDepth = motion = depth = null;
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
        temporal.close();
        atrous.close();
        modulate.close();
        taau.close();
        tonemap.close();
        rtPipeline.close();
        frameUbo.close();
        vkDestroySampler(ctx.device, nearestSampler, null);
        vkDestroySampler(ctx.device, linearSampler, null);
        vkDestroyDescriptorPool(ctx.device, descriptorPool, null);
        vkDestroyFence(ctx.device, fence, null);
        MemoryUtil.memFree(push);
        ctx.close();
    }
}
