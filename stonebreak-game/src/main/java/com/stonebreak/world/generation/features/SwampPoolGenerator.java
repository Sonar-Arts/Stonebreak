package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Floods patches of a swamp's surface into shallow pools, giving the biome its mix of
 * water and swampy grass.
 *
 * <p><b>Containment contract.</b> Worldgen water is a permanent source, and
 * {@code WaterSim.onChunkLoaded} wakes any water cell with air beside or below it. So a
 * leaky pool drains forever. A cell becomes water only when the block below it and all
 * four horizontal neighbours at its level are {@linkplain #isWatertight watertight}. Turning
 * a solid surface block into water never creates air, so one pass is enough. A neighbour
 * chunk populated later only turns more solid cells into water, so it keeps every pool
 * here sealed. Bumpy ground and carved pockets simply leave the offending cells dry.
 *
 * <p><b>Order independence.</b> Only the +X/+Z neighbours are guaranteed loaded when a chunk
 * is populated. A neighbour that is not loaded is answered from the deterministic terrain
 * pass ({@link ColumnOracle}) rather than read as air: read as air, every x=0 / z=0 pool
 * cell beside a not-yet-loaded chunk stayed dry for good, a grass line along the chunk
 * border through the middle of the water. Because pools only ever turn solid into water,
 * "solid in the terrain pass" is "watertight after pools", whichever chunk goes first.
 * Carved oracle columns read as air (conservative: may leave a cell dry, never leaks).
 *
 * <p>Runs before vegetation, so cypress trees can root in the pools.
 */
public class SwampPoolGenerator {
    /** Blob scale of the pool field, in blocks. */
    private static final int POOL_LATTICE = 8;
    /** Pool-field values above this flood the surface cell. */
    static final float POOL_THRESHOLD = 0.5f;
    /** Pool-field values above this also flood the cell beneath (2-deep pool cores). */
    static final float DEEP_THRESHOLD = 0.68f;
    private static final String POOL_FEATURE = "swamp_pool";

    /** Reads any block by world coordinate — including neighbour chunks. */
    @FunctionalInterface
    public interface BlockReader {
        BlockType get(int worldX, int y, int worldZ);
    }

    private final DeterministicRandom rng;

    public SwampPoolGenerator(DeterministicRandom rng) {
        this.rng = rng;
    }

    /** Terrain-pass column facts for chunks that are not loaded, all in world y. */
    public interface ColumnOracle {
        /** Height before carving: ground occupies y below it. */
        int rawHeight(int worldX, int worldZ);

        /** One past the highest solid block after carving. */
        int surfaceHeight(int worldX, int worldZ);

        /** First non-water y, or anything at or below the surface when dry. */
        int waterLevel(int worldX, int worldZ);

        /** This oracle with every column read as uncarved: no carve profile is ever built. */
        default ColumnOracle uncarved() {
            ColumnOracle carved = this;
            return new ColumnOracle() {
                @Override public int rawHeight(int x, int z) { return carved.rawHeight(x, z); }
                @Override public int surfaceHeight(int x, int z) { return carved.rawHeight(x, z); }
                @Override public int waterLevel(int x, int z) { return carved.waterLevel(x, z); }
            };
        }

        static ColumnOracle of(TerrainGenerator terrain) {
            return new ColumnOracle() {
                @Override public int rawHeight(int x, int z) { return terrain.getFinalTerrainHeightAt(x, z); }
                @Override public int surfaceHeight(int x, int z) { return terrain.carvedSurfaceHeight(x, z); }
                @Override public int waterLevel(int x, int z) { return terrain.getWaterLevelAt(x, z); }
            };
        }
    }

    public void generate(ChunkGenerationContext ctx, ColumnOracle columns) {
        generate(ctx, (wx, y, wz) -> {
            Chunk loaded = ctx.world == null ? null : ctx.world.getChunkIfLoaded(
                Math.floorDiv(wx, ChunkGenerationContext.SIZE), Math.floorDiv(wz, ChunkGenerationContext.SIZE));
            if (loaded == null) {
                return terrainPassBlock(columns, wx, y, wz);
            }
            return y < 0 || y >= WorldConfiguration.WORLD_HEIGHT ? BlockType.AIR
                : loaded.getBlock(Math.floorMod(wx, ChunkGenerationContext.SIZE), y,
                    Math.floorMod(wz, ChunkGenerationContext.SIZE));
        });
    }

    /** What the terrain pass places at (x, y, z), as far as watertightness goes. */
    static BlockType terrainPassBlock(ColumnOracle columns, int x, int y, int z) {
        int surface = columns.surfaceHeight(x, z);
        if (y < surface) {
            return surface == columns.rawHeight(x, z) ? BlockType.DIRT : BlockType.AIR;
        }
        return y < columns.waterLevel(x, z) ? BlockType.WATER : BlockType.AIR;
    }

    /** @param world reads cells outside this chunk */
    void generate(ChunkGenerationContext ctx, BlockReader world) {
        Chunk chunk = ctx.chunk;
        BlockReader reader = (wx, y, wz) -> {
            int lx = wx - ctx.worldX(0);
            int lz = wz - ctx.worldZ(0);
            boolean local = lx >= 0 && lx < ChunkGenerationContext.SIZE
                && lz >= 0 && lz < ChunkGenerationContext.SIZE;
            return local ? chunk.getBlock(lx, y, lz) : world.get(wx, y, wz);
        };
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                if (ctx.biome(x, z) != BiomeType.SWAMP) {
                    continue;
                }
                int top = ctx.height(x, z) - 1;
                if (top < 2 || chunk.getBlock(x, top, z) != BlockType.SWAMPY_GRASS) {
                    continue;
                }
                int depth = poolDepth(reader, ctx.worldX(x), top, ctx.worldZ(z), rng);
                if (depth == 2) {
                    chunk.setBlock(x, top - 1, z, BlockType.WATER);
                }
                if (depth >= 1) {
                    chunk.setBlock(x, top, z, BlockType.WATER);
                }
            }
        }
    }

    /**
     * The pool a sampled column grows, decided from the terrain pass alone, for renderers that
     * never generate the chunk (FastLOD). Agrees with {@link #generate} wherever the
     * neighbours still hold their generated blocks: pools only ever turn solid into water,
     * so the terrain-pass reading a neighbour gives is the watertightness it has after pools.
     *
     * @param surface      the block the column exposes at {@code carvedHeight - 1}
     * @param rawHeight    the column's height before carving (world y; ground below it)
     * @param carvedHeight one past its highest solid block
     * @param waterLevel   its first non-water y from the terrain pass
     * @return 0 dry, 1 the top block is water, 2 the block under it is too (a pool core)
     */
    public static int probePoolDepth(BiomeType biome, BlockType surface, int rawHeight, int carvedHeight,
                                     int waterLevel, int worldX, int worldZ, ColumnOracle columns,
                                     DeterministicRandom rng) {
        // generate()'s gate: a swamp column whose top block is still its dry swampy grass.
        if (biome != BiomeType.SWAMP || surface != BlockType.SWAMPY_GRASS || carvedHeight != rawHeight
                || waterLevel > rawHeight || rawHeight - 1 < 2) {
            return 0;
        }
        return poolDepth((x, y, z) -> terrainPassBlock(columns, x, y, z), worldX, rawHeight - 1, worldZ, rng);
    }

    /**
     * A surface cell floods when the pool field clears {@link #POOL_THRESHOLD} and it is
     * {@linkplain #sealed sealed}; the cell under it also floods, as a pool core, past
     * {@link #DEEP_THRESHOLD} when sealed too. Under a dry surface it stays dirt: roofed, it
     * would be a buried source, not a pool. Either cell turning to water leaves the other's
     * floor and sides exactly as watertight, so the two tests are independent.
     */
    private static int poolDepth(BlockReader reader, int wx, int top, int wz, DeterministicRandom rng) {
        float pool = ForestDensityField.sampleLattice(wx, wz, POOL_LATTICE, POOL_FEATURE, rng);
        if (pool <= POOL_THRESHOLD || !sealed(reader, wx, top, wz)) {
            return 0;
        }
        return pool > DEEP_THRESHOLD && sealed(reader, wx, top - 1, wz) ? 2 : 1;
    }

    /** True when water at (x, y, z) could never flow: watertight floor and four sides. */
    static boolean sealed(BlockReader reader, int x, int y, int z) {
        return isWatertight(reader.get(x, y - 1, z))
            && isWatertight(reader.get(x + 1, y, z))
            && isWatertight(reader.get(x - 1, y, z))
            && isWatertight(reader.get(x, y, z + 1))
            && isWatertight(reader.get(x, y, z - 1));
    }

    /** Water either cannot flow into this block, or the block already is water. */
    static boolean isWatertight(BlockType block) {
        return block == BlockType.WATER || !FlowBlockInteraction.canDisplace(block);
    }
}
