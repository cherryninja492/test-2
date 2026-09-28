package dev.helios.core.dlss;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JNI entry points of the optional native library {@code helios_dlss} (see {@code native/dlss}),
 * a thin wrapper around NVIDIA's NGX DLSS Super Resolution API for Vulkan.
 *
 * <p>The library is not shipped in the mod jar: the DLSS SDK license requires developers to build
 * it themselves. When it is missing, Helios falls back to its own temporal upscaler.
 */
public final class DlssBridge {
    private static boolean loaded;
    private static String loadError = "not attempted";

    private DlssBridge() {
    }

    /**
     * Tries {@code <dir>/helios_dlss.dll|libhelios_dlss.so} in each directory, then {@code java.library.path}.
     */
    public static synchronized boolean load(List<Path> searchDirs) {
        if (loaded) return true;
        String file = System.mapLibraryName("helios_dlss");
        for (Path dir : searchDirs) {
            Path candidate = dir.resolve(file);
            if (Files.isRegularFile(candidate)) {
                try {
                    System.load(candidate.toAbsolutePath().toString());
                    loaded = true;
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    loadError = candidate + ": " + e.getMessage();
                }
            }
        }
        try {
            System.loadLibrary("helios_dlss");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loadError = file + " not found in " + searchDirs + " or java.library.path";
        }
        return loaded;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String loadError() {
        return loadError;
    }

    // --- natives (implemented in native/dlss/src/helios_dlss.cpp) ---

    static native String[] nativeRequiredInstanceExtensions();

    static native String[] nativeRequiredDeviceExtensions();

    /** @return opaque context pointer, or 0 on failure (see {@link #nativeLastError}) */
    static native long nativeInit(String appDataPath, String runtimeSearchPath, long vkInstance, long vkPhysicalDevice,
                                  long vkDevice, long pfnGetInstanceProcAddr, long pfnGetDeviceProcAddr);

    static native boolean nativeIsSuperSamplingAvailable(long context);

    /** @return {renderWidth, renderHeight} or null */
    static native int[] nativeOptimalRenderSize(long context, int displayWidth, int displayHeight, int perfQuality);

    /** Records feature creation into {@code vkCommandBuffer}; the buffer must be submitted before evaluating. */
    static native long nativeCreateFeature(long context, long vkCommandBuffer, int renderWidth, int renderHeight,
                                           int displayWidth, int displayHeight, int perfQuality);

    /**
     * @param resources 4 x {VkImage, VkImageView, VkFormat, width, height} for color, depth, motion and output
     */
    static native boolean nativeEvaluate(long context, long feature, long vkCommandBuffer, long[] resources,
                                         float jitterX, float jitterY, int renderWidth, int renderHeight,
                                         boolean reset, float motionScaleX, float motionScaleY);

    static native void nativeReleaseFeature(long context, long feature);

    static native void nativeShutdown(long context);

    static native String nativeLastError();
}
