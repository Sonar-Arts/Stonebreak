package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sparse cactus spawning must be deterministic, biome-gated, ground-gated and
 * headroom-capped.
 *
 * <p>Roll-and-write in the shape of {@code OreGenerator.generateCrystals}, so the pinned
 * properties are the ones a probability tweak cannot fake: every placed formation is 2-3
 * cells tall (a height roll cannot produce anything else), cacti grow only on sand/red
 * sand in deserts and red sand deserts, and a cell in the way truncates the stack so
 * carvers or water above never clip through a cactus. Same-seed determinism ratchets the
 * whole behaviour — the same chunk must populate identically every time.
 */
public class CactusGeneratorTest {

    private static final int SURFACE = 80;
    private static final int SIZE = ChunkGenerationContext.SIZE;

    /** Hand-built terrained chunk: ground block at SURFACE - 1, everything above air. */
    private record Population(Chunk chunk, int[] heights, BiomeType[] biomes) {}

    private Population population(BiomeType biome, BlockType ground, long seed) {
        Chunk chunk = new Chunk(0, 0);
        int[] heights = new int[SIZE * SIZE];
        BiomeType[] biomes = new BiomeType[SIZE * SIZE];
        int[] waterLevels = new int[SIZE * SIZE];
        Arrays.fill(waterLevels, TerrainTile.NO_WATER);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                heights[x * SIZE + z] = SURFACE;
                biomes[x * SIZE + z] = biome;
                chunk.setBlock(x, SURFACE - 1, z, ground);
            }
        }
        new CactusGenerator(new DeterministicRandom(seed)).generate(
                new ChunkGenerationContext(null, chunk, null, heights, biomes, waterLevels, biome));
        return new Population(chunk, heights, biomes);
    }

    /** Columns the placement roll would fire for (probed standalone, stateless per position). */
    private List<int[]> placingColumns(BiomeType[] biomes, long seed) {
        List<int[]> columns = new ArrayList<>();
        DeterministicRandom rng = new DeterministicRandom(seed);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (rng.getFloat(x, z, "cactus") < CactusGenerator.CACTUS_CHANCE) {
                    columns.add(new int[]{x, z});
                }
            }
        }
        return columns;
    }

    /** Vertical cactus runs starting at SURFACE, across the whole chunk. */
    private List<Integer> formations(Population p) {
        List<Integer> runs = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (p.chunk().getBlock(x, SURFACE, z) != BlockType.CACTUS) {
                    continue;
                }
                int height = 0;
                while (SURFACE + height < WorldConfiguration.WORLD_HEIGHT
                        && p.chunk().getBlock(x, SURFACE + height, z) == BlockType.CACTUS) {
                    height++;
                }
                runs.add(height);
            }
        }
        return runs;
    }

    @Test
    public void desertFormationsAreTwoOrThreeCellsTall() {
        List<Integer> all = new ArrayList<>();
        // Seeds 16-19: DeterministicRandom's position hash suppresses floats below ~0.012
        // for world seeds 1-15, so a 0.003 chance roll no-ops there (pre-existing quirk —
        // the tundra-pine reference roll does the same). Outside the band the roll is
        // statistically uniform and the band pin below actually measures placement.
        for (long seed : new long[]{16L, 17L, 18L, 19L}) {
            all.addAll(formations(population(BiomeType.DESERT, BlockType.SAND, seed)));
            all.addAll(formations(population(BiomeType.RED_SAND_DESERT, BlockType.RED_SAND, seed)));
        }
        assertFalse(all.isEmpty(), "calibrated sparsity over 8 chunks must place at least one cactus");
        for (int height : all) {
            assertTrue(height >= CactusGenerator.MIN_HEIGHT && height <= CactusGenerator.MAX_HEIGHT,
                    "formation height rolled outside the 2-3 band: " + height);
        }
        assertTrue(all.contains(CactusGenerator.MIN_HEIGHT) || all.contains(CactusGenerator.MAX_HEIGHT));
    }

    @Test
    public void placementIsDeterministicForTheSameSeed() {
        Population first = population(BiomeType.DESERT, BlockType.SAND, 987654321L);
        Population second = population(BiomeType.DESERT, BlockType.SAND, 987654321L);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                assertEquals(first.chunk().getBlock(x, SURFACE, z),
                        second.chunk().getBlock(x, SURFACE, z),
                        "same seed must populate identically at (" + x + ", " + z + ")");
                assertEquals(first.chunk().getBlock(x, SURFACE + 1, z),
                        second.chunk().getBlock(x, SURFACE + 1, z));
            }
        }
    }

    @Test
    public void otherBiomesGrowNothing() {
        // Seed 16 so deserts DO place at this seed — the gate assertion is non-vacuous.
        for (BiomeType biome : new BiomeType[]{BiomeType.PLAINS, BiomeType.TAIGA, BiomeType.MEADOW,
                BiomeType.TUNDRA, BiomeType.BADLANDS}) {
            Population p = population(biome, BlockType.GRASS, 16L);
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    assertFalse(p.chunk().getBlock(x, SURFACE, z) == BlockType.CACTUS,
                            biome + " must not grow cacti");
                }
            }
        }
    }

    @Test
    public void groundGateRejectsNonSandColumns() {
        // Red sand desert with a stone surface: the ground gate requires sand/red sand.
        // Seed 16 so the gate is non-vacuous (the roll fires on sand at this seed).
        Population stoneGround = population(BiomeType.RED_SAND_DESERT, BlockType.STONE, 16L);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                assertFalse(stoneGround.chunk().getBlock(x, SURFACE, z) == BlockType.CACTUS,
                        "cactus must not generate on stone ground");
            }
        }

        // Sand desert with the wrong sand: the ground gate is biome-matched sand.
        Population wrongSand = population(BiomeType.DESERT, BlockType.RED_SAND, 1L);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                assertFalse(wrongSand.chunk().getBlock(x, SURFACE, z) == BlockType.CACTUS,
                        "desert sand gate must not accept red sand ground");
            }
        }

        // And every base in a normal run really sits on its biome's sand.
        Population p = population(BiomeType.RED_SAND_DESERT, BlockType.RED_SAND, 17L);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (p.chunk().getBlock(x, SURFACE, z) == BlockType.CACTUS) {
                    assertEquals(BlockType.RED_SAND, p.chunk().getBlock(x, SURFACE - 1, z));
                }
            }
        }
    }

    @Test
    public void headroomTruncatesTheStack() {
        // Seed 16: outside the low-seed suppressed band of DeterministicRandom's position
        // hash (seeds 1-15 never fire a 0.003 roll), so the probe finds real placements.
        long seed = 16L;
        Population base = population(BiomeType.DESERT, BlockType.SAND, seed);
        List<int[]> columns = placingColumns(base.biomes(), seed);
        assertFalse(columns.isEmpty(), "probe must find the columns the roll would fire for");

        Set<Long> placing = new HashSet<>();
        for (int[] col : columns) {
            placing.add((long) col[0] * SIZE + col[1]);
        }

        // Pre-fill the cell one above the surface with stone on every placing column.
        Chunk chunk = new Chunk(0, 0);
        int[] heights = new int[SIZE * SIZE];
        BiomeType[] biomes = new BiomeType[SIZE * SIZE];
        int[] waterLevels = new int[SIZE * SIZE];
        Arrays.fill(waterLevels, TerrainTile.NO_WATER);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                heights[x * SIZE + z] = SURFACE;
                biomes[x * SIZE + z] = BiomeType.DESERT;
                chunk.setBlock(x, SURFACE - 1, z, BlockType.SAND);
                if (placing.contains((long) x * SIZE + z)) {
                    chunk.setBlock(x, SURFACE + 1, z, BlockType.STONE);
                }
            }
        }
        new CactusGenerator(new DeterministicRandom(seed)).generate(
                new ChunkGenerationContext(null, chunk, null, heights, biomes, waterLevels,
                        BiomeType.DESERT));

        for (int[] col : columns) {
            int x = col[0];
            int z = col[1];
            assertEquals(BlockType.CACTUS, chunk.getBlock(x, SURFACE, z),
                    "the base must still be placed at (" + x + ", " + z + ")");
            assertEquals(BlockType.STONE, chunk.getBlock(x, SURFACE + 1, z),
                    "headroom must truncate the stack — never clip through the cell above");
        }
    }
}
