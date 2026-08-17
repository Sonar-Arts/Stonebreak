package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coal and iron must generate as veins, and iron must follow its depth curve.
 *
 * <p>The property that matters is <b>clumping</b>, and it is the one a probability tweak
 * cannot fake: the generator this replaced rolled every stone block independently, so its
 * mean connected-component size was ~1.0 by construction no matter how the rates were
 * tuned. Component size is therefore the floor that actually pins the behaviour, and the
 * per-chunk budgets are ratcheted alongside it so clumping cannot be bought by simply
 * generating more ore.
 *
 * <p>The third property is <b>seamlessness</b>. Veins are spawned per source chunk and
 * scanned in from neighbours ({@code OreGenerator.SCAN_RADIUS}) rather than clipped to the
 * chunk being populated, so a healthy share of veins must straddle a chunk border. If that
 * share collapses, the scan radius or its bounding-box reject has regressed and veins are
 * being sliced flat at chunk walls — a defect that is invisible in totals and in mean
 * component size, but glaring in game.
 */
public class OreVeinDistributionTest {

    private static final long SEED = 987654321L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int REGION = 6;

    /**
     * Budget floors and ceilings, blocks per chunk. Targets are ~500 coal and ~130 iron;
     * the band is wide because yield depends on how much of each vein lands in cave air.
     */
    private static final double COAL_PER_CHUNK_MIN = 325;
    private static final double COAL_PER_CHUNK_MAX = 675;
    private static final double IRON_PER_CHUNK_MIN = 85;
    private static final double IRON_PER_CHUNK_MAX = 175;

    /** Mean connected-component size. The old per-block generator scored 1.0. */
    private static final double COAL_MEAN_COMPONENT_MIN = 6.0;
    private static final double IRON_MEAN_COMPONENT_MIN = 3.0;

    /**
     * Share of veins that must cross a chunk border rather than stop at it, per ore.
     *
     * <p>These are low on purpose. The rate is set by geometry — a vein only crosses a
     * border if it happens to be placed within its own width of one, so a ~2-block-wide
     * iron vein crosses a 16-block border an order of magnitude less often than the
     * measured coal figure. What the floors detect is the defect, whose signature is
     * unambiguous: veins clipped to their source chunk straddle exactly 0% of the time.
     * Measured at the calibrated sizes: coal 20%, iron 9%.
     */
    private static final double COAL_STRADDLE_MIN = 0.12;
    private static final double IRON_STRADDLE_MIN = 0.05;

    /** Iron density in the deep and high bands, relative to the sea-level trough. */
    private static final double IRON_BAND_RATIO_MIN = 2.0;

    private static final int DEEP_Y_MAX = 100;
    private static final int MID_Y_MIN = 280;
    private static final int MID_Y_MAX = 360;
    private static final int HIGH_Y_MIN = 430;

    @Test
    public void oresGenerateAsVeinsAndIronFollowsTheDepthCurve() {
        TallHillsTileSource tiles = new TallHillsTileSource();
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, tiles);
        OreGenerator ores = new OreGenerator(
                new DeterministicRandom(SEED), new HeightMapGenerator(tiles), SEED);

        int span = REGION * CHUNK;
        int yCap = TallHillsTileSource.MAX_HEIGHT + 1;
        // 0 = neither, 1 = coal, 2 = iron. Flat array over the whole region so veins can
        // be flood-filled across chunk borders.
        byte[] ore = new byte[span * span * yCap];

