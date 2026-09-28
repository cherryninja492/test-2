package dev.helios.core.dlss;

import dev.helios.core.vk.Commands;
import dev.helios.core.vk.GpuImage;
import dev.helios.core.vk.VulkanContext;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** NVIDIA DLSS Super Resolution, driven through {@link DlssBridge}. */
public final class DlssUpscaler implements AutoCloseable {
    private final long context;
    private long feature;
    private int featureRenderW, featureRenderH, featureDisplayW, featureDisplayH, featureQuality = -1;

    private DlssUpscaler(long context) {
        this.context = context;
    }

    public static List<String> requiredInstanceExtensions() {
        return DlssBridge.isLoaded() ? orEmpty(DlssBridge.nativeRequiredInstanceExtensions()) : List.of();
    }

    public static List<String> requiredDeviceExtensions() {
        return DlssBridge.isLoaded() ? orEmpty(DlssBridge.nativeRequiredDeviceExtensions()) : List.of();
    }

    private static List<String> orEmpty(String[] a) {
        return a == null ? List.of() : Arrays.asList(a);
    }

    /**
     * @return the upscaler, or null if the library is not loaded or the GPU/driver does not support DLSS
     *         (reason available from {@link DlssBridge#nativeLastError()})
     */
    public static DlssUpscaler create(VulkanContext ctx, Path dataDir, Path runtimeDir) {
        if (!DlssBridge.isLoaded()) return null;
        long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        long gdpa = VK10.vkGetInstanceProcAddr(ctx.instance, "vkGetDeviceProcAddr");
        long handle = DlssBridge.nativeInit(dataDir.toAbsolutePath().toString(), runtimeDir.toAbsolutePath().toString(),
                ctx.instance.address(), ctx.physicalDevice.address(), ctx.device.address(), gipa, gdpa);
        if (handle == 0L) return null;
        if (!DlssBridge.nativeIsSuperSamplingAvailable(handle)) {
            DlssBridge.nativeShutdown(handle);
            return null;
        }
        return new DlssUpscaler(handle);
    }

    public static String lastError() {
        return DlssBridge.isLoaded() ? DlssBridge.nativeLastError() : DlssBridge.loadError();
    }

    /** DLSS' preferred render size for a quality mode, or null to use the default scale factors. */
    public int[] optimalRenderSize(int displayW, int displayH, int ngxPerfQuality) {
        return DlssBridge.nativeOptimalRenderSize(context, displayW, displayH, ngxPerfQuality);
    }

    /** (Re)creates the DLSS feature if resolution or quality changed. Blocks on the GPU. */
    public void prepare(VulkanContext ctx, int renderW, int renderH, int displayW, int displayH, int ngxPerfQuality) {
        if (feature != 0L && renderW == featureRenderW && renderH == featureRenderH
                && displayW == featureDisplayW && displayH == featureDisplayH && ngxPerfQuality == featureQuality) {
            return;
        }
        ctx.waitIdle();
        if (feature != 0L) DlssBridge.nativeReleaseFeature(context, feature);
        feature = 0L;
        long[] created = new long[1];
        Commands.immediate(ctx, cmd -> created[0] = DlssBridge.nativeCreateFeature(context, cmd.address(),
                renderW, renderH, displayW, displayH, ngxPerfQuality));
        if (created[0] == 0L) throw new IllegalStateException("DLSS feature creation failed: " + lastError());
        feature = created[0];
        featureRenderW = renderW;
        featureRenderH = renderH;
        featureDisplayW = displayW;
        featureDisplayH = displayH;
        featureQuality = ngxPerfQuality;
    }

    /**
     * Records the upscale. Motion vectors are in UV units (previous - current), so they are scaled
     * by the render size to get pixels as DLSS expects.
     */
    public void evaluate(VkCommandBuffer cmd, GpuImage color, GpuImage depth, GpuImage motion, GpuImage output,
                         float jitterX, float jitterY, boolean reset) {
        long[] resources = new long[20];
        GpuImage[] images = {color, depth, motion, output};
        for (int i = 0; i < 4; i++) {
            resources[i * 5] = images[i].image;
            resources[i * 5 + 1] = images[i].view;
            resources[i * 5 + 2] = images[i].format;
            resources[i * 5 + 3] = images[i].width;
            resources[i * 5 + 4] = images[i].height;
        }
        if (!DlssBridge.nativeEvaluate(context, feature, cmd.address(), resources, jitterX, jitterY,
                color.width, color.height, reset, color.width, color.height)) {
            throw new IllegalStateException("DLSS evaluate failed: " + lastError());
        }
    }

    @Override
    public void close() {
        if (feature != 0L) DlssBridge.nativeReleaseFeature(context, feature);
        DlssBridge.nativeShutdown(context);
    }
}
