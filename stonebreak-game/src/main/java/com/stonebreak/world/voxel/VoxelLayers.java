package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.world.chunk.ChunkWaterLayer;

/**
 * Stonebreak's per-cell game layers on the native {@link CubeWorld} ({@code cv_layer_*}): which host layer
 * holds what, and the WATER layer's raw encoding. One source of truth for the world, the native generator
 * config ({@code NativeCubeGenerator.Config.waterLayer/stateLayer/snowLayer}), saves and the network.
 *
 * <h2>Layers</h2>
 * <ul>
 *   <li>{@link #WATER} - water flow state of WATER cells, in the engine's {@code cv_fluid} encoding (so the
 *       native fluid mesher reads it directly): {@code level(0..7) | falling << 3 | source << 4 |
 *       (river flow octant + 1) << 5}; 0 = source. The game's own vocabulary is {@link ChunkWaterLayer}
 *       (0 source, 1..7 flowing, 8 falling, 9..16 river + octant); {@link #waterToGame} /
 *       {@link #waterToRaw} convert. Worldgen writes 0 for sources (config {@code waterSourceValue = 0})
 *       so an ocean cube's layer stays empty.</li>
 *   <li>{@link #STATE} - block-state id of the cell (its mesh-variant key, {@link BlockStateRegistry});
 *       0 = no state. Payload-carrying state strings (furnace inventories, crafting grids) keep their full
 *       text in the cube's game payload ({@code Cube}), the layer only holds the variant id.</li>
 *   <li>{@link #SNOW} - snow layer count 1..8 of SNOW cells; 0 = untracked (reads as 1).</li>
 * </ul>
 * All three are {@code SAVED | NETWORK | MESHED}: they are part of the cube record, travel with the cube
 * and change geometry.
 */
public final class VoxelLayers {

    public static final int WATER = 0;
    public static final int STATE = 1;
    public static final int SNOW = 2;

    /** Flags every Stonebreak layer is defined with. */
    public static final int FLAGS = CubeWorld.LAYER_SAVED | CubeWorld.LAYER_NETWORK | CubeWorld.LAYER_MESHED;

    // ---- cv_fluid bits --------------------------------------------------------------------------------------
    public static final int RAW_LEVEL_MASK = 0x7;
    public static final int RAW_FALLING = 1 << 3;
    public static final int RAW_SOURCE = 1 << 4;
    public static final int RAW_FLOW_SHIFT = 5;
    public static final int RAW_FLOW_MASK = 0xF << RAW_FLOW_SHIFT;

    private VoxelLayers() {
    }

    /** Defines the Stonebreak layers on a world. */
    public static void define(CubeWorld world) {
        world.defineLayer(WATER, FLAGS);
        world.defineLayer(STATE, FLAGS);
        world.defineLayer(SNOW, FLAGS);
    }

    /**
     * Raw WATER layer value -> the game's {@link ChunkWaterLayer} vocabulary (0 source, 1..7 flowing,
     * {@link ChunkWaterLayer#FALLING}, {@link ChunkWaterLayer#RIVER} + octant).
     */
    public static int waterToGame(int raw) {
        if (raw <= 0) {
            return ChunkWaterLayer.SOURCE;
        }
        int flow = (raw & RAW_FLOW_MASK) >>> RAW_FLOW_SHIFT;
        if (flow > 0) {
            return ChunkWaterLayer.river(flow - 1);
        }
        if ((raw & RAW_FALLING) != 0) {
            return ChunkWaterLayer.FALLING;
        }
        return raw & RAW_LEVEL_MASK;
    }

    /** The game's water value -> raw WATER layer value (inverse of {@link #waterToGame}). */
    public static int waterToRaw(int game) {
        if (game <= ChunkWaterLayer.SOURCE) {
            return 0;
        }
        if (game > ChunkWaterLayer.MAX_VALUE) {
            throw new IllegalArgumentException("water value " + game);
        }
        if (ChunkWaterLayer.isRiver(game)) {
            return (ChunkWaterLayer.octant(game) + 1) << RAW_FLOW_SHIFT;
        }
        if (game == ChunkWaterLayer.FALLING) {
            return RAW_FALLING;
        }
        if (game > ChunkWaterLayer.MAX_FLOW_LEVEL) {
            throw new IllegalArgumentException("water value " + game);
        }
        return game;
    }

    /** {@code cv_fluid_desc.surface_by_level}: the surface height in the cell per level 0..7. */
    public static float[] waterSurfaceByLevel() {
        float[] out = new float[8];
        for (int level = 0; level < 8; level++) {
            out[level] = WaterHeightUtil.surfaceFraction(level);
        }
        return out;
    }

    /** River-flow layer values per octant for the native generator ({@code waterRiverValues}). */
    public static short[] riverRawValues() {
        short[] out = new short[8];
        for (int octant = 0; octant < 8; octant++) {
            out[octant] = (short) waterToRaw(ChunkWaterLayer.river(octant));
        }
        return out;
    }
}
