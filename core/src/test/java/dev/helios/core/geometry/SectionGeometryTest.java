package dev.helios.core.geometry;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SectionGeometryTest {
    private static final float[] POS = {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0};
    private static final float[] UV = {0, 0, 0, 1, 1, 1, 1, 0};

    @Test
    void opaqueQuadsPrecedeCutoutQuads() {
        SectionGeometry g = new SectionGeometry();
        g.addQuad(POS, UV, 0xFFFFFF, Materials.pack(0, true, false));
        g.addQuad(POS, UV, 0x112233, Materials.pack(7, false, false));
        g.addQuad(POS, UV, 0xFFFFFF, Materials.pack(0, false, true));

        assertEquals(1, g.opaqueQuadCount());
        assertEquals(2, g.cutoutQuadCount(), "water goes through the any-hit geometry");
        assertEquals(3L * 4 * SectionGeometry.VERTEX_STRIDE, g.sizeBytes());

        ByteBuffer buf = ByteBuffer.allocateDirect((int) g.sizeBytes()).order(ByteOrder.LITTLE_ENDIAN);
        g.writeTo(buf);
        assertEquals(buf.capacity(), buf.position());

        // First vertex belongs to the opaque (emissive) quad.
        assertEquals(0f, buf.getFloat(0));
        assertEquals(1f, buf.getFloat(4));
        assertEquals(0x11 | 0x22 << 8 | 0x33 << 16 | 0xFF << 24, buf.getInt(20), "RGBA8 with R in the low byte");
        assertEquals(7, Materials.emission(buf.getInt(24)));
        assertFalse(Materials.isCutout(buf.getInt(24)));
        // Vertex 3 of quad 0: (1, 1, 0) with uv (1, 0)
        int v3 = 3 * SectionGeometry.VERTEX_STRIDE;
        assertEquals(1f, buf.getFloat(v3));
        assertEquals(0f, buf.getFloat(v3 + 8));
        assertEquals(1f, buf.getFloat(v3 + 12));

        int secondQuad = 4 * SectionGeometry.VERTEX_STRIDE;
        assertTrue(Materials.isCutout(buf.getInt(secondQuad + 24)));
    }

    @Test
    void growsBeyondInitialCapacity() {
        SectionGeometry g = new SectionGeometry();
        for (int i = 0; i < 10_000; i++) g.addQuad(POS, UV, 0, 0);
        assertEquals(10_000, g.quadCount());
        g.clear();
        assertTrue(g.isEmpty());
    }

    @Test
    void materialPackingClampsEmission() {
        assertEquals(15, Materials.emission(Materials.pack(99, false, false)));
        assertTrue(Materials.isWater(Materials.pack(0, false, true)));
    }
}
