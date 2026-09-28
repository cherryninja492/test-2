package dev.helios.core.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitterTest {
    @Test
    void haltonBase2() {
        assertEquals(0.5f, Jitter.halton(1, 2));
        assertEquals(0.25f, Jitter.halton(2, 2));
        assertEquals(0.75f, Jitter.halton(3, 2));
    }

    @Test
    void offsetsAreSubPixelAndRepeat() {
        int phases = Jitter.phaseCount(960, 1920);
        assertEquals(32, phases);
        for (int f = 0; f < 100; f++) {
            float[] j = Jitter.offset(f, phases);
            assertTrue(j[0] >= -0.5f && j[0] < 0.5f);
            assertTrue(j[1] >= -0.5f && j[1] < 0.5f);
        }
        assertArrayEquals(Jitter.offset(3, phases), Jitter.offset(3 + phases, phases));
    }

    @Test
    void nativeResolutionUsesMinimumPhaseCount() {
        assertEquals(8, Jitter.phaseCount(1920, 1920));
    }

    @Test
    void qualityRenderSizes() {
        assertEquals(1280, UpscaleQuality.QUALITY.renderWidth(1920));
        assertEquals(960, UpscaleQuality.PERFORMANCE.renderWidth(1920));
        assertEquals(1920, UpscaleQuality.NATIVE.renderWidth(1920));
        assertEquals(1, UpscaleQuality.ULTRA_PERFORMANCE.renderWidth(1));
    }
}
