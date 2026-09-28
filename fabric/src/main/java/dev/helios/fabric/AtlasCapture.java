package dev.helios.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.helios.core.HeliosRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/** Copies the block atlas (mip level 0) from OpenGL into the ray tracer. */
final class AtlasCapture {
    private AtlasCapture() {
    }

    static void upload(HeliosRenderer renderer) {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GlStateManager._bindTexture(texture.getId());
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GlStateManager._pixelStore(GL_PACK_ALIGNMENT, 4);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            renderer.uploadAtlas(width, height, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
