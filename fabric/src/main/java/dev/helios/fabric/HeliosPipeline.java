package dev.helios.fabric;

import dev.helios.core.FrameInput;
import dev.helios.core.HeliosRenderer;
import dev.helios.core.geometry.SectionGeometry;
import dev.helios.core.math.Lighting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glue between Minecraft's render loop and {@link HeliosRenderer}. Called from
 * {@code LevelRendererMixin} on the render thread.
 */
public final class HeliosPipeline {
    private static final Logger LOG = LoggerFactory.getLogger("Helios");
    public static final HeliosPipeline INSTANCE = new HeliosPipeline();

    public final SectionTracker tracker = new SectionTracker();
    private HeliosConfig config;

    private HeliosRenderer renderer;
    private GlComposite composite;
    private String failure;
    private int rendererGeneration = -1;
    private boolean atlasDirty = true;
    private final MeshScheduler meshScheduler = new MeshScheduler();
    private final EntityShadowCapture entityShadows = new EntityShadowCapture();

    // Captured at the start of LevelRenderer#renderLevel.
    private final Matrix4f viewRotation = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private Camera camera;
    private float partialTick;
    private boolean frameActive;

    private HeliosPipeline() {
    }

    void init(HeliosConfig config) {
        this.config = config;
    }

    public HeliosConfig config() {
        return config;
    }

    /** True while Helios replaces terrain/sky rendering for the current frame. */
    public boolean isRenderingFrame() {
        return frameActive;
    }

    public boolean isEnabled() {
        return config != null && config.enabled && failure == null;
    }

