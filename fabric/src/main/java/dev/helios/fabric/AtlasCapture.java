package dev.helios.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.helios.core.HeliosRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL11.*;

/** Copies the block atlas and Minecraft's mipmaps of it from OpenGL into the ray tracer. */
final class AtlasCapture {
    private AtlasCapture() {
    }

    static void upload(HeliosRenderer renderer) {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GlStateManager._bindTexture(texture.getId());
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
        int maxLevel = Math.max(0, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL));
        GlStateManager._pixelStore(GL_PACK_ALIGNMENT, 4);

        List<ByteBuffer> levels = new ArrayList<>();
        try {
            for (int level = 0; level <= Math.min(maxLevel, 8); level++) {
                int w = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH);
                int h = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
                if (w != Math.max(1, width >> level) || h != Math.max(1, height >> level)) break;
                ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
                glGetTexImage(GL_TEXTURE_2D, level, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                levels.add(pixels);
            }
            renderer.uploadAtlas(width, height, levels);
        } finally {
            levels.forEach(MemoryUtil::memFree);
        }
    }
}
