package dev.helios.fabric;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.PriorityQueue;

/**
 * Tracks loaded chunks and chunk sections whose geometry must be (re)built. Independent of the
 * renderer so no events are lost while ray tracing is disabled or not yet initialized.
 */
public final class SectionTracker {
    private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    private final LongOpenHashSet dirtySections = new LongOpenHashSet();
    private ClientLevel level;
    /** Incremented whenever the tracked level changes; the renderer drops all sections when it sees a new value. */
    private int generation;

    public synchronized void onChunkLoad(ClientLevel chunkLevel, int chunkX, int chunkZ) {
        adoptLevel(chunkLevel);
        loadedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        for (int sy = chunkLevel.getMinSection(); sy < chunkLevel.getMaxSection(); sy++) {
            dirtySections.add(SectionPos.asLong(chunkX, sy, chunkZ));
        }
    }

    /** Sections to remove from the renderer are reported through {@code removed}. */
    public synchronized void onChunkUnload(ClientLevel chunkLevel, int chunkX, int chunkZ, SectionConsumer removed) {
        if (chunkLevel != level) return;
        loadedChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
        for (int sy = chunkLevel.getMinSection(); sy < chunkLevel.getMaxSection(); sy++) {
            dirtySections.remove(SectionPos.asLong(chunkX, sy, chunkZ));
            removed.accept(chunkX, sy, chunkZ);
        }
    }

    public synchronized void markDirty(int sx, int sy, int sz) {
        // Vanilla also marks neighbours above/below the world's height range; those do not exist.
        if (level != null && sy >= level.getMinSection() && sy < level.getMaxSection()
                && loadedChunks.contains(ChunkPos.asLong(sx, sz))) {
            dirtySections.add(SectionPos.asLong(sx, sy, sz));
        }
    }

    /** Re-mesh everything (resource reload changed atlas UVs, renderer recreated). */
    public synchronized void markAllDirty() {
        if (level == null) return;
        LongIterator it = loadedChunks.iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            int cx = ChunkPos.getX(chunk);
            int cz = ChunkPos.getZ(chunk);
            for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                dirtySections.add(SectionPos.asLong(cx, sy, cz));
            }
        }
    }

    public synchronized void adoptLevel(ClientLevel newLevel) {
        if (newLevel == level) return;
        level = newLevel;
        loadedChunks.clear();
        dirtySections.clear();
        generation++;
    }

    public synchronized int generation() {
        return generation;
    }

    public synchronized int pendingCount() {
        return dirtySections.size();
    }

    /** Removes and returns up to {@code budget} dirty sections, closest to the camera section first. */
    public synchronized long[] poll(int budget, int camSx, int camSy, int camSz) {
        if (dirtySections.isEmpty() || budget <= 0) return new long[0];
        // Max-heap on distance keeps the closest {budget} sections.
        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(b[1], a[1]));
        LongIterator it = dirtySections.iterator();
        while (it.hasNext()) {
            long s = it.nextLong();
            long dx = SectionPos.x(s) - camSx, dy = SectionPos.y(s) - camSy, dz = SectionPos.z(s) - camSz;
            long d = dx * dx + dy * dy + dz * dz;
            if (heap.size() < budget) {
                heap.add(new long[] {s, d});
            } else if (d < heap.peek()[1]) {
                heap.poll();
                heap.add(new long[] {s, d});
            }
        }
        long[] out = new long[heap.size()];
        int i = 0;
        for (long[] e : heap) {
            out[i++] = e[0];
            dirtySections.remove(e[0]);
        }
        return out;
    }

    @FunctionalInterface
    public interface SectionConsumer {
        void accept(int sx, int sy, int sz);
    }
}
