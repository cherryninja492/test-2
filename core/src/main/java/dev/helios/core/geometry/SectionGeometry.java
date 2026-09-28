package dev.helios.core.geometry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * CPU-side triangle soup for one 16x16x16 chunk section, in the vertex layout the ray tracing
 * shaders read through buffer references (see {@code common.glsl}, struct {@code Vertex}).
 *
 * <p>Geometry is stored as quads (4 vertices, 2 triangles each). Opaque quads come first and
 * alpha-tested ("cutout") quads second, so the BLAS can mark the first geometry opaque and skip the
 * any-hit shader for it. Positions are local to the section origin.
 */
public final class SectionGeometry {
    /** Bytes per vertex: vec3 pos, vec2 uv, uint color, uint material, uint pad (scalar layout). */
    public static final int VERTEX_STRIDE = 32;
    public static final int VERTICES_PER_QUAD = 4;
    public static final int INDICES_PER_QUAD = 6;
    private static final int INTS_PER_VERTEX = VERTEX_STRIDE / 4;
    private static final int INTS_PER_QUAD = INTS_PER_VERTEX * VERTICES_PER_QUAD;

    private int[] opaque = new int[INTS_PER_QUAD * 64];
    private int opaqueQuads;
    private int[] cutout = new int[INTS_PER_QUAD * 16];
    private int cutoutQuads;

    /**
     * Adds a quad.
     *
     * @param positions 12 floats: x,y,z for each of the 4 vertices, counter-clockwise
     * @param uvs       8 floats: u,v for each vertex, in block-atlas space
     * @param rgb       tint colour as 0xRRGGBB
     * @param material  packed material bits, see {@link Materials}
     */
    public void addQuad(float[] positions, float[] uvs, int rgb, int material) {
        boolean isCutout = Materials.isCutout(material);
        int[] dst;
        int offset;
        if (isCutout) {
            cutout = ensure(cutout, cutoutQuads + 1);
            dst = cutout;
            offset = cutoutQuads++ * INTS_PER_QUAD;
        } else {
            opaque = ensure(opaque, opaqueQuads + 1);
            dst = opaque;
            offset = opaqueQuads++ * INTS_PER_QUAD;
        }
        int color = packColor(rgb);
        for (int v = 0; v < VERTICES_PER_QUAD; v++) {
            int o = offset + v * INTS_PER_VERTEX;
            dst[o] = Float.floatToRawIntBits(positions[v * 3]);
            dst[o + 1] = Float.floatToRawIntBits(positions[v * 3 + 1]);
            dst[o + 2] = Float.floatToRawIntBits(positions[v * 3 + 2]);
            dst[o + 3] = Float.floatToRawIntBits(uvs[v * 2]);
            dst[o + 4] = Float.floatToRawIntBits(uvs[v * 2 + 1]);
            dst[o + 5] = color;
            dst[o + 6] = material;
            dst[o + 7] = 0;
        }
    }

    public int opaqueQuadCount() {
        return opaqueQuads;
    }

    public int cutoutQuadCount() {
        return cutoutQuads;
    }

    public int quadCount() {
        return opaqueQuads + cutoutQuads;
    }

    public boolean isEmpty() {
        return quadCount() == 0;
    }

    public long sizeBytes() {
        return (long) quadCount() * VERTICES_PER_QUAD * VERTEX_STRIDE;
    }

    /** Writes opaque then cutout vertices at the buffer's position (little-endian, as the GPU expects). */
    public void writeTo(ByteBuffer dst) {
        ByteBuffer le = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        le.asIntBuffer().put(opaque, 0, opaqueQuads * INTS_PER_QUAD)
                .put(cutout, 0, cutoutQuads * INTS_PER_QUAD);
        dst.position(dst.position() + (int) sizeBytes());
    }

    public void clear() {
        opaqueQuads = 0;
        cutoutQuads = 0;
    }

    /** Packs 0xRRGGBB into the byte order GLSL's {@code unpackUnorm4x8} expects (R in the lowest byte). */
    static int packColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r | (g << 8) | (b << 16) | (0xFF << 24);
    }

    private static int[] ensure(int[] array, int quads) {
        int needed = quads * INTS_PER_QUAD;
        return needed <= array.length ? array : Arrays.copyOf(array, Math.max(needed, array.length * 2));
    }
}
