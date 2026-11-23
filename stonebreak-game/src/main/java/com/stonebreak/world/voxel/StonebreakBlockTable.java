package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.world.BlockFlags;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.world.lighting.BlockOpacity;

import java.util.ArrayList;
import java.util.List;

/**
 * What every Stonebreak block IS to the native voxel world: the {@code cv_block_table} flags
 * ({@link BlockFlags}), the water fluid ({@code cv_fluid_set}) and the two live neighbour face rules.
 * Applied to every {@link CubeWorld} a {@code World} creates ({@link #apply}); the render assets
 * ({@link CubeRenderAssets}) later refine which blocks are unit cubes and add faces/models on the client.
 *
 * <h2>Flags</h2>
 * <ul>
 *   <li>AIR 0; WATER 0 (a fluid: meshed from the WATER layer, never SKY_OPAQUE - the surface band must reach
 *       the seabed; it still counts for TOP through the occupancy mask).</li>
 *   <li>{@link BlockFlags#SKY_OPAQUE} = {@link BlockOpacity#isOpaque} (the old heightmap rule: leaves, glass,
 *       ice, flowers, snow, torches, doors, stalagmites and cacti pass sky light).</li>
 *   <li>Unit cubes: {@code CUBE}, then {@code TRANSPARENT} (+{@code TRANSLUCENT} for the translucent pass)
 *       when {@link BlockType#isTransparent()} (setting-aware: leaves with "Leaf Transparency" off are opaque
 *       cubes), else {@code OPAQUE | VIS_OPAQUE}.</li>
 *   <li>Everything else (stairs, flowers, saplings, snow layers, cactus, stalagmites, animated doors/torches)
 *       is {@code HOST_GEOMETRY} (+{@code OPAQUE} when it blocks sky light, for AO), meshed through the models
 *       {@link CubeRenderAssets} binds; animated blocks get no model (drawn by the animated-block renderer).</li>
 * </ul>
 * Which blocks are unit cubes is decided headlessly by {@link #isUnitCube} (shape predicates) and corrected
 * by the stamp cache on clients ({@link CubeRenderAssets#cubeOverride}); SKY_OPAQUE never depends on that,
 * so the surface tracker agrees between the server and its clients.
 */
public final class StonebreakBlockTable {

    private StonebreakBlockTable() {
    }

    /** Highest registered block id. */
    public static int maxId() {
        int max = 0;
        for (BlockType type : BlockType.values()) {
            max = Math.max(max, type.getId());
        }
        return max;
    }

    /** True when the block draws as a unit cube by the headless shape rules. */
    public static boolean isUnitCube(BlockType type) {
        if (type == null || type == BlockType.AIR || type == BlockType.WATER) {
            return false;
        }
        if (type.isFlower() || type.isStairs() || type == BlockType.SNOW || type == BlockType.CACTUS
                || type == BlockType.LIMESTONE_STALAGMITE || AnimatedBlockRegistry.isAnimatedType(type)
                || type.isAnimated() || type.name().endsWith("SAPLING")) {
            return false;
        }
        return true;
    }

    /** The cv flags of one block, with {@code unitCube} deciding the cube/host-geometry split. */
    public static int flagsOf(BlockType type, boolean unitCube) {
        return flagsOf(type, unitCube, false);
    }

    /**
     * The cv flags of one block; {@code detail} (an opaque unit cube that also binds its relief model, e.g. an
     * ore with nuggets) adds {@link BlockFlags#DETAIL}: the model near the camera, the cube faces beyond.
     */
    public static int flagsOf(BlockType type, boolean unitCube, boolean detail) {
        if (type == null || type == BlockType.AIR || type == BlockType.WATER) {
            return 0;
        }
        boolean skyOpaque = BlockOpacity.isOpaque(type);
        int flags = skyOpaque ? BlockFlags.SKY_OPAQUE : 0;
        if (unitCube) {
            flags |= BlockFlags.CUBE;
            if (type.isTransparent()) {
                flags |= BlockFlags.TRANSPARENT;
                if (type.isTranslucent()) {
                    flags |= BlockFlags.TRANSLUCENT;
                }
            } else {
                flags |= BlockFlags.OPAQUE | BlockFlags.VIS_OPAQUE;
                if (detail) {
                    flags |= BlockFlags.DETAIL;
                }
            }
        } else {
            flags |= BlockFlags.HOST_GEOMETRY;
            if (skyOpaque) {
                flags |= BlockFlags.OPAQUE;
            }
        }
        return flags;
    }

    /** The whole table, headless rules ({@code flags[id]}). */
    public static short[] flags() {
        return flags(null);
    }

    /**
     * The whole table; {@code cubeOverride} (indexed by id, may be null or shorter) replaces the headless
     * unit-cube decision where it holds a non-null value.
     */
    public static short[] flags(Boolean[] cubeOverride) {
        return flags(cubeOverride, null);
    }

    /**
     * The whole table with the stamp cache's verdicts: {@code cubeOverride} as above, {@code detail} (indexed by
     * id, may be null or shorter) marks the cube blocks that also bind a relief model ({@link BlockFlags#DETAIL}).
     */
    public static short[] flags(Boolean[] cubeOverride, boolean[] detail) {
        short[] out = new short[maxId() + 1];
        for (BlockType type : BlockType.values()) {
            int id = type.getId();
            boolean cube = cubeOverride != null && id < cubeOverride.length && cubeOverride[id] != null
                ? cubeOverride[id] : isUnitCube(type);
            boolean relief = detail != null && id < detail.length && detail[id];
            out[id] = (short) flagsOf(type, cube, relief);
        }
        return out;
    }

    /**
     * Configures a fresh world: layers, block table, the water fluid and the live face rules. Safe to call
     * again (e.g. after the leaf-transparency setting changed): every part is replaced.
     */
    public static void apply(CubeWorld world) {
        apply(world, null);
    }

    public static void apply(CubeWorld world, Boolean[] cubeOverride) {
        apply(world, cubeOverride, null);
    }

    /** {@link #apply(CubeWorld, Boolean[])} with the relief (DETAIL) blocks of {@link #flags(Boolean[], boolean[])}. */
    public static void apply(CubeWorld world, Boolean[] cubeOverride, boolean[] detail) {
        VoxelLayers.define(world);
        world.setBlockTable(flags(cubeOverride, detail));
        world.setFluid(VoxelLayers.WATER, BlockType.WATER.getId(), VoxelLayers.waterSurfaceByLevel());
        applyFaceRules(world);
    }

    /**
     * The two neighbour face rules Stonebreak draws today (the old stamp emitter's instance policies):
     * the top face of every translucent block is dropped under a snow layer, and a cactus top is dropped
     * under another cactus. (The "ice touching water drawn opaque" policy was never wired: not added.)
     */
    public static void applyFaceRules(CubeWorld world) {
        world.clearFaceRules(-1);
        int top = CubeWorld.faceBit(0);
        int snow = BlockType.SNOW.getId();
        for (int id : translucentIds()) {
            world.addFaceRule(id, -1, 0, top, CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, snow);
        }
        int cactus = BlockType.CACTUS.getId();
        world.addFaceRule(cactus, -1, 0, top, CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, cactus);
    }

    /** Ids of the blocks drawn in the translucent pass (ice, glass), water excluded. */
    public static List<Integer> translucentIds() {
        List<Integer> out = new ArrayList<>();
        for (BlockType type : BlockType.values()) {
            if (type != BlockType.WATER && type != BlockType.AIR && type.isTranslucent()) {
                out.add(type.getId());
            }
        }
        return out;
    }
}
