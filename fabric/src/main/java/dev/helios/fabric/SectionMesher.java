package dev.helios.fabric;

import dev.helios.core.geometry.Materials;
import dev.helios.core.geometry.SectionGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private final QuadCollector liquidCollector = new QuadCollector();

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
                    if (!fluid.isEmpty()) meshFluid(level, dispatcher, state, fluid);

                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = dispatcher.getBlockModel(state);
                    boolean cutout = ItemBlockRenderTypes.getChunkRenderType(state) != RenderType.solid()
                            || state.getBlock() instanceof LeavesBlock;
                    int material = Materials.withSurface(Materials.pack(state.getLightEmission(), cutout, false),
                            surfaceOf(state.getBlock()));
                    Vec3 offset = state.getOffset(level, pos);
                    float ox = x + (float) offset.x, oy = y + (float) offset.y, oz = z + (float) offset.z;
                    long seed = state.getSeed(pos);

                    for (Direction dir : DIRECTIONS) {
                        random.setSeed(seed);
                        List<BakedQuad> quads = model.getQuads(state, dir, random);
                        if (quads.isEmpty()) continue;
                        neighbor.setWithOffset(pos, dir);
                        if (!Block.shouldRenderFace(state, level, pos, dir, neighbor)) continue;
                        int light = Math.max(blockLight(level, neighbor), state.getLightEmission());
                        addQuads(quads, level, colors, state, Materials.withBlockLight(material, light), ox, oy, oz);
                    }
                    random.setSeed(seed);
                    int light = Math.max(blockLight(level, pos), state.getLightEmission());
                    addQuads(model.getQuads(state, null, random), level, colors, state,
                            Materials.withBlockLight(material, light), ox, oy, oz);
                }
            }
        }
        return geometry;
    }

    private static final Map<Block, Integer> SURFACES = new IdentityHashMap<>();

    /**
     * Which blocks get special optics, by registry name: glass and ice refract, metal blocks
     * reflect glossily, gems and polished stone get a clear coat.
     */
    static int surfaceOf(Block block) {
        return SURFACES.computeIfAbsent(block, b -> {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (id.contains("glass") || id.equals("ice") || id.equals("packed_ice") || id.equals("blue_ice")) {
                return Materials.SURFACE_GLASS;
            }
            if (id.contains("ore")) return Materials.SURFACE_DIFFUSE;
            if (id.equals("iron_block") || id.equals("gold_block") || id.equals("netherite_block")
                    || id.contains("copper") && !id.contains("oxidized") && !id.contains("weathered")
                    || id.contains("anvil") || id.equals("iron_bars") || id.equals("iron_door")
                    || id.equals("iron_trapdoor") || id.equals("chain") || id.equals("cauldron")
                    || id.equals("hopper") || id.equals("heavy_core")) {
                return Materials.SURFACE_METAL;
            }
            if (id.equals("diamond_block") || id.equals("emerald_block") || id.equals("lapis_block")
                    || id.contains("amethyst") || id.startsWith("quartz") || id.startsWith("smooth_quartz")
                    || id.startsWith("polished_") || id.equals("obsidian") || id.equals("crying_obsidian")
                    || id.startsWith("prismarine") || id.startsWith("dark_prismarine") || id.equals("sea_lantern")
                    || id.contains("glazed_terracotta") || id.equals("redstone_block")) {
                return Materials.SURFACE_POLISHED;
            }
            return Materials.SURFACE_DIFFUSE;
        });
    }

    /** Minecraft's block light level (0-15) at a position, as used by vanilla lighting. */
    private static int blockLight(Level level, BlockPos p) {
        return level.getBrightness(LightLayer.BLOCK, p);
    }

    private final float[] uvs = new float[8];

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

    /**
     * Fluids through Minecraft's own liquid renderer (sloped flowing surfaces, falling water,
     * correct face culling). It emits section-local positions, atlas UVs and tinted colours.
     */
    private void meshFluid(Level level, BlockRenderDispatcher dispatcher, BlockState state, FluidState fluid) {
        boolean water = fluid.is(FluidTags.WATER);
        int emission = fluid.createLegacyBlock().getLightEmission();
        int material = Materials.pack(emission, Math.max(blockLight(level, pos), emission), false, water);
        dispatcher.renderLiquid(pos, level, liquidCollector.begin((p, uv, rgb) -> geometry.addQuad(p, uv, rgb, material)),
                state, fluid);
        liquidCollector.finish();
    }
}
