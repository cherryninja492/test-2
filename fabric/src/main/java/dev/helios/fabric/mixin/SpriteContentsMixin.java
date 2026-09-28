package dev.helios.fabric.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.helios.fabric.AtlasAnimations;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every animated sprite frame (and interpolated frame) is uploaded through this method. */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
    @Inject(method = "upload", at = @At("HEAD"), require = 0)
    private void helios$recordFrame(int x, int y, int frameX, int frameY, NativeImage[] images, CallbackInfo ci) {
        SpriteContents self = (SpriteContents) (Object) this;
        AtlasAnimations.onUpload(x, y, frameX, frameY, self.width(), self.height(), images);
    }
}
