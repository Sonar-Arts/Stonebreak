package com.stonebreak.world.generation.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;

/**
 * Swamp pools must never leak. Every generated water cell must have a watertight floor and
 * four watertight sides, or WaterSim wakes it on load and the source floods forever. The
 * fixtures are built to tempt leaks: stepped ground, bumps, carved air beside the surface,
 * and an unloaded (air) neighbour chunk.
 */
class SwampPoolGeneratorTest {

    private static final int SIZE = ChunkGenerationContext.SIZE;
    private static final int BASE = 80;

    /** Terrain column heights for the chunk and the ring around it. */
    @FunctionalInterface
    private interface Terrain {
        int height(int worldX, int worldZ);
    }

    private record Fixture(Chunk chunk, SwampPoolGenerator.BlockReader outside) {
        BlockType get(int x, int y, int z) {
            boolean local = x >= 0 && x < SIZE && z >= 0 && z < SIZE;
            return local ? chunk.getBlock(x, y, z) : outside.get(x, y, z);
        }
    }

    private static Fixture generate(Terrain terrain, BiomeType biome, long seed,
                                    SwampPoolGenerator.BlockReader outside) {
        Chunk chunk = new Chunk(0, 0);
        int[] heights = new int[SIZE * SIZE];
        BiomeType[] biomes = new BiomeType[SIZE * SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int h = terrain.height(x, z);
                heights[x * SIZE + z] = h;
                biomes[x * SIZE + z] = biome;
                for (int y = h - 5; y < h - 1; y++) chunk.setBlock(x, y, z, BlockType.DIRT);
                chunk.setBlock(x, h - 1, z, BlockType.SWAMPY_GRASS);
            }
        }
        new SwampPoolGenerator(new DeterministicRandom(seed)).generate(
            new ChunkGenerationContext(null, chunk, null, heights, biomes, noWater(), biome), outside);
        return new Fixture(chunk, outside);
    }

    /** No column is submerged: swamp pools come from the generator, not the terrain's water plane. */
    private static int[] noWater() {
        int[] levels = new int[SIZE * SIZE];
        Arrays.fill(levels, TerrainTile.NO_WATER);
        return levels;
    }

    private static SwampPoolGenerator.BlockReader solidBelow(Terrain terrain) {
        return (x, y, z) -> y < terrain.height(x, z) ? BlockType.DIRT : BlockType.AIR;
    }

    /** Asserts containment and returns how many water cells there are. */
    private static int assertSealed(Fixture f) {
        List<String> leaks = new ArrayList<>();
        int water = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = BASE - 12; y < BASE + 12; y++) {
                    if (f.chunk().getBlock(x, y, z) != BlockType.WATER) continue;
                    water++;
                    if (!SwampPoolGenerator.sealed(f::get, x, y, z)) {
                        leaks.add("(" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }
        assertTrue(leaks.isEmpty(), () -> leaks.size() + " leaking cells, e.g. " + leaks.subList(0, Math.min(8, leaks.size())));
        return water;
    }

    @Test
    void flatSwampMixesWaterAndGrass() {
        Terrain flat = (x, z) -> BASE;
        int water = 0;
        for (long seed = 0; seed < 20; seed++) {
            water += assertSealed(generate(flat, BiomeType.SWAMP, seed, solidBelow(flat)));
        }
        int columns = 20 * SIZE * SIZE;
        assertTrue(water > columns / 5 && water < columns, "water cells: " + water + " of " + columns);
    }

    @Test
    void steppedAndBumpyGroundNeverLeaks() {
        for (long seed = 0; seed < 50; seed++) {
            Random bumps = new Random(seed);
            int[][] noise = new int[SIZE + 2][SIZE + 2];
            for (int[] row : noise) for (int i = 0; i < row.length; i++) row[i] = bumps.nextInt(3) - 1;
            Terrain terrain = (x, z) -> BASE + (x >= 8 ? 2 : 0) + noise[x + 1][z + 1];
            assertSealed(generate(terrain, BiomeType.SWAMP, seed, solidBelow(terrain)));
        }
    }

    @Test
    void carvedAirBesideThePoolLevelNeverLeaks() {
        Terrain flat = (x, z) -> BASE;
        for (long seed = 0; seed < 30; seed++) {
            long s = seed;
            // A cave mouth: air in the outside ring and a pocket cut into the chunk after
            // terrain, before features.
            SwampPoolGenerator.BlockReader outside = (x, y, z) ->
                (y == BASE - 1 && (x + z + s) % 3 == 0) || y >= BASE ? BlockType.AIR : BlockType.DIRT;
            Fixture f = generateWithPocket(flat, seed, outside);
            assertSealed(f);
        }
    }

    private static Fixture generateWithPocket(Terrain terrain, long seed, SwampPoolGenerator.BlockReader outside) {
        Chunk chunk = new Chunk(0, 0);
        int[] heights = new int[SIZE * SIZE];
        BiomeType[] biomes = new BiomeType[SIZE * SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                heights[x * SIZE + z] = BASE;
                biomes[x * SIZE + z] = BiomeType.SWAMP;
                for (int y = BASE - 5; y < BASE - 1; y++) chunk.setBlock(x, y, z, BlockType.DIRT);
                chunk.setBlock(x, BASE - 1, z, BlockType.SWAMPY_GRASS);
            }
        }
        for (int x = 5; x < 9; x++) {
            chunk.setBlock(x, BASE - 2, 7, BlockType.AIR); // carved just under the surface
            chunk.setBlock(x, BASE - 1, 9, BlockType.AIR); // carved at surface level
        }
        new SwampPoolGenerator(new DeterministicRandom(seed)).generate(
            new ChunkGenerationContext(null, chunk, null, heights, biomes, noWater(), BiomeType.SWAMP), outside);
        return new Fixture(chunk, outside);
    }

    @Test
    void unloadedNeighbourKeepsBorderColumnsDry() {
        Terrain flat = (x, z) -> BASE;
        for (long seed = 0; seed < 20; seed++) {
            Fixture f = generate(flat, BiomeType.SWAMP, seed, (x, y, z) -> BlockType.AIR);
            assertSealed(f);
            for (int i = 0; i < SIZE; i++) {
                assertEquals(BlockType.SWAMPY_GRASS, f.chunk().getBlock(0, BASE - 1, i));
                assertEquals(BlockType.SWAMPY_GRASS, f.chunk().getBlock(SIZE - 1, BASE - 1, i));
            }
        }
    }

    @Test
    void onlySwampsGetPools() {
        Terrain flat = (x, z) -> BASE;
        for (BiomeType biome : BiomeType.values()) {
            if (biome == BiomeType.SWAMP) continue;
            assertEquals(0, assertSealed(generate(flat, biome, 7, solidBelow(flat))), biome.name());
        }
    }
}