        long coalBlocks = 0;
        long ironBlocks = 0;
        long[] ironByBand = new long[3];
        long[] rockByBand = new long[3];

        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                TerrainGenerationSystem.TerrainResult result = terrain.generateTerrainOnly(cx, cz);
                Chunk chunk = result.chunk();
                ColumnProfile profile = result.profile();
                ores.generate(new ChunkGenerationContext(null, chunk, null,
                        profile.heights(), profile.biomes(), profile.waterLevels(),
                        profile.dominantBiome()));

                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int top = Math.min(profile.heights()[lx * CHUNK + lz], yCap);
                        for (int y = 1; y < top; y++) {
                            BlockType block = chunk.getBlock(lx, y, lz);
                            boolean isCoal = block == BlockType.COAL_ORE;
                            boolean isIron = block == BlockType.IRON_ORE;
                            if (!isCoal && !isIron && block != BlockType.STONE) {
                                continue;
                            }
                            // Denominator for the depth curve: rock that iron could have
                            // occupied, so the bands are comparable despite holding very
                            // different amounts of stone.
                            int band = bandOf(y);
                            if (band >= 0) {
                                rockByBand[band]++;
                                if (isIron) {
                                    ironByBand[band]++;
                                }
                            }
                            if (!isCoal && !isIron) {
                                continue;
                            }
                            if (isCoal) {
                                coalBlocks++;
                            } else {
                                ironBlocks++;
                            }
                            int x = cx * CHUNK + lx;
                            int z = cz * CHUNK + lz;
                            ore[index(x, y, z, span, yCap)] = (byte) (isCoal ? 1 : 2);
                        }
                    }
                }
            }
        }

        int chunks = REGION * REGION;
        double coalPerChunk = coalBlocks / (double) chunks;
        double ironPerChunk = ironBlocks / (double) chunks;

        Components coal = components(ore, (byte) 1, span, yCap);
        Components iron = components(ore, (byte) 2, span, yCap);

        double deepDensity = ironByBand[0] / (double) Math.max(1, rockByBand[0]);
        double midDensity = ironByBand[1] / (double) Math.max(1, rockByBand[1]);
        double highDensity = ironByBand[2] / (double) Math.max(1, rockByBand[2]);

        System.out.printf("coal: %.1f blocks/chunk, %d veins, mean size %.2f, %.0f%% straddling%n",
                coalPerChunk, coal.count, coal.meanSize(), coal.straddleFraction() * 100);
        System.out.printf("iron: %.1f blocks/chunk, %d veins, mean size %.2f, %.0f%% straddling%n",
                ironPerChunk, iron.count, iron.meanSize(), iron.straddleFraction() * 100);
        System.out.printf("iron density  deep(y<%d) %.5f  mid(%d-%d) %.5f  high(y>%d) %.5f%n",
                DEEP_Y_MAX, deepDensity, MID_Y_MIN, MID_Y_MAX, midDensity,
                HIGH_Y_MIN - 1, highDensity);

        assertTrue(coal.meanSize() >= COAL_MEAN_COMPONENT_MIN,
                "coal must generate in clumps, not single blocks: mean component size "
                        + coal.meanSize() + " < " + COAL_MEAN_COMPONENT_MIN);
        assertTrue(iron.meanSize() >= IRON_MEAN_COMPONENT_MIN,
                "iron must generate in clumps, not single blocks: mean component size "
                        + iron.meanSize() + " < " + IRON_MEAN_COMPONENT_MIN);

        assertTrue(coalPerChunk >= COAL_PER_CHUNK_MIN && coalPerChunk <= COAL_PER_CHUNK_MAX,
                "coal budget out of band: " + coalPerChunk + " blocks/chunk");
        assertTrue(ironPerChunk >= IRON_PER_CHUNK_MIN && ironPerChunk <= IRON_PER_CHUNK_MAX,
                "iron budget out of band: " + ironPerChunk + " blocks/chunk");

        assertTrue(coal.straddleFraction() >= COAL_STRADDLE_MIN,
                "coal veins are being clipped at chunk borders: only "
                        + (coal.straddleFraction() * 100) + "% cross one");
        assertTrue(iron.straddleFraction() >= IRON_STRADDLE_MIN,
                "iron veins are being clipped at chunk borders: only "
                        + (iron.straddleFraction() * 100) + "% cross one");

        assertTrue(rockByBand[0] > 0 && rockByBand[1] > 0 && rockByBand[2] > 0,
                "fixture must supply rock in all three bands to test the curve");
        assertTrue(deepDensity >= midDensity * IRON_BAND_RATIO_MIN,
                "iron must be far denser deep than at sea level: " + deepDensity
                        + " vs " + midDensity);
        assertTrue(highDensity >= midDensity * IRON_BAND_RATIO_MIN,
                "iron must be far denser in the highlands than at sea level: " + highDensity
                        + " vs " + midDensity);
    }

    /** Band index for the depth-curve comparison, or -1 for the transition zones. */
    private static int bandOf(int y) {
        if (y < DEEP_Y_MAX) return 0;
        if (y >= MID_Y_MIN && y < MID_Y_MAX) return 1;
        if (y >= HIGH_Y_MIN) return 2;
        return -1;
    }

    private static int index(int x, int y, int z, int span, int yCap) {
        return (x * span + z) * yCap + y;
    }

    private record Components(int count, long blocks, int interior, int straddling) {
        double meanSize() {
            return count == 0 ? 0 : blocks / (double) count;
        }

        /** Of the veins that could have crossed a border, the share that did. */
        double straddleFraction() {
            return interior == 0 ? 0 : straddling / (double) interior;
        }
    }

    /**
     * Flood-fills 6-connected components of {@code kind} over the whole region, counting
     * how many span more than one chunk. Components touching the region's outer wall are
     * excluded from the straddle count entirely — they are genuinely clipped, since no
     * chunk was generated beyond it, and would otherwise be scored as failures.
     */
    private static Components components(byte[] ore, byte kind, int span, int yCap) {
        boolean[] seen = new boolean[ore.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int count = 0;
        long blocks = 0;
        int interior = 0;
        int straddling = 0;

        for (int x = 0; x < span; x++) {
            for (int z = 0; z < span; z++) {
                for (int y = 1; y < yCap; y++) {
                    int start = index(x, y, z, span, yCap);
                    if (ore[start] != kind || seen[start]) {
                        continue;
                    }
                    seen[start] = true;
                    queue.add(start);
                    long size = 0;
                    Set<Long> chunks = new HashSet<>();
                    boolean atRegionEdge = false;

                    while (!queue.isEmpty()) {
                        int cur = queue.poll();
                        int cy = cur % yCap;
                        int plane = cur / yCap;
                        int cz = plane % span;
                        int cxx = plane / span;
                        size++;
                        chunks.add(((long) (cxx / CHUNK) << 32) | (cz / CHUNK));
                        if (cxx == 0 || cxx == span - 1 || cz == 0 || cz == span - 1) {
                            atRegionEdge = true;
                        }
                        for (int[] d : NEIGHBOURS) {
                            int nx = cxx + d[0];
                            int ny = cy + d[1];
                            int nz = cz + d[2];
                            if (nx < 0 || nx >= span || nz < 0 || nz >= span
                                    || ny < 1 || ny >= yCap) {
                                continue;
                            }
                            int ni = index(nx, ny, nz, span, yCap);
                            if (ore[ni] == kind && !seen[ni]) {
                                seen[ni] = true;
                                queue.add(ni);
                            }
                        }
                    }

                    count++;
                    blocks += size;
                    if (!atRegionEdge) {
                        interior++;
                        if (chunks.size() > 1) {
                            straddling++;
                        }
                    }
                }
            }
        }
        return new Components(count, blocks, interior, straddling);
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Dry hills tall enough to span the whole iron curve: valleys just above sea level
     * (y≈330) and peaks well past the high-altitude peak at y=420, so all three sample
     * bands hold real rock. {@code DryHillsTileSource} tops out around y=404 and would
     * leave the highland band empty.
     */
    private static final class TallHillsTileSource implements TerrainTileSource {
        private static final int TILE_SIZE = 256;
        private static final int BASE = WorldConfiguration.SEA_LEVEL + 120;
        private static final int AMPLITUDE = 55;
        static final int MAX_HEIGHT = BASE + 2 * AMPLITUDE;

        private final Map<Long, TerrainTile> cache = new HashMap<>();

        static int height(int worldX, int worldZ) {
            double offset = Math.sin(worldX * 0.031) * AMPLITUDE
                    + Math.cos(worldZ * 0.043) * AMPLITUDE;
            return BASE + (int) Math.round(offset);
        }

        @Override
        public synchronized TerrainTile getTile(int worldX, int worldZ) {
            int tileX = Math.floorDiv(worldX, TILE_SIZE);
            int tileZ = Math.floorDiv(worldZ, TILE_SIZE);
            return cache.computeIfAbsent((((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL),
                    k -> build(tileX, tileZ));
        }

        private static TerrainTile build(int tileX, int tileZ) {
            int i1 = tileX * TILE_SIZE;
            int j1 = tileZ * TILE_SIZE;
            short[] heights = new short[TILE_SIZE * TILE_SIZE];
            short[] biomes = new short[TILE_SIZE * TILE_SIZE];
            short[] waterLevels = new short[TILE_SIZE * TILE_SIZE];
            for (int row = 0; row < TILE_SIZE; row++) {
                for (int col = 0; col < TILE_SIZE; col++) {
                    int idx = row * TILE_SIZE + col;
                    heights[idx] = (short) height(i1 + row, j1 + col);
                    biomes[idx] = 1; // plains — no RED_SAND_DESERT crystals in the way
                    waterLevels[idx] = TerrainTile.NO_WATER;
                }
            }
            return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                    TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels);
        }
    }
}
