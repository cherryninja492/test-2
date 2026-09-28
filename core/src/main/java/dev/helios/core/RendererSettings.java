package dev.helios.core;

import dev.helios.core.math.UpscaleQuality;
import dev.helios.core.math.UpscalerBackend;

/** Mutable user-facing settings; read by the renderer at the start of each frame. */
public final class RendererSettings {
    public UpscalerBackend upscaler = UpscalerBackend.DLSS;
    public UpscaleQuality quality = UpscaleQuality.QUALITY;
    /** Diffuse bounces after the primary hit. 0 = direct lighting only. */
    public int maxBounces = 2;
    public int samplesPerPixel = 1;
    public boolean denoiser = true;
    public int denoiserIterations = 4;
    public float exposure = 1.0f;
    /** Enables Vulkan validation layers (slow; for development). */
    public boolean validation = false;
}
