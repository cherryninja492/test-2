package dev.helios.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.helios.core.HeliosRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated block textures (water, lava, fire, portals...). Minecraft uploads new frames into the
 * GL block atlas every tick; {@code SpriteContentsMixin} records the same pixels from the CPU-side
 * images here, and they are forwarded to the ray tracer's copy of the atlas each frame.
 */
public final class AtlasAnimations {
    private record Region(int level, int x, int y, int width, int height, int[] rgba) {
    }

    private static final List<Region> PENDING = new ArrayList<>();
    private static boolean blockAtlasTicking;

    private AtlasAnimations() {
    }

    /** Set by {@code TextureAtlasMixin} while the block atlas advances its animations. */
    public static void setBlockAtlasTicking(boolean ticking) {
        blockAtlasTicking = ticking;
    }

    /**
     * Called for every animation frame upload of a sprite at atlas position (x, y).
     *
     * @param frameX, frameY position of the frame inside the source images
     * @param images         the source mip chain
     */
    public static void onUpload(int x, int y, int frameX, int frameY, int width, int height, NativeImage[] images) {
        if (!blockAtlasTicking || !HeliosPipeline.INSTANCE.isEnabled()) return;
        for (int level = 0; level < images.length; level++) {
            int w = Math.max(1, width >> level);
            int h = Math.max(1, height >> level);
            int fx = frameX >> level, fy = frameY >> level;
            NativeImage image = images[level];
            if (fx + w > image.getWidth() || fy + h > image.getHeight()) break;
            int[] pixels = new int[w * h];
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    pixels[py * w + px] = image.getPixelRGBA(fx + px, fy + py);
                }
            }
            PENDING.add(new Region(level, x >> level, y >> level, w, h, pixels));
        }
        // Bound memory if Helios is not rendering (menus): only the latest frames matter.
        if (PENDING.size() > 4096) PENDING.subList(0, PENDING.size() - 2048).clear();
    }

    static void drainTo(HeliosRenderer renderer) {
        for (Region r : PENDING) renderer.updateAtlasRegion(r.level, r.x, r.y, r.width, r.height, r.rgba);
        PENDING.clear();
    }

    static void clear() {
        PENDING.clear();
    }
}
