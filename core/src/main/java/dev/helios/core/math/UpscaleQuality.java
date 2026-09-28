package dev.helios.core.math;

/** Render-resolution presets. Scale factors match the DLSS defaults; the TAAU fallback uses the same ones. */
public enum UpscaleQuality {
    /** Native resolution with temporal anti-aliasing (DLAA when DLSS is the backend). */
    NATIVE(1.0f, 2),
    QUALITY(1.0f / 1.5f, 1),
    BALANCED(1.0f / 1.7241f, 0),
    PERFORMANCE(0.5f, 3),
    ULTRA_PERFORMANCE(1.0f / 3.0f, 4);

    /** Fraction of the display resolution rendered per axis. */
    public final float scale;
    /** Value of {@code NVSDK_NGX_PerfQuality_Value} for this preset. */
    public final int ngxPerfQuality;

    UpscaleQuality(float scale, int ngxPerfQuality) {
        this.scale = scale;
        this.ngxPerfQuality = ngxPerfQuality;
    }

    public int renderWidth(int displayWidth) {
        return Math.max(1, Math.round(displayWidth * scale));
    }

    public int renderHeight(int displayHeight) {
        return Math.max(1, Math.round(displayHeight * scale));
    }
}
