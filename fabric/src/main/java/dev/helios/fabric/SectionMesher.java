package dev.helios.fabric;

import dev.helios.core.geometry.Materials;
import dev.helios.core.geometry.SectionGeometry;
import dev.helios.core.lighting.LightGrid;
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
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts one chunk section into {@link SectionGeometry} and a list of point lights, using
 * Minecraft's baked block models and liquid renderer with vanilla face culling.
 *
 * <p>Runs on worker threads against a {@code RenderChunkRegion} snapshot, like vanilla chunk
 * meshing. One instance per thread.
 */
public final class SectionMesher {
    /** Output of meshing one section. */
    public record Result(SectionGeometry geometry, float[] lights, int lightCount) {
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    /** Ints per vertex in {@code DefaultVertexFormat.BLOCK}: pos(3) color(1) uv0(2) uv2(1) normal(1). */
    private static final int VERTEX_INTS = 8;

    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final QuadCollector liquidCollector = new QuadCollector();
    private SectionGeometry geometry;
    private float[] lights = new float[LightGrid.MAX_LIGHTS_PER_SECTION * LightGrid.INPUT_STRIDE];
    private int lightCount;
    private int emissiveSeen;

    public Result mesh(BlockAndTintGetter level, int sx, int sy, int sz) {
        geometry = new SectionGeometry();
        lightCount = 0;
        emissiveSeen = 0;
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockColors colors = mc.getBlockColors();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) meshFluid(level, dispatcher, state, fluid, x, y, z);

                    int emission = state.getLightEmission();
                    if (emission > 0 && fluid.isEmpty()) addLight(x, y, z, emission, state.getBlock());

                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = dispatcher.getBlockModel(state);
                    Block block = state.getBlock();
                    boolean cutout = ItemBlockRenderTypes.getChunkRenderType(state) != RenderType.solid()
                            || block instanceof LeavesBlock;
                    int material = Materials.withSurface(Materials.pack(emission, cutout, false), surfaceOf(block));
                    if (isFoliage(block)) material |= Materials.FOLIAGE;
                    Vec3 offset = state.getOffset(level, pos);
                    float ox = x + (float) offset.x, oy = y + (float) offset.y, oz = z + (float) offset.z;
                    long seed = state.getSeed(pos);

                    for (Direction dir : DIRECTIONS) {
                        random.setSeed(seed);
                        List<BakedQuad> quads = model.getQuads(state, dir, random);
                        if (quads.isEmpty()) continue;
                        neighbor.setWithOffset(pos, dir);
                        if (!Block.shouldRenderFace(state, level, pos, dir, neighbor)) continue;
                        int light = Math.max(blockLight(level, neighbor), emission);
                        addQuads(quads, level, colors, state, Materials.withBlockLight(material, light), ox, oy, oz);
                    }
                    random.setSeed(seed);
                    int light = Math.max(blockLight(level, pos), emission);
                    addQuads(model.getQuads(state, null, random), level, colors, state,
                            Materials.withBlockLight(material, light), ox, oy, oz);
                }
            }
        }
        return new Result(geometry, Arrays.copyOf(lights, lightCount * LightGrid.INPUT_STRIDE), lightCount);
    }

    /** Thin plants get two-sided lighting. */
    static boolean isFoliage(Block block) {
        return block instanceof BushBlock || block instanceof SugarCaneBlock || block instanceof VineBlock;
    }

    /**
     * Registers a point light. Sections with many light sources (lava lakes) keep an evenly spread
     * subset, via reservoir sampling.
     */
    private void addLight(int x, int y, int z, int emission, Block block) {
        emissiveSeen++;
        int slot;
        if (lightCount < LightGrid.MAX_LIGHTS_PER_SECTION) {
            slot = lightCount++;
        } else {
            slot = random.nextInt(emissiveSeen);
            if (slot >= LightGrid.MAX_LIGHTS_PER_SECTION) return;
        }
        float[] color = lightColorOf(block);
        int o = slot * LightGrid.INPUT_STRIDE;
        lights[o] = x + 0.5f;
        lights[o + 1] = y + 0.5f;
        lights[o + 2] = z + 0.5f;
        lights[o + 3] = emission / 15f;
        lights[o + 4] = color[0];
        lights[o + 5] = color[1];
        lights[o + 6] = color[2];
    }

    private static final Map<Block, float[]> LIGHT_COLORS = new ConcurrentHashMap<>();

    static float[] lightColorOf(Block block) {
        return LIGHT_COLORS.computeIfAbsent(block, b -> {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (id.contains("soul")) return new float[] {0.35f, 0.8f, 1.0f};
            if (id.contains("redstone")) return new float[] {1.0f, 0.18f, 0.08f};
            if (id.contains("sea_lantern") || id.contains("beacon") || id.contains("end_rod")
                    || id.contains("conduit") || id.contains("froglight") && id.contains("pearlescent")) {
                return new float[] {0.8f, 0.92f, 1.0f};
            }
            if (id.contains("amethyst") || id.contains("crying_obsidian") || id.contains("portal")) {
                return new float[] {0.7f, 0.4f, 1.0f};
            }
            if (id.contains("lava") || id.contains("magma") || id.contains("fire") || id.contains("campfire")) {
                return new float[] {1.0f, 0.45f, 0.15f};
            }
            if (id.contains("glowstone") || id.contains("shroomlight") || id.contains("lantern")) {
                return new float[] {1.0f, 0.78f, 0.45f};
            }
            return new float[] {1.0f, 0.72f, 0.42f}; // torches and warm light by default
        });
    }

    private static final Map<Block, Integer> SURFACES = new ConcurrentHashMap<>();

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
    private static int blockLight(BlockAndTintGetter level, BlockPos p) {
        return level.getBrightness(LightLayer.BLOCK, p);
    }

    private void addQuads(List<BakedQuad> quads, BlockAndTintGetter level, BlockColors colors, BlockState state,
                          int material, float ox, float oy, float oz) {
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
    private void meshFluid(BlockAndTintGetter level, BlockRenderDispatcher dispatcher, BlockState state, FluidState fluid,
                           int x, int y, int z) {
        boolean water = fluid.is(FluidTags.WATER);
        int emission = fluid.createLegacyBlock().getLightEmission();
        if (emission > 0) addLight(x, y, z, emission, fluid.createLegacyBlock().getBlock());
        int material = Materials.pack(emission, Math.max(blockLight(level, pos), emission), false, water)
                | (fluid.isSource() ? 0 : Materials.FLOWING);
        dispatcher.renderLiquid(pos, level, liquidCollector.begin((p, uv, rgb) -> geometry.addQuad(p, uv, rgb, material)),
                state, fluid);
        liquidCollector.finish();
    }
}
