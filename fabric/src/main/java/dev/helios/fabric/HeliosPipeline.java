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
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

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
    private final SectionMesher mesher = new SectionMesher();

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
     * Renders the ray traced frame and composites it into the main framebuffer in place of the
     * vanilla sky. Returns false if vanilla rendering should proceed instead.
     */
    public boolean renderInPlaceOfSky() {
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
        atlasDirty = true;
        rendererGeneration = -1;
    }

    private void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        tracker.adoptLevel(level);

        if (rendererGeneration != tracker.generation()) {
            renderer.clearSections();
            tracker.markAllDirty();
            rendererGeneration = tracker.generation();
        }
        if (atlasDirty) {
            AtlasCapture.upload(renderer);
            atlasDirty = false;
        }

        // The previous frame's composite must be done reading before Vulkan writes again.
        composite.waitForGl();

        Vec3 cam = camera.getPosition();
        for (long section : tracker.poll(config.sectionsPerFrame,
                SectionPos.blockToSectionCoord(cam.x), SectionPos.blockToSectionCoord(cam.y),
                SectionPos.blockToSectionCoord(cam.z))) {
            int sx = SectionPos.x(section), sy = SectionPos.y(section), sz = SectionPos.z(section);
            SectionGeometry geometry = mesher.mesh(level, sx, sy, sz);
            renderer.uploadSection(sx, sy, sz, geometry);
        }

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

        renderer.renderFrame(new FrameInput(cam.x, cam.y, cam.z, viewRotation, projection,
                lightDir, lightIntensity, skyLinear, rain));
        composite.draw();
    }

    private void fail(Throwable t) {
        LOG.error("Helios ray tracing failed; falling back to vanilla rendering", t);
        failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        frameActive = false;
        shutdown();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("helios.message.failed", failure), false);
        }
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
