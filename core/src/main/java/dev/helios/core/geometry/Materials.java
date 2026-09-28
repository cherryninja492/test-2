package dev.helios.core.geometry;

/**
 * Per-vertex material bits shared with the shaders ({@code MAT_*} in {@code common.glsl}).
 *
 * <pre>
 *  bits 0-3  block light emission (0-15)
 *  bit  4    cutout: alpha tested in the any-hit shader
 *  bit  5    water: specular reflections with Fresnel; transparent to shadow rays
 * </pre>
 *
 * Water is routed through the any-hit shader like cutout geometry, so shadow rays can skip it.
 */
public final class Materials {
    public static final int EMISSION_MASK = 0xF;
    public static final int CUTOUT = 1 << 4;
    public static final int WATER = 1 << 5;

    private Materials() {
    }

    public static int pack(int emission, boolean cutout, boolean water) {
        int m = Math.max(0, Math.min(15, emission));
        if (cutout) m |= CUTOUT;
        if (water) m |= WATER;
        return m;
    }

    public static int emission(int material) {
        return material & EMISSION_MASK;
    }

    /** True if the quad needs the any-hit shader (alpha tested or water), i.e. belongs to the non-opaque BLAS geometry. */
    public static boolean isCutout(int material) {
        return (material & (CUTOUT | WATER)) != 0;
    }

    public static boolean isWater(int material) {
        return (material & WATER) != 0;
    }
}
