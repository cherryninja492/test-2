package dev.helios.fabric.mixin;

import dev.helios.fabric.AtlasAnimations;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks when the block atlas advances its animated textures (see {@link AtlasAnimations}). */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), require = 0)
    private void helios$beginAnimations(CallbackInfo ci) {
        AtlasAnimations.setBlockAtlasTicking(TextureAtlas.LOCATION_BLOCKS.equals(((TextureAtlas) (Object) this).location()));
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"), require = 0)
    private void helios$endAnimations(CallbackInfo ci) {
        AtlasAnimations.setBlockAtlasTicking(false);
    }
}
