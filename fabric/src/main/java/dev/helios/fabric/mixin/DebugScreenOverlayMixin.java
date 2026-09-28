package dev.helios.fabric.mixin;

import dev.helios.fabric.HeliosPipeline;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Adds Helios status and GPU timings to the left side of the F3 screen. */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"), require = 0)
    private void helios$addDebugInfo(CallbackInfoReturnable<List<String>> cir) {
        cir.getReturnValue().add("");
        cir.getReturnValue().addAll(HeliosPipeline.INSTANCE.debugLines());
    }
}
