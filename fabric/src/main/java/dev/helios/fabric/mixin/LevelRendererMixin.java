package dev.helios.fabric.mixin;

import dev.helios.fabric.HeliosPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Helios into vanilla world rendering:
 * <ul>
 *   <li>{@code renderLevel} HEAD: capture camera matrices for the frame.</li>
 *   <li>{@code renderSky}: replaced by the ray traced frame (colour + depth), drawn right after
 *       vanilla clears the framebuffer, so everything vanilla draws afterwards (entities, block
 *       entities, particles, clouds, weather, hand) is depth tested against it.</li>
 *   <li>{@code renderSectionLayer}: vanilla terrain is skipped while Helios is active.</li>
 *   <li>{@code setSectionDirty}: block changes re-mesh the affected section.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void helios$beginLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                   GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                   Matrix4f projectionMatrix, CallbackInfo ci) {
        HeliosPipeline.INSTANCE.beginLevel(deltaTracker, camera, frustumMatrix, projectionMatrix);
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void helios$renderSky(CallbackInfo ci) {
        if (HeliosPipeline.INSTANCE.renderInPlaceOfSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void helios$skipTerrain(CallbackInfo ci) {
        if (HeliosPipeline.INSTANCE.isRenderingFrame()) {
            ci.cancel();
        }
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void helios$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread,
                                     CallbackInfo ci) {
        HeliosPipeline.INSTANCE.tracker.markDirty(sectionX, sectionY, sectionZ);
    }
}