    public void beginLevel(DeltaTracker deltaTracker, Camera camera, Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        frameActive = isEnabled() && Minecraft.getInstance().level != null;
        EntityLighting.INSTANCE.setActive(false);
        if (!frameActive) return;
        this.camera = camera;
        this.partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        viewRotation.set(frustumMatrix);
        projection.set(projectionMatrix);
        try {
            ensureRenderer();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Renders the ray traced frame and composites it over the vanilla sky (sky pixels are left
     * as drawn by Minecraft). Returns false if vanilla terrain rendering should proceed instead.
     */
    public boolean renderAfterSky() {
        if (!frameActive) return false;
        try {
            renderFrame();
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public void markAtlasDirty() {
        atlasDirty = true;
        tracker.markAllDirty();
    }

    public void onChunkUnload(ClientLevel level, int chunkX, int chunkZ) {
        tracker.onChunkUnload(level, chunkX, chunkZ, (sx, sy, sz) -> {
            meshScheduler.invalidate(sx, sy, sz);
            if (renderer != null) renderer.removeSection(sx, sy, sz);
        });
    }

    public void toggle() {
        config.enabled = !config.enabled;
        failure = null;
        config.save();
    }

    public HeliosRenderer renderer() {
        return renderer;
    }

    private void ensureRenderer() {
        if (renderer != null) return;
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("helios");
        Path natives = configDir.resolve("natives");
        renderer = new HeliosRenderer(config.renderer, GlComposite.deviceUuid(), configDir, List.of(natives, configDir));
        composite = new GlComposite();
        composite.importSemaphores(renderer);
        atlasDirty = true;
        rendererGeneration = -1;
    }

    private void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        tracker.adoptLevel(level);

        if (rendererGeneration != tracker.generation()) {
            renderer.clearSections();
            meshScheduler.invalidateAll();
            tracker.markAllDirty();
            rendererGeneration = tracker.generation();
        }
        if (atlasDirty) {
            AtlasCapture.upload(renderer);
            AtlasAnimations.clear();
            atlasDirty = false;
        } else {
            AtlasAnimations.drainTo(renderer);
        }

        // Without GPU-side sync, the previous composite must be done reading before Vulkan writes again.
        composite.waitForGl();

        // Scene updates below may touch buffers of the previous frame: let it finish first.
        renderer.waitForPreviousFrame();

        Vec3 cam = camera.getPosition();
        meshSections(level, cam);
        EntityLighting.INSTANCE.update(renderer, level, cam, partialTick, 64.0);
        if (config.entityShadows) entityShadows.capture(renderer, level, cam, partialTick, config.entityShadowRange);
        else renderer.setShadowGeometry(new float[0], 0, cam.x, cam.y, cam.z);

        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        if (renderer.needsResize(width, height)) {
            composite.importTargets(renderer.resize(width, height));
        }

        Vector3f lightDir = new Vector3f();
        float rain = level.getRainLevel(partialTick);
        float lightIntensity = Lighting.directionalLight(level.getSunAngle(partialTick), rain, lightDir);
        Vec3 sky = level.getSkyColor(cam, partialTick);
        Vector3f skyLinear = new Vector3f((float) Math.pow(sky.x, 2.2), (float) Math.pow(sky.y, 2.2), (float) Math.pow(sky.z, 2.2));

        boolean underwater = camera.getFluidInCamera() == FogType.WATER;
        renderer.renderFrame(new FrameInput(cam.x, cam.y, cam.z, viewRotation, projection,
                lightDir, lightIntensity, skyLinear, rain, underwater));
        composite.draw(renderer);
        EntityLighting.INSTANCE.setActive(true);
    }

    /**
     * Uploads finished sections, then snapshots dirty sections (closest first, within the time
     * budget) for meshing on worker threads.
     */
    private void meshSections(ClientLevel level, Vec3 cam) {
        meshScheduler.drainTo(renderer, config.uploadsPerFrame);
        int capacity = Math.min(meshScheduler.capacity(), config.sectionsPerFrame);
        if (capacity == 0) return;
        long deadline = System.nanoTime() + (long) (config.meshBudgetMs * 1_000_000L);
        long[] batch = tracker.poll(capacity, SectionPos.blockToSectionCoord(cam.x),
                SectionPos.blockToSectionCoord(cam.y), SectionPos.blockToSectionCoord(cam.z));
        RenderRegionCache cache = new RenderRegionCache();
        for (int i = 0; i < batch.length; i++) {
            int sx = SectionPos.x(batch[i]), sy = SectionPos.y(batch[i]), sz = SectionPos.z(batch[i]);
            if (i > 0 && System.nanoTime() > deadline) {
                tracker.markDirty(sx, sy, sz); // over budget: retry next frame
                continue;
            }
            if (!meshScheduler.submit(level, cache, sx, sy, sz)) {
                meshScheduler.invalidate(sx, sy, sz);
                renderer.removeSection(sx, sy, sz); // empty section
            }
        }
    }

    /** Lines for the F3 debug screen. */
    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        if (config == null || !config.enabled) {
            lines.add("[Helios] disabled (F9)");
            return lines;
        }
        if (failure != null) {
            lines.add("[Helios] failed: " + failure);
            return lines;
        }
        HeliosRenderer r = renderer;
        if (r == null) {
            lines.add("[Helios] starting");
            return lines;
        }
        HeliosRenderer.GpuTimings t = r.gpuTimings();
        lines.add(String.format(Locale.ROOT, "[Helios] %s, %s %dx%d, %s sync",
                r.deviceName(), r.activeBackend(), r.renderWidth(), r.renderHeight(), r.usesGpuSync() ? "GPU" : "CPU"));
        lines.add(String.format(Locale.ROOT, "[Helios] GPU %.2f ms (trace %.2f, denoise %.2f, upscale %.2f)",
                t.total(), t.accelerationAndTrace(), t.denoise(), t.upscaleAndTonemap()));
        lines.add(String.format(Locale.ROOT, "[Helios] sections %d, pending %d, meshing %d, bounces %d",
                r.sectionCount(), tracker.pendingCount(), meshScheduler.inFlight(), config.renderer.maxBounces));
        return lines;
    }

    private void fail(Throwable t) {
        LOG.error("Helios ray tracing failed; falling back to vanilla rendering", t);
        failure = describe(t);
        frameActive = false;
        shutdown();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("helios.message.failed", failure), false);
        }
    }

    /** Message plus the innermost Helios frame, e.g. "Out of stack space. (VulkanContext.java:190)". */
    private static String describe(Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("dev.helios.")) {
                return message + " (" + e.getFileName() + ":" + e.getLineNumber() + ")";
            }
        }
        return message;
    }

    public void shutdown() {
        try {
            if (composite != null) composite.close();
            if (renderer != null) renderer.close();
        } catch (Throwable t) {
            LOG.warn("Error while shutting down Helios", t);
        } finally {
            composite = null;
            renderer = null;
        }
    }
}
