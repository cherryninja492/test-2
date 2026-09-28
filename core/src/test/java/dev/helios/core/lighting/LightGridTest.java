package dev.helios.core.lighting;

import org.junit.jupiter.api.Test;

import static dev.helios.core.lighting.LightGrid.*;
import static org.junit.jupiter.api.Assertions.*;

class LightGridTest {
    private static float[] light(float x, float y, float z, float intensity) {
        return new float[] {x, y, z, intensity, 1f, 0.7f, 0.4f};
    }

    private static int cellIndex(int cx, int cy, int cz) {
        return (cy * CELLS_Z + cz) * CELLS_X + cx;
    }

    @Test
    void lightIsVisibleFromItsOwnAndNeighbouringCells() {
        LightGrid grid = new LightGrid();
        grid.setSectionLights(10, 4, -3, light(8.5f, 2.5f, 8.5f, 14 / 15f), 1);
        assertTrue(grid.build(10, 4, -3));

        // Camera section sits at cell (6, 4, 6); the light is in the same section.
        assertEquals((10 - 6) * 16L, grid.originBlockX());
        assertEquals(1, grid.lightCount());
        float[] lights = grid.lights();
        assertEquals(6 * 16 + 8.5f, lights[0]);
        assertEquals(4 * 16 + 2.5f, lights[1]);
        assertEquals(14 / 15f, lights[3], 1e-6);

        int[] cells = grid.cells();
        for (int dx = -1; dx <= 1; dx++) {
            int c = cellIndex(6 + dx, 4, 6);
            assertEquals(1, cells[c * 2 + 1], "cell offset " + dx);
            assertEquals(0, grid.indices()[cells[c * 2]]);
        }
        assertEquals(0, cells[cellIndex(8, 4, 6) * 2 + 1], "two sections away: out of reach");
    }

    @Test
    void rebuildsOnlyWhenNeeded() {
        LightGrid grid = new LightGrid();
        assertTrue(grid.build(0, 0, 0));
        assertFalse(grid.build(0, 0, 0));
        assertTrue(grid.build(1, 0, 0), "camera moved to another section");
        grid.setSectionLights(1, 0, 0, light(1, 1, 1, 1), 1);
        assertTrue(grid.build(1, 0, 0), "lights changed");
        grid.removeSection(1, 0, 0);
        assertTrue(grid.build(1, 0, 0));
        assertEquals(0, grid.lightCount());
    }

    @Test
    void cellsKeepTheClosestLights() {
        LightGrid grid = new LightGrid();
        float[] many = new float[MAX_LIGHTS_PER_SECTION * INPUT_STRIDE];
        for (int i = 0; i < MAX_LIGHTS_PER_SECTION; i++) {
            System.arraycopy(light(i % 16 + 0.5f, 8, i / 16 * 8 + 0.5f, 1), 0, many, i * INPUT_STRIDE, INPUT_STRIDE);
        }
        // Three full sections around the camera section: more candidates than a cell can hold.
        grid.setSectionLights(0, 0, 0, many, MAX_LIGHTS_PER_SECTION);
        grid.setSectionLights(1, 0, 0, many, MAX_LIGHTS_PER_SECTION);
        grid.setSectionLights(-1, 0, 0, many, MAX_LIGHTS_PER_SECTION);
        grid.build(0, 0, 0);
        int[] cells = grid.cells();
        int c = cellIndex(CELLS_X / 2, CELLS_Y / 2, CELLS_Z / 2);
        assertEquals(MAX_LIGHTS_PER_CELL, cells[c * 2 + 1]);
        assertTrue(grid.indexCount() <= CELL_COUNT * MAX_LIGHTS_PER_CELL);
    }
}
