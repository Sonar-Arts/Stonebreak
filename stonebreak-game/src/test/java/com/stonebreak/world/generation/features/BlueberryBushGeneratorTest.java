package com.stonebreak.world.generation.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;

/**
 * Wild blueberry bushes: plains only, on grass only, in groups of 1 to 3 around a rolled anchor,
 * already grown, and deterministic per seed.
 */
class BlueberryBushGeneratorTest {

    private static final int SURFACE = 80;
    private static final int SIZE = ChunkGenerationContext.SIZE;
    /** Enough chunks that the sparse group roll fires many times. */
    private static final int CHUNKS = 24;
    private static final long SEED = 1234L;

    private static Chunk generate(int cx, int cz, BiomeType biome, BlockType ground, long seed) {
        Chunk chunk = new Chunk(cx, cz);
        int[] heights = new int[SIZE * SIZE];
        BiomeType[] biomes = new BiomeType[SIZE * SIZE];
        int[] waterLevels = new int[SIZE * SIZE];
        Arrays.fill(waterLevels, TerrainTile.NO_WATER);
        Arrays.fill(heights, SURFACE);
        Arrays.fill(biomes, biome);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                chunk.setBlock(x, SURFACE - 1, z, ground);
            }
        }
        new BlueberryBushGenerator(new DeterministicRandom(seed)).generate(
            new ChunkGenerationContext(null, chunk, null, heights, biomes, waterLevels, biome));
        return chunk;
    }

    private static List<int[]> bushes(Chunk chunk) {
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (chunk.getBlock(x, SURFACE, z) == BlockType.BLUEBERRY_BUSH) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    /** Columns whose group roll fires (the roll is stateless per world position). */
    private static List<int[]> anchors(int cx, int cz, long seed) {
        DeterministicRandom rng = new DeterministicRandom(seed);
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (rng.getFloat(cx * SIZE + x, cz * SIZE + z, "blueberry_group")
                        < BlueberryBushGenerator.GROUP_CHANCE) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    @Test
    void plainsGrassGrowsGroupsOfOneToThreeAroundEachAnchor() {
        int totalAnchors = 0;
        int totalBushes = 0;
        boolean sawMultiBushGroup = false;
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                List<int[]> anchors = anchors(cx, cz, SEED);
                List<int[]> bushes = bushes(generate(cx, cz, BiomeType.PLAINS, BlockType.GRASS, SEED));
                totalAnchors += anchors.size();
                totalBushes += bushes.size();
                // An anchor an earlier group already filled adds nothing, so only the cap is exact.
                assertTrue(bushes.isEmpty() == anchors.isEmpty() && bushes.size() <= 3 * anchors.size(),
                    "chunk " + cx + "," + cz + ": " + bushes.size() + " bushes for " + anchors.size() + " groups");
                for (int[] b : bushes) {
                    boolean nearAnchor = anchors.stream().anyMatch(a ->
                        Math.abs(a[0] - b[0]) <= BlueberryBushGenerator.GROUP_RADIUS
                            && Math.abs(a[1] - b[1]) <= BlueberryBushGenerator.GROUP_RADIUS);
                    assertTrue(nearAnchor, "bush at " + b[0] + "," + b[1] + " belongs to no group");
                }
                if (anchors.size() == 1 && bushes.size() > 1) {
                    sawMultiBushGroup = true;
                }
            }
        }
        assertTrue(totalAnchors > 5, "too few groups rolled to test: " + totalAnchors);
        assertTrue(sawMultiBushGroup, "no group ever had more than one bush");
        assertTrue(totalBushes > totalAnchors, "groups never grew past one bush");
    }

    @Test
    void onlyPlainsAndOnlyGrass() {
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                for (BiomeType other : new BiomeType[]{BiomeType.MEADOW, BiomeType.DESERT, BiomeType.SWAMP}) {
                    assertTrue(bushes(generate(cx, cz, other, BlockType.GRASS, SEED)).isEmpty(), other.name());
                }
                assertTrue(bushes(generate(cx, cz, BiomeType.PLAINS, BlockType.DIRT, SEED)).isEmpty(), "dirt");
            }
        }
    }

    @Test
    void wildBushesAreGrownAndCarryTheirTimer() {
        int seen = 0;
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                Chunk chunk = generate(cx, cz, BiomeType.PLAINS, BlockType.GRASS, SEED);
                for (int[] b : bushes(chunk)) {
                    BlueberryBushState s = BlueberryBushState.parse(chunk.getBlockState(b[0], SURFACE, b[1]));
                    assertNotNull(s, "wild bush without a state");
                    assertTrue(!s.stage().isGrowing(), "wild bush still growing: " + s.stage());
                    assertTrue(s.isRipe() || s.ticksLeft() > 0, "stage without a timer: " + s);
                    seen++;
                }
            }
        }
        assertTrue(seen > 0);
    }

    @Test
    void sameSeedSameBushes() {
        for (int cx = 0; cx < 8; cx++) {
            Chunk a = generate(cx, 3, BiomeType.PLAINS, BlockType.GRASS, SEED);
            Chunk b = generate(cx, 3, BiomeType.PLAINS, BlockType.GRASS, SEED);
            List<int[]> ba = bushes(a);
            List<int[]> bb = bushes(b);
            assertEquals(ba.size(), bb.size());
            for (int i = 0; i < ba.size(); i++) {
                assertTrue(Arrays.equals(ba.get(i), bb.get(i)));
                assertEquals(a.getBlockState(ba.get(i)[0], SURFACE, ba.get(i)[1]),
                    b.getBlockState(bb.get(i)[0], SURFACE, bb.get(i)[1]));
            }
        }
    }
}
