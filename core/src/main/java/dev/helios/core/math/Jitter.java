package dev.helios.core.math;

/** Sub-pixel camera jitter for temporal upscaling / anti-aliasing (Halton 2,3). */
public final class Jitter {
    private Jitter() {
    }

    public static float halton(int index, int base) {
        float f = 1f;
        float r = 0f;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    /**
     * Phase count recommended by the DLSS programming guide: 8 * (display / render)^2, so that each
     * display pixel receives enough distinct render samples.
     */
    public static int phaseCount(int renderWidth, int displayWidth) {
        float ratio = (float) displayWidth / renderWidth;
        return Math.max(8, (int) Math.ceil(8f * ratio * ratio));
    }

    /** Jitter in render pixels, in [-0.5, 0.5), with +y pointing down (image space). */
    public static float[] offset(long frame, int phaseCount) {
        int i = (int) (frame % phaseCount) + 1;
        return new float[] {halton(i, 2) - 0.5f, halton(i, 3) - 0.5f};
    }
}
