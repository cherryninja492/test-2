package dev.helios.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.helios.core.HeliosRenderer;
import dev.helios.core.rt.SceneAccel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders nearby entities (mobs, players including yourself, items, minecarts...) through
 * Minecraft's own entity renderers into a {@link QuadCollector}, and hands the resulting animated
 * model geometry to the ray tracer as shadow-only casters.
 */
final class EntityShadowCapture {
    private static final Logger LOG = LoggerFactory.getLogger("Helios");

    private final float[] vertices = new float[SceneAccel.MAX_SHADOW_QUADS * 12];
    private int quads;
    private final QuadCollector collector = new QuadCollector();
    private final QuadCollector.Sink sink = (positions, uvs, rgb) -> {
        if (quads < SceneAccel.MAX_SHADOW_QUADS) {
            System.arraycopy(positions, 0, vertices, quads * 12, 12);
            quads++;
        }
    };
    private final MultiBufferSource buffers = this::bufferFor;
    private boolean warned;

    void capture(HeliosRenderer renderer, ClientLevel level, Vec3 cam, float partialTick, double range) {
        quads = 0;
        collector.begin(sink);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        PoseStack poseStack = new PoseStack();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isInvisible() || quads >= SceneAccel.MAX_SHADOW_QUADS) continue;
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - cam.x;
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - cam.y;
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - cam.z;
            if (x * x + y * y + z * z > range * range) continue;
            float yRot = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            try {
                dispatcher.render(entity, x, y, z, yRot, partialTick, poseStack, buffers, LightTexture.FULL_BRIGHT);
                collector.finish();
            } catch (RuntimeException e) {
                collector.begin(sink); // drop a partially emitted quad
                if (!warned) {
                    warned = true;
                    LOG.warn("Helios could not capture the shadow of {}; skipping entities that fail", entity, e);
                }
            }
        }
        renderer.setShadowGeometry(vertices, quads, cam.x, cam.y, cam.z);
    }

    /** Only solid model geometry casts shadows: not name tags, blob shadows, outlines, glint or beams. */
    private VertexConsumer bufferFor(RenderType type) {
        if (type.mode() != VertexFormat.Mode.QUADS) return QuadCollector.DISCARD;
        String name = nameOf(type);
        if (name.startsWith("text") || name.contains("shadow") || name.contains("glint") || name.contains("lines")
                || name.contains("beam") || name.contains("lightning") || name.contains("outline")) {
            return QuadCollector.DISCARD;
        }
        collector.finish(); // a new buffer never continues the previous one's quad
        return collector;
    }

    /**
     * The render type's own name. {@code toString()} of composite types is
     * "RenderType[name:CompositeState[...]]", and the state part lists shards such as
     * "affects_outline", so only the name must be matched.
     */
    static String nameOf(RenderType type) {
        String s = type.toString();
        int open = s.indexOf('[');
        int colon = s.indexOf(':');
        return s.startsWith("RenderType[") && colon > open ? s.substring(open + 1, colon) : s;
    }
}
