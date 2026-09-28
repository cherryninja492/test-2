package dev.helios.fabric;

import dev.helios.core.geometry.Materials;
import dev.helios.core.geometry.SectionGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Converts one chunk section into {@link SectionGeometry} using Minecraft's baked block models,
 * with the same face culling as vanilla chunk meshing. Runs on the render thread.
 */
public final class SectionMesher {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Ints per vertex in {@code DefaultVertexFormat.BLOCK}: pos(3) color(1) uv0(2) uv2(1) normal(1). */
    private static final int VERTEX_INTS = 8;

    private final SectionGeometry geometry = new SectionGeometry();
    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];

    /** @return the geometry (reused between calls; consume before the next call). Empty if nothing to draw. */
    public SectionGeometry mesh(Level level, int sx, int sy, int sz) {
        geometry.clear();
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        int index = level.getSectionIndexFromSectionY(sy);
        if (chunk == null || index < 0 || index >= chunk.getSections().length) return geometry;
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) return geometry;

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockColors colors = mc.getBlockColors();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    pos.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);

                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) meshFluid(level, dispatcher, state, fluid, x, y, z);

                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = dispatcher.getBlockModel(state);
                    boolean cutout = ItemBlockRenderTypes.getChunkRenderType(state) != RenderType.solid()
                            || state.getBlock() instanceof LeavesBlock;
                    int material = Materials.pack(state.getLightEmission(), cutout, false);
                    Vec3 offset = state.getOffset(level, pos);
                    float ox = x + (float) offset.x, oy = y + (float) offset.y, oz = z + (float) offset.z;
                    long seed = state.getSeed(pos);

                    for (Direction dir : DIRECTIONS) {
                        random.setSeed(seed);
                        List<BakedQuad> quads = model.getQuads(state, dir, random);
                        if (quads.isEmpty()) continue;
                        neighbor.setWithOffset(pos, dir);
                        if (!Block.shouldRenderFace(state, level, pos, dir, neighbor)) continue;
                        addQuads(quads, level, colors, state, material, ox, oy, oz);
                    }
                    random.setSeed(seed);
                    addQuads(model.getQuads(state, null, random), level, colors, state, material, ox, oy, oz);
                }
            }
        }
        return geometry;
    }

    private void addQuads(List<BakedQuad> quads, Level level, BlockColors colors, BlockState state, int material,
                          float ox, float oy, float oz) {
        for (BakedQuad quad : quads) {
            int[] v = quad.getVertices();
            for (int i = 0; i < 4; i++) {
                int o = i * VERTEX_INTS;
                positions[i * 3] = Float.intBitsToFloat(v[o]) + ox;
                positions[i * 3 + 1] = Float.intBitsToFloat(v[o + 1]) + oy;
                positions[i * 3 + 2] = Float.intBitsToFloat(v[o + 2]) + oz;
                uvs[i * 2] = Float.intBitsToFloat(v[o + 4]);
                uvs[i * 2 + 1] = Float.intBitsToFloat(v[o + 5]);
            }
            int tint = quad.isTinted() ? colors.getColor(state, level, pos, quad.getTintIndex()) : 0xFFFFFF;
            geometry.addQuad(positions, uvs, tint, material);
        }
    }

    /** Simplified fluid surfaces: a top face at the fluid height plus side faces towards air. */
    private void meshFluid(Level level, BlockRenderDispatcher dispatcher, BlockState state, FluidState fluid,
                           int x, int y, int z) {
        boolean water = fluid.is(FluidTags.WATER);
        TextureAtlasSprite sprite = dispatcher.getBlockModelShaper().getParticleIcon(fluid.createLegacyBlock());
        int tint = water ? BiomeColors.getAverageWaterColor(level, pos) : 0xFFFFFF;
        int material = Materials.pack(fluid.createLegacyBlock().getLightEmission(), false, water);
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();

        neighbor.setWithOffset(pos, Direction.UP);
        boolean fluidAbove = level.getFluidState(neighbor).getType().isSame(fluid.getType());
        float h = fluidAbove ? 1f : fluid.getHeight(level, pos);
        if (!fluidAbove) {
            quad(x, y + h, z, x, y + h, z + 1, x + 1, y + h, z + 1, x + 1, y + h, z, u0, v0, u1, v1, tint, material);
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            neighbor.setWithOffset(pos, dir);
            if (!level.getBlockState(neighbor).isAir()) continue;
            float x0 = x + Math.max(0, dir.getStepX()), z0 = z + Math.max(0, dir.getStepZ());
            float x1 = dir.getStepZ() != 0 ? x0 + 1 : x0, z1 = dir.getStepX() != 0 ? z0 + 1 : z0;
            quad(x0, y, z0, x0, y + h, z0, x1, y + h, z1, x1, y, z1, u0, v0, u1, v0 + (v1 - v0) * h, tint, material);
        }
    }

    private void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                      float dx, float dy, float dz, float u0, float v0, float u1, float v1, int tint, int material) {
        positions[0] = ax; positions[1] = ay; positions[2] = az;
        positions[3] = bx; positions[4] = by; positions[5] = bz;
        positions[6] = cx; positions[7] = cy; positions[8] = cz;
        positions[9] = dx; positions[10] = dy; positions[11] = dz;
        uvs[0] = u0; uvs[1] = v0;
        uvs[2] = u0; uvs[3] = v1;
        uvs[4] = u1; uvs[5] = v1;
        uvs[6] = u1; uvs[7] = v0;
        geometry.addQuad(positions, uvs, tint, material);
    }
}
