package dev.helios.fabric;

import dev.helios.core.RendererSettings;
import dev.helios.core.math.UpscaleQuality;
import dev.helios.core.math.UpscalerBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** {@code config/helios.properties}. */
public final class HeliosConfig {
    private static final Logger LOG = LoggerFactory.getLogger("Helios");

    public boolean enabled = true;
    /** Chunk sections meshed and uploaded per frame. */
    /** Max chunk sections handed to the mesher threads per frame. */
    public int sectionsPerFrame = 128;
    /** Milliseconds per frame the render thread spends snapshotting sections for the mesher threads. */
    public float meshBudgetMs = 2.0f;
    /** Max meshed sections uploaded to the GPU per frame. */
    public int uploadsPerFrame = 192;
    /** Mobs and players cast (box-shaped) ray traced shadows. */
    public boolean entityShadows = true;
    /** Entities within this many blocks cast shadows (they are re-rendered each frame for it). */
    public int entityShadowRange = 40;
    public final RendererSettings renderer = new RendererSettings();

    private final Path file;

    private HeliosConfig(Path file) {
        this.file = file;
    }

    public static HeliosConfig load(Path file) {
        HeliosConfig config = new HeliosConfig(file);
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
                RendererSettings s = config.renderer;
                config.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
                config.sectionsPerFrame = parseInt(p, "sectionsPerFrame", config.sectionsPerFrame);
                config.uploadsPerFrame = parseInt(p, "uploadsPerFrame", config.uploadsPerFrame);
                config.meshBudgetMs = Float.parseFloat(p.getProperty("meshBudgetMs", String.valueOf(config.meshBudgetMs)));
                config.entityShadows = Boolean.parseBoolean(p.getProperty("entityShadows", String.valueOf(config.entityShadows)));
                config.entityShadowRange = parseInt(p, "entityShadowRange", config.entityShadowRange);
                s.upscaler = parseEnum(p, "upscaler", UpscalerBackend.class, s.upscaler);
                s.quality = parseEnum(p, "quality", UpscaleQuality.class, s.quality);
                s.maxBounces = parseInt(p, "maxBounces", s.maxBounces);
                s.samplesPerPixel = parseInt(p, "samplesPerPixel", s.samplesPerPixel);
                s.denoiser = Boolean.parseBoolean(p.getProperty("denoiser", String.valueOf(s.denoiser)));
                s.denoiserIterations = parseInt(p, "denoiserIterations", s.denoiserIterations);
                s.exposure = Float.parseFloat(p.getProperty("exposure", String.valueOf(s.exposure)));
                s.validation = Boolean.parseBoolean(p.getProperty("validation", "false"));
            } catch (IOException | IllegalArgumentException e) {
                LOG.warn("Invalid {}, using defaults: {}", file, e.toString());
            }
        }
        if (Boolean.getBoolean("helios.validation")) config.renderer.validation = true;
        config.save();
        return config;
    }

    public void save() {
        Properties p = new Properties();
        RendererSettings s = renderer;
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("sectionsPerFrame", String.valueOf(sectionsPerFrame));
        p.setProperty("meshBudgetMs", String.valueOf(meshBudgetMs));
        p.setProperty("uploadsPerFrame", String.valueOf(uploadsPerFrame));
        p.setProperty("entityShadows", String.valueOf(entityShadows));
        p.setProperty("entityShadowRange", String.valueOf(entityShadowRange));
        p.setProperty("upscaler", s.upscaler.name());
        p.setProperty("quality", s.quality.name());
        p.setProperty("maxBounces", String.valueOf(s.maxBounces));
        p.setProperty("samplesPerPixel", String.valueOf(s.samplesPerPixel));
        p.setProperty("denoiser", String.valueOf(s.denoiser));
        p.setProperty("denoiserIterations", String.valueOf(s.denoiserIterations));
        p.setProperty("exposure", String.valueOf(s.exposure));
        p.setProperty("validation", String.valueOf(s.validation));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                p.store(w, "Helios ray tracing. upscaler: DLSS|TAAU, quality: NATIVE|QUALITY|BALANCED|PERFORMANCE|ULTRA_PERFORMANCE");
            }
        } catch (IOException e) {
            LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }

    private static int parseInt(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static <E extends Enum<E>> E parseEnum(Properties p, String key, Class<E> type, E fallback) {
        return Enum.valueOf(type, p.getProperty(key, fallback.name()).trim().toUpperCase());
    }
}
