package dev.helios.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.helios.core.HeliosRenderer;
import dev.helios.core.math.UpscaleQuality;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;

public final class HeliosClient implements ClientModInitializer {
    private static final String CATEGORY = "key.categories.helios";

    @Override
    public void onInitializeClient() {
        HeliosConfig config = HeliosConfig.load(FabricLoader.getInstance().getConfigDir().resolve("helios.properties"));
        HeliosPipeline pipeline = HeliosPipeline.INSTANCE;
        pipeline.init(config);

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                pipeline.tracker.onChunkLoad(level, chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                pipeline.onChunkUnload(level, chunk.getPos().x, chunk.getPos().z));

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath("helios", "block_atlas");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                pipeline.markAtlasDirty();
            }
        });

        KeyMapping toggle = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.helios.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY));
        KeyMapping cycleQuality = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.helios.cycle_quality", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));

        KeyMapping cycleJitter = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.helios.cycle_dlss_jitter", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (cycleJitter.consumeClick()) {
                config.renderer.dlssJitterMode = (config.renderer.dlssJitterMode + 1) & 3;
                config.save();
                message(mc, Component.translatable("helios.message.dlss_jitter", config.renderer.dlssJitterMode));
            }
            while (toggle.consumeClick()) {
                pipeline.toggle();
                status(mc, pipeline);
            }
            while (cycleQuality.consumeClick()) {
                UpscaleQuality[] values = UpscaleQuality.values();
                config.renderer.quality = values[(config.renderer.quality.ordinal() + 1) % values.length];
                config.save();
                HeliosRenderer renderer = pipeline.renderer();
                String backend = renderer != null ? renderer.activeBackend().name() : config.renderer.upscaler.name();
                message(mc, Component.translatable("helios.message.quality", config.renderer.quality.name(), backend));
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> pipeline.shutdown());
    }

    private static void status(Minecraft mc, HeliosPipeline pipeline) {
        HeliosRenderer renderer = pipeline.renderer();
        if (!pipeline.config().enabled) {
            message(mc, Component.translatable("helios.message.disabled"));
        } else {
            String device = renderer != null ? renderer.deviceName() : "starting";
            String backend = renderer != null ? renderer.activeBackend().name() : pipeline.config().renderer.upscaler.name();
            message(mc, Component.translatable("helios.message.enabled", device, backend));
        }
    }

    private static void message(Minecraft mc, Component text) {
        if (mc.player != null) mc.player.displayClientMessage(text, true);
    }
}
