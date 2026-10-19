package com.stonebreak.world.generation.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Swamp pools must never leak. Every generated water cell must have a watertight floor and
 * four watertight sides, or WaterSim wakes it on load and the source floods forever. The
 * fixtures are built to tempt leaks: stepped ground, bumps, carved air beside the surface,
 * and an unloaded neighbour chunk answered by the terrain-pass oracle, including a carved one.
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
        new SwampPoolGenerator(new DeterministicRandom(seed)).generate(
            context(chunk, terrain, biome), outside);
        return new Fixture(chunk, outside);
    }

    /** Unloaded neighbours answered by the terrain pass instead of by {@code outside}. */
    private static Fixture generate(Terrain terrain, BiomeType biome, long seed,
                                    SwampPoolGenerator.ColumnOracle columns) {
        Chunk chunk = new Chunk(0, 0);
        new SwampPoolGenerator(new DeterministicRandom(seed)).generate(
            context(chunk, terrain, biome), columns);
        return new Fixture(chunk, (x, y, z) -> SwampPoolGenerator.terrainPassBlock(columns, x, y, z));
    }

    /** Fills {@code chunk} the way the terrain pass would and wraps it in a generation context. */
    private static ChunkGenerationContext context(Chunk chunk, Terrain terrain, BiomeType biome) {
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
        return new ChunkGenerationContext(null, chunk, null, heights, biomes, null, biome);
    }

    private static SwampPoolGenerator.BlockReader solidBelow(Terrain terrain) {
        return (x, y, z) -> y < terrain.height(x, z) ? BlockType.DIRT : BlockType.AIR;
    }

    /** A {@code ColumnOracle} reading its three column facts from per-column terrain functions. */
    private static SwampPoolGenerator.ColumnOracle oracle(Terrain raw, Terrain surface, Terrain water) {
        return new SwampPoolGenerator.ColumnOracle() {
            @Override public int rawHeight(int x, int z) { return raw.height(x, z); }
            @Override public int surfaceHeight(int x, int z) { return surface.height(x, z); }
            @Override public int waterLevel(int x, int z) { return water.height(x, z); }
        };
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
            new ChunkGenerationContext(null, chunk, null, heights, biomes, null, BiomeType.SWAMP), outside);
        return new Fixture(chunk, outside);
    }

    @Test
    void unloadedNeighbourAnsweredByOracleFloodsBorderColumns() {
        Terrain flat = (x, z) -> BASE;
        SwampPoolGenerator.ColumnOracle columns = oracle((x, z) -> BASE, (x, z) -> BASE, (x, z) -> -1);
        int borderWater = 0;
        for (long seed = 0; seed < 20; seed++) {
            Fixture f = generate(flat, BiomeType.SWAMP, seed, columns);
            assertSealed(f);
            for (int i = 0; i < SIZE; i++) {
                if (f.chunk().getBlock(0, BASE - 1, i) == BlockType.WATER) borderWater++;
                if (f.chunk().getBlock(SIZE - 1, BASE - 1, i) == BlockType.WATER) borderWater++;
                if (f.chunk().getBlock(i, BASE - 1, 0) == BlockType.WATER) borderWater++;
                if (f.chunk().getBlock(i, BASE - 1, SIZE - 1) == BlockType.WATER) borderWater++;
            }
        }
        assertTrue(borderWater > 0, "border columns are still forced dry");
    }

    @Test
    void borderPoolsDoNotDependOnPopulationOrder() {
        Terrain flat = (x, z) -> BASE;
        SwampPoolGenerator.ColumnOracle columns = oracle((x, z) -> BASE, (x, z) -> BASE, (x, z) -> -1);
        SwampPoolGenerator.BlockReader oracleOutside =
            (x, y, z) -> SwampPoolGenerator.terrainPassBlock(columns, x, y, z);
        SwampPoolGenerator gen = new SwampPoolGenerator(new DeterministicRandom(3));

        // Order 1: A first with B unloaded, then B with A actually loaded.
        Chunk a1 = new Chunk(0, 0);
        Chunk b1 = new Chunk(1, 0);
        gen.generate(context(a1, flat, BiomeType.SWAMP), columns);
        gen.generate(context(b1, flat, BiomeType.SWAMP), loadedNeighbour(a1, oracleOutside));

        // Order 2: the same pair populated the other way round.
        Chunk a2 = new Chunk(0, 0);
        Chunk b2 = new Chunk(1, 0);
        gen.generate(context(b2, flat, BiomeType.SWAMP), columns);
        gen.generate(context(a2, flat, BiomeType.SWAMP), loadedNeighbour(b2, oracleOutside));

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = BASE - 3; y < BASE + 1; y++) {
                    assertEquals(a1.getBlock(x, y, z), a2.getBlock(x, y, z), "A(" + x + "," + y + "," + z + ")");
                    assertEquals(b1.getBlock(x, y, z), b2.getBlock(x, y, z), "B(" + x + "," + y + "," + z + ")");
                }
            }
        }
        assertPairSealed(a1, b1, oracleOutside);
        assertPairSealed(a2, b2, oracleOutside);
    }

    @Test
    void carvedOracleColumnKeepsBorderDry() {
        Terrain flat = (x, z) -> BASE;
        // The -X neighbour column is carved, so its terrain pass reads as air at pool level.
        SwampPoolGenerator.ColumnOracle columns = oracle(
            (x, z) -> BASE,
            (x, z) -> x == -1 ? BASE - 2 : BASE,
            (x, z) -> -1);
        for (long seed = 0; seed < 20; seed++) {
            Fixture f = generate(flat, BiomeType.SWAMP, seed, columns);
            assertSealed(f);
            for (int z = 0; z < SIZE; z++) {
                assertNotEquals(BlockType.WATER, f.chunk().getBlock(0, BASE - 1, z), "z=" + z);
            }
        }
    }

    /** Reads a loaded neighbour chunk's cells from that chunk, everything else from {@code elsewhere}. */
    private static SwampPoolGenerator.BlockReader loadedNeighbour(Chunk other,
            SwampPoolGenerator.BlockReader elsewhere) {
        int ox = other.getChunkX() * SIZE;
        int oz = other.getChunkZ() * SIZE;
        return (x, y, z) -> x >= ox && x < ox + SIZE && z >= oz && z < oz + SIZE
            ? other.getBlock(x - ox, y, z - oz)
            : elsewhere.get(x, y, z);
    }

    /** Every water cell in the chunk pair stays sealed once both chunks and the oracle are visible. */
    private static void assertPairSealed(Chunk a, Chunk b, SwampPoolGenerator.BlockReader outside) {
        SwampPoolGenerator.BlockReader both = loadedNeighbour(a, loadedNeighbour(b, outside));
        List<String> leaks = new ArrayList<>();
        for (Chunk c : new Chunk[] {a, b}) {
            int ox = c.getChunkX() * SIZE;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = BASE - 3; y < BASE + 1; y++) {
                        if (c.getBlock(x, y, z) != BlockType.WATER) continue;
                        if (!SwampPoolGenerator.sealed(both, ox + x, y, z)) {
                            leaks.add("chunk " + c.getChunkX() + " (" + x + "," + y + "," + z + ")");
                        }
                    }
                }
            }
        }
        assertTrue(leaks.isEmpty(), () -> leaks.size() + " leaking cells, e.g. " + leaks.subList(0, Math.min(8, leaks.size())));
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
