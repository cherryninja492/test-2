package dev.helios.fabric;

import dev.helios.core.HeliosRenderer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Meshes chunk sections on worker threads. The render thread snapshots a section's
 * neighbourhood ({@link RenderChunkRegion}, as vanilla does for its own chunk meshing), workers run
 * {@link SectionMesher} on it, and finished results are uploaded on the render thread. Each
 * section has a version number so results made stale by a newer change are dropped.
 */
final class MeshScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("Helios");

    private record Completed(long section, int version, SectionMesher.Result result) {
    }

    private final ExecutorService workers;
    private final ThreadLocal<SectionMesher> meshers = ThreadLocal.withInitial(SectionMesher::new);
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
    private final int maxInFlight;

    MeshScheduler() {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        maxInFlight = threads * 16;
        AtomicInteger id = new AtomicInteger();
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Helios Mesher " + id.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    int inFlight() {
        return inFlight.get();
    }

    /** How many more sections may be submitted now. */
    int capacity() {
        return Math.max(0, maxInFlight - inFlight.get());
    }

    /**
     * Snapshots and queues one section (render thread). Returns false if it is empty: the caller
     * removes it from the renderer directly.
     */
    boolean submit(Level level, RenderRegionCache cache, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        int version = versions.addTo(key, 1) + 1;
        RenderChunkRegion region = cache.createRegion(level, SectionPos.of(sx, sy, sz));
        if (region == null) return false;
        inFlight.incrementAndGet();
        workers.execute(() -> {
            try {
                completed.add(new Completed(key, version, meshers.get().mesh(region, sx, sy, sz)));
            } catch (Throwable t) {
                LOG.warn("Helios failed to mesh section {} {} {}", sx, sy, sz, t);
            } finally {
                inFlight.decrementAndGet();
            }
        });
        return true;
    }

    /** Drops any pending result for a section (it was unloaded or changed again). */
    void invalidate(int sx, int sy, int sz) {
        versions.addTo(SectionPos.asLong(sx, sy, sz), 1);
    }

    void invalidateAll() {
        // Bumping every known key makes all in-flight results stale.
        for (var e : versions.long2IntEntrySet()) e.setValue(e.getIntValue() + 1);
    }

    /** Uploads up to {@code max} finished sections (render thread). */
    int drainTo(HeliosRenderer renderer, int max) {
        int uploaded = 0;
        Completed c;
        while (uploaded < max && (c = completed.poll()) != null) {
            if (versions.get(c.section) != c.version) continue; // superseded
            int sx = SectionPos.x(c.section), sy = SectionPos.y(c.section), sz = SectionPos.z(c.section);
            renderer.uploadSection(sx, sy, sz, c.result.geometry());
            renderer.setSectionLights(sx, sy, sz, c.result.lights(), c.result.lightCount());
            uploaded++;
        }
        return uploaded;
    }

    @Override
    public void close() {
        workers.shutdownNow();
        completed.clear();
    }
}
