package dev.helios.fabric;

import dev.helios.core.HeliosRenderer;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Makes vanilla-rendered entities react to ray traced lighting: each frame the ray tracer probes
 * nearby entities for sun visibility and water depth above them, and the sky light Minecraft uses
 * to light each entity is scaled accordingly (see {@code EntityRenderDispatcherMixin}).
 */
public final class EntityLighting {
    public static final EntityLighting INSTANCE = new EntityLighting();

    private final float[] positions = new float[HeliosRenderer.MAX_PROBES * 3];
    private final int[] submittedIds = new int[HeliosRenderer.MAX_PROBES];
    private int submittedCount;
    /** Entity id -> (visibility, water depth) packed as two floats. */
    private final Int2LongOpenHashMap results = new Int2LongOpenHashMap();
    private boolean active;

    private EntityLighting() {
    }

    /** Reads last frame's probe results and submits this frame's probes (render thread). */
    void update(HeliosRenderer renderer, ClientLevel level, Vec3 cam, float partialTick, double range) {
        results.clear();
        float[] probeResults = renderer.probeResults();
        int n = Math.min(renderer.probeResultCount(), submittedCount);
        for (int i = 0; i < n; i++) {
            long packed = (long) Float.floatToRawIntBits(probeResults[i * 2]) << 32
                    | (Float.floatToRawIntBits(probeResults[i * 2 + 1]) & 0xFFFFFFFFL);
            results.put(submittedIds[i], packed);
        }

        int count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (count == HeliosRenderer.MAX_PROBES) break;
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - cam.x;
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) + entity.getBbHeight() * 0.5 - cam.y;
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - cam.z;
            if (x * x + y * y + z * z > range * range) continue;
            positions[count * 3] = (float) x;
            positions[count * 3 + 1] = (float) y;
            positions[count * 3 + 2] = (float) z;
            submittedIds[count] = entity.getId();
            count++;
        }
        submittedCount = count;
        renderer.setProbes(positions, count, cam.x, cam.y, cam.z);
    }

    void setActive(boolean active) {
        this.active = active;
    }

    /** Scales the sky light of an entity's packed light by ray traced sun visibility and water depth. */
    public int adjust(Entity entity, int packedLight) {
        if (!active || !results.containsKey(entity.getId())) return packedLight;
        long packed = results.get(entity.getId());
        float visibility = Float.intBitsToFloat((int) (packed >>> 32));
        float waterDepth = Float.intBitsToFloat((int) packed);
        float factor = (0.4f + 0.6f * visibility) * (float) Math.exp(-0.12 * waterDepth);
        int sky = Math.round(LightTexture.sky(packedLight) * factor);
        return LightTexture.pack(LightTexture.block(packedLight), sky);
    }
}
