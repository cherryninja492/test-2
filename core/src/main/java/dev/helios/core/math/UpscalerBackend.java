package dev.helios.core.math;

public enum UpscalerBackend {
    /** NVIDIA DLSS Super Resolution through the native {@code helios_dlss} bridge (RTX GPUs only). */
    DLSS,
    /** Built-in temporal upscaler (compute shader); works on any ray tracing capable GPU. */
    TAAU
}
