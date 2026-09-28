package dev.helios.core.lighting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Point lights from light-emitting blocks, organised for sampling on the GPU.
 *
 * <p>Lights are registered per chunk section. For shading, a camera-centred grid of
 * {@value #CELLS_X}x{@value #CELLS_Y}x{@value #CELLS_Z} section-sized cells lists, per cell, the
 * lights of its own and neighbouring sections (block lights reach ~15 blocks), capped at the
 * {@value #MAX_LIGHTS_PER_CELL} closest. The shader looks up the cell of a shading point and
 * picks lights by resampled importance sampling.
 *
 * <p>Light positions are stored relative to the grid origin (a section corner), so the grid only
 * needs rebuilding when lights change or the camera enters another section.
 */
public final class LightGrid {
    public static final int CELLS_X = 12, CELLS_Y = 8, CELLS_Z = 12;
    public static final int CELL_COUNT = CELLS_X * CELLS_Y * CELLS_Z;
    public static final int MAX_LIGHTS_PER_CELL = 32;
    public static final int MAX_LIGHTS_PER_SECTION = 24;
    /** Floats per light in the GPU buffer: x, y, z, intensity, r, g, b, pad. */
    public static final int LIGHT_STRIDE = 8;
    /** Floats per light as registered: section-local x, y, z, intensity (0-1), r, g, b. */
    public static final int INPUT_STRIDE = 7;

    private final Map<Long, float[]> sections = new HashMap<>();
    private boolean dirty = true;
    private int originSx = Integer.MIN_VALUE, originSy, originSz;

    private float[] lights = new float[1024 * LIGHT_STRIDE];
    private int lightCount;
    private final int[] cells = new int[CELL_COUNT * 2];
    private int[] indices = new int[4096];
    private int indexCount;

    private static long key(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    /** Replaces the lights of a section ({@link #INPUT_STRIDE} floats each). */
    public void setSectionLights(int sx, int sy, int sz, float[] data, int count) {
        long k = key(sx, sy, sz);
        if (count <= 0) {
            if (sections.remove(k) != null) dirty = true;
            return;
        }
        int n = Math.min(count, MAX_LIGHTS_PER_SECTION);
        sections.put(k, Arrays.copyOf(data, n * INPUT_STRIDE));
        dirty = true;
    }

    public void removeSection(int sx, int sy, int sz) {
        if (sections.remove(key(sx, sy, sz)) != null) dirty = true;
    }

    public void clear() {
        sections.clear();
        dirty = true;
    }

    /**
     * Rebuilds the grid if needed for a camera in section (camSx, camSy, camSz).
     *
     * @return true if the GPU buffers must be re-uploaded
     */
    public boolean build(int camSx, int camSy, int camSz) {
        int ox = camSx - CELLS_X / 2, oy = camSy - CELLS_Y / 2, oz = camSz - CELLS_Z / 2;
        if (!dirty && ox == originSx && oy == originSy && oz == originSz) return false;
        originSx = ox;
        originSy = oy;
        originSz = oz;
        dirty = false;

        // Lights of all sections in the grid plus a one-section border, indexed by section.
        int ex = CELLS_X + 2, ey = CELLS_Y + 2, ez = CELLS_Z + 2;
        int[] firstLight = new int[ex * ey * ez];
        int[] sectionLightCount = new int[ex * ey * ez];
        lightCount = 0;
        for (int y = 0; y < ey; y++) {
            for (int z = 0; z < ez; z++) {
                for (int x = 0; x < ex; x++) {
                    int s = (y * ez + z) * ex + x;
                    float[] data = sections.get(key(ox + x - 1, oy + y - 1, oz + z - 1));
                    firstLight[s] = lightCount;
                    if (data == null) continue;
                    int n = data.length / INPUT_STRIDE;
                    ensureLights(lightCount + n);
                    for (int i = 0; i < n; i++) {
                        int src = i * INPUT_STRIDE, dst = (lightCount + i) * LIGHT_STRIDE;
                        // Position relative to the grid origin (block units).
                        lights[dst] = (x - 1) * 16 + data[src];
                        lights[dst + 1] = (y - 1) * 16 + data[src + 1];
                        lights[dst + 2] = (z - 1) * 16 + data[src + 2];
                        lights[dst + 3] = data[src + 3];
                        lights[dst + 4] = data[src + 4];
                        lights[dst + 5] = data[src + 5];
                        lights[dst + 6] = data[src + 6];
                        lights[dst + 7] = 0f;
                    }
                    sectionLightCount[s] = n;
                    lightCount += n;
                }
            }
        }

        indexCount = 0;
        int[] candidates = new int[27 * MAX_LIGHTS_PER_SECTION];
        float[] distances = new float[candidates.length];
        for (int cy = 0; cy < CELLS_Y; cy++) {
            for (int cz = 0; cz < CELLS_Z; cz++) {
                for (int cx = 0; cx < CELLS_X; cx++) {
                    int n = 0;
                    float centerX = cx * 16 + 8, centerY = cy * 16 + 8, centerZ = cz * 16 + 8;
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int dz = 0; dz <= 2; dz++) {
                            for (int dx = 0; dx <= 2; dx++) {
                                int s = ((cy + dy) * ez + (cz + dz)) * ex + (cx + dx);
                                for (int i = 0; i < sectionLightCount[s]; i++) {
                                    int light = firstLight[s] + i;
                                    int o = light * LIGHT_STRIDE;
                                    float ddx = lights[o] - centerX, ddy = lights[o + 1] - centerY, ddz = lights[o + 2] - centerZ;
                                    candidates[n] = light;
                                    distances[n] = (ddx * ddx + ddy * ddy + ddz * ddz) / Math.max(lights[o + 3], 1e-3f);
                                    n++;
                                }
                            }
                        }
                    }
                    if (n > MAX_LIGHTS_PER_CELL) keepClosest(candidates, distances, n, MAX_LIGHTS_PER_CELL);
                    int kept = Math.min(n, MAX_LIGHTS_PER_CELL);
                    int cell = (cy * CELLS_Z + cz) * CELLS_X + cx;
                    ensureIndices(indexCount + kept);
                    cells[cell * 2] = indexCount;
                    cells[cell * 2 + 1] = kept;
                    System.arraycopy(candidates, 0, indices, indexCount, kept);
                    indexCount += kept;
                }
            }
        }
        return true;
    }

    /** Partial selection sort: moves the {@code k} smallest distances to the front. */
    private static void keepClosest(int[] items, float[] keys, int n, int k) {
        for (int i = 0; i < k; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) if (keys[j] < keys[best]) best = j;
            float tk = keys[i];
            keys[i] = keys[best];
            keys[best] = tk;
            int ti = items[i];
            items[i] = items[best];
            items[best] = ti;
        }
    }

    private void ensureLights(int count) {
        if (count * LIGHT_STRIDE > lights.length) lights = Arrays.copyOf(lights, Math.max(count * LIGHT_STRIDE, lights.length * 2));
    }

    private void ensureIndices(int count) {
        if (count > indices.length) indices = Arrays.copyOf(indices, Math.max(count, indices.length * 2));
    }

    /** Grid origin in block coordinates (a section corner). */
    public long originBlockX() {
        return originSx * 16L;
    }

    public long originBlockY() {
        return originSy * 16L;
    }

    public long originBlockZ() {
        return originSz * 16L;
    }

    public int lightCount() {
        return lightCount;
    }

    public float[] lights() {
        return lights;
    }

    public int[] cells() {
        return cells;
    }

    public int[] indices() {
        return indices;
    }

    public int indexCount() {
        return indexCount;
    }
}
