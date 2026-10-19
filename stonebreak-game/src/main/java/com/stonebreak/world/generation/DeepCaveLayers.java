package com.stonebreak.world.generation;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.heightmap.CarveMaskKey;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Carries Standard Generation's caves down through the stone {@link StandardTerrain} lifts
 * the terrain onto, so they reach the bottom of the world as they do on main.
 *
 * <p>Main's cave carvers place everything by depth below the surface, which in a 256-tall
 * column is the whole column. Lifted, the same band ends ~{@link StandardTerrain#Y_OFFSET}
 * blocks above bedrock. Rather than retune the carvers (and the fused native kernel that
 * mirrors them), each deep layer runs the same worm, cavern and megacavern carvers again under
 * their own seed and lays the result {@link #LAYER_SPACING} blocks lower than the one above.
 * The spacing is less than a band is thick, so neighbouring layers overlap and their tunnels
 * meet. Surface-anchored entrances (ravines, sinkholes) and the overhang band stay with the
 * top layer — the ordinary generation pass — which is the only one that reaches daylight.
 */
final class DeepCaveLayers {

    /** World-Y drop from one layer to the next. */
    static final int LAYER_SPACING = 100;
    /** Enough layers for the lowest to reach the bottom of the world. */
    static final int LAYER_COUNT = (StandardTerrain.Y_OFFSET + LAYER_SPACING - 1) / LAYER_SPACING;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final long LAYER_SALT = 0x5DEEC0DE1A7E4L;

    private final HeightMapGenerator heights;
    private final Layer[] layers = new Layer[LAYER_COUNT];

    /** @param nativeWormCtx the layer's native worm-carver context, or 0 for the Java carver */
    private record Layer(int drop, PerlinWormCarver worms, CavernCarver caverns, MegaCavernCarver megaCaverns,
                         long nativeWormCtx) {}

    /** @param nativeWorms carve worms with the native kernel, as the top layer does when it can */
    DeepCaveLayers(long seed, NoiseRouter router, boolean nativeWorms) {
        this.heights = new DryHeights(router);
        for (int i = 0; i < LAYER_COUNT; i++) {
            long layerSeed = seed ^ (LAYER_SALT * (i + 1));
            PerlinWormCarver worms = new PerlinWormCarver(layerSeed, heights);
            CavernCarver caverns = new CavernCarver(layerSeed, heights);
            MegaCavernCarver megaCaverns = new MegaCavernCarver(layerSeed, heights);
            worms.setCavernCarver(caverns);
            worms.setMegaCavernCarver(megaCaverns);
            long ctx = nativeWorms ? TerrainGenerationSystem.createNativeCarverContext(this, layerSeed) : 0L;
            layers[i] = new Layer((i + 1) * LAYER_SPACING, worms, caverns, megaCaverns, ctx);
        }
    }

    /**
     * Opens the deep layers' caves in {@code storage}, a chunk already filled by the ordinary
     * pass. Only stone is carved, so ore-free rock, bedrock and whatever the top layer placed
     * above (water, surface blocks) are left alone.
     *
     * @param standardHeights the chunk's surface heights in the Standard frame
     */
    void carve(CcoBlockStorage storage, int chunkX, int chunkZ, int[] standardHeights) {
        int[] dry = new int[standardHeights.length];
        Arrays.fill(dry, WorldConfiguration.NO_WATER);
        for (Layer layer : layers) {
            BitSet cave = layer.nativeWormCtx() != 0L
                    ? TerrainGenerationSystem.nativeWormMask(layer.nativeWormCtx(), layer.worms(),
                        chunkX, chunkZ, standardHeights, dry)
                    : layer.worms().carveMaskForChunk(chunkX, chunkZ, standardHeights, dry);
            CavernCarver.Result caverns = layer.caverns().buildForChunk(chunkX, chunkZ, standardHeights, dry);
            MegaCavernCarver.Result mega = layer.megaCaverns().buildForChunk(chunkX, chunkZ, standardHeights, dry);
            cave.or(caverns.carveMask);
            cave.or(mega.carveMask);
            cave.andNot(caverns.formationMask);
            cave.andNot(mega.formationMask);

            int lift = StandardTerrain.Y_OFFSET - layer.drop();
            for (int bit = cave.nextSetBit(0); bit >= 0; bit = cave.nextSetBit(bit + 1)) {
                int y = CarveMaskKey.y(bit) + lift;
                if (y < 1) continue;
                int x = CarveMaskKey.x(bit);
                int z = CarveMaskKey.z(bit);
                // Stay a layer's depth under the real surface: deep caves never open to the sky.
                if (y >= standardHeights[x * CHUNK_SIZE + z] + lift) continue;
                if (storage.get(x, y, z) == BlockType.STONE) {
                    storage.set(x, y, z, BlockType.AIR);
                }
            }
        }
    }

    /**
     * The Standard height field with no water anywhere: a deep layer sits a hundred blocks or
     * more under any sea or shore, so the carvers' water guards would only thin it out.
     */
    private static final class DryHeights extends HeightMapGenerator {
        DryHeights(NoiseRouter router) {
            super(router);
        }

        @Override
        public int waterLevel(int x, int z) {
            return WorldConfiguration.NO_WATER;
        }

        @Override
        public void populateChunkHeights(int chunkX, int chunkZ, int[] out, int[] outWaterLevels) {
            populateChunkHeights(chunkX, chunkZ, out);
            if (outWaterLevels != null) {
                Arrays.fill(outWaterLevels, WorldConfiguration.NO_WATER);
            }
        }
    }
}
