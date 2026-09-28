package dev.helios.fabric.mixin;

import dev.helios.fabric.HeliosPipeline;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's round blob shadows are replaced by ray traced entity shadows while Helios renders. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private static void helios$skipBlobShadow(CallbackInfo ci) {
        if (HeliosPipeline.INSTANCE.isRenderingFrame() && HeliosPipeline.INSTANCE.config().entityShadows) {
            ci.cancel();
        }
    }
}
