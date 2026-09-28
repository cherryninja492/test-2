package dev.helios.fabric;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A {@link VertexConsumer} that records quads emitted by Minecraft's own renderers (liquids,
 * entity models) instead of drawing them. Expects quad-mode geometry: every 4 vertices form a quad.
 */
final class QuadCollector implements VertexConsumer {
    @FunctionalInterface
    interface Sink {
        /** @param positions 12 floats, @param uvs 8 floats, @param rgb colour of the first vertex */
        void quad(float[] positions, float[] uvs, int rgb);
    }

    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final int[] colors = new int[4];
    private int vertex = -1;
    private Sink sink;

    QuadCollector begin(Sink sink) {
        this.sink = sink;
        vertex = -1;
        return this;
    }

    /** Emits the last quad if complete. */
    void finish() {
        if (vertex == 3) sink.quad(positions, uvs, colors[0]);
        vertex = -1;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (vertex == 3) {
            sink.quad(positions, uvs, colors[0]);
            vertex = -1;
        }
        vertex++;
        positions[vertex * 3] = x;
        positions[vertex * 3 + 1] = y;
        positions[vertex * 3 + 2] = z;
        uvs[vertex * 2] = 0f;
        uvs[vertex * 2 + 1] = 0f;
        colors[vertex] = 0xFFFFFF;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        if (vertex >= 0) colors[vertex] = (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (vertex >= 0) {
            uvs[vertex * 2] = u;
            uvs[vertex * 2 + 1] = v;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    /** Discards everything written to it. */
    static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    };
}
