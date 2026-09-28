package dev.helios.core.geometry;

/**
 * Per-vertex material bits shared with the shaders ({@code MAT_*} in {@code common.glsl}).
 *
 * <pre>
 *  bits 0-3  block light emission (0-15)
 *  bit  4    cutout: alpha tested in the any-hit shader
 *  bit  5    water: specular reflections with Fresnel; transparent to shadow rays
 *  bits 8-11 Minecraft block light level (0-15) in front of the face
 *  bits 12-15 surface type: 0 diffuse, 1 glass (refractive), 2 metal, 3 polished (clear coat)
 * </pre>
 *
 * Water is routed through the any-hit shader like cutout geometry, so shadow rays can skip it.
 */
public final class Materials {
    public static final int EMISSION_MASK = 0xF;
    public static final int CUTOUT = 1 << 4;
    public static final int WATER = 1 << 5;
    public static final int BLOCK_LIGHT_SHIFT = 8;
    public static final int SURFACE_SHIFT = 12;
    public static final int SURFACE_DIFFUSE = 0;
    public static final int SURFACE_GLASS = 1;
    public static final int SURFACE_METAL = 2;
    public static final int SURFACE_POLISHED = 3;

    private Materials() {
    }

    public static int pack(int emission, boolean cutout, boolean water) {
        return pack(emission, 0, cutout, water);
    }

    public static int pack(int emission, int blockLight, boolean cutout, boolean water) {
        int m = clamp(emission) | clamp(blockLight) << BLOCK_LIGHT_SHIFT;
        if (cutout) m |= CUTOUT;
        if (water) m |= WATER;
        return m;
    }

    /** Same material with a different block light level. */
    public static int withBlockLight(int material, int blockLight) {
        return (material & ~(0xF << BLOCK_LIGHT_SHIFT)) | clamp(blockLight) << BLOCK_LIGHT_SHIFT;
    }

    public static int withSurface(int material, int surface) {
        return (material & ~(0xF << SURFACE_SHIFT)) | (surface & 0xF) << SURFACE_SHIFT;
    }

    public static int surface(int material) {
        return (material >> SURFACE_SHIFT) & 0xF;
    }

    public static int blockLight(int material) {
        return (material >> BLOCK_LIGHT_SHIFT) & 0xF;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(15, level));
    }

    public static int emission(int material) {
        return material & EMISSION_MASK;
    }

    /**
     * True if the quad needs the any-hit shader (alpha tested, water or glass), i.e. belongs to the
     * non-opaque BLAS geometry.
     */
    public static boolean isCutout(int material) {
        return (material & (CUTOUT | WATER)) != 0 || surface(material) == SURFACE_GLASS;
    }

    public static boolean isWater(int material) {
        return (material & WATER) != 0;
    }
}
