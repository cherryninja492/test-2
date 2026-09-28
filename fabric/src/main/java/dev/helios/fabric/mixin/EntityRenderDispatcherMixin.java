package dev.helios.fabric.mixin;

import dev.helios.fabric.EntityLighting;
import dev.helios.fabric.HeliosPipeline;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's round blob shadows are replaced by ray traced entity shadows while Helios renders, and
 * entity light levels follow ray traced sun visibility and water depth ({@link EntityLighting}).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private static void helios$skipBlobShadow(CallbackInfo ci) {
        if (HeliosPipeline.INSTANCE.isRenderingFrame() && HeliosPipeline.INSTANCE.config().entityShadows) {
            ci.cancel();
        }
    }

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true, require = 0)
    private void helios$rayTracedEntityLight(Entity entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(EntityLighting.INSTANCE.adjust(entity, cir.getReturnValue()));
    }
}
