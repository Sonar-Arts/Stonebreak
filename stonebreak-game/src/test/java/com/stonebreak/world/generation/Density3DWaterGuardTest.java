package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.WaterGuard;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overhang band must be sealed against standing water, exactly as the mask carvers are.
 *
 * <p>Every mask carver goes through {@link WaterGuard}, whose promise is containment: no
 * carve under — or beside — standing water, because worldgen water is a source block and a
 * breach pours {@code WaterSim} down the hole across every chunk that loads. The band carve
 * decision alone is gated only on depth and the biome's intensity, so without its own seal a
 * submerged column whose biome has a non-zero intensity can be carved at depth 1 directly
 * beneath the source water cell, and a dry bank column beside the ocean can be carved at the
 * water line on the side wall — both exactly what the mask carvers are sealed against.
 *
 * <p>This asserts the invariant over a deterministic coastline: for every sealed band cell
 * in the band-only depth range (with the {@code WATER_CLEARANCE} the band carves with), both
 * density backends must keep the cell solid — the per-point {@link Density3D#isSolid} (the
 * Java-backend path) and the prepared field (the native-fill path), each handed the same
 * {@link WaterGuard} plane. A sealed cell that either backend carves is the breach this test
 * exists to catch.
 *
 * <p>The depth range is band-only, and that is what isolates the assertion: the whole
 * {@code isSolid} also runs the tube test below the band, whose fade opens at
 * {@code SPAG_FADE_START} and can barely fire at depth 11+ — a tube at those depths is by
 * design (capped by the fade at depth 10, so no water can pour down its top), not a band
 * carve, and cannot be told apart from one from the outside. At depth 10 and shallower the
 * tube test cannot fire at all, so the band branch is the only carve candidate — and the
 * breach is at depth 1, the block directly beneath the source water cell.
 *
 * <p>The assertion is an invariant rather than a targeted sample, which moves with terrain
 * and biome: sealed means solid, wherever the seal holds. The gate-liveness check guards
 * against the seal predicate being wired to nothing — across the scan, some sealed band
 * cell must have been carved had the backend run unguarded, or the invariant proves nothing.
 *
 * <p>Note what this deliberately does <em>not</em> test: agreement between the two backends
 * on which cells they carve. Those use different simplex implementations and produce
 * different terrain by design; each is only required to respect the same seal.
 */
public class Density3DWaterGuardTest {

    private static final long SEED = 13579L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int REGION = 4;
    /** Mirrors Density3D.WATER_CLEARANCE. */
    private static final int CLEARANCE = 1;
    /** Density3D.CAVE_FLOOR / OVERHANG_DEPTH — the band the seal applies to. */
    private static final int CAVE_FLOOR = 8;
    private static final int OVERHANG_DEPTH = 16;
    /**
     * Density3D.SPAG_FADE_START — the tube test below the band opens here. At depth 10
     * and shallower it cannot fire, so the band branch is the only carve candidate; see
     * the class doc for why the invariant range stops here.
     */
    private static final int SPAG_FADE_START = 10;

    @Test
    public void sealedBandCellsStaySolidHeadless() {
        CoastHeightMap src = new CoastHeightMap(SEED);
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, src);
        Density3D density3D = new Density3D(SEED, src);

        long[] counts = scanSealedBandCells(terrain, src, density3D);

        assertTrue(counts[0] > 0, "no sealed band cells across the scan — the coastline "
                + "did not cover any; the invariant proves nothing");
        assertTrue(counts[1] > 0, "no sealed band cell would have been carved had the "
                + "backend run unguarded — the gate is never live here, the invariant "
                + "proves nothing");
        System.out.printf("[caves] band seal verified over %d sealed band cells "
                + "(%d would have breached unguarded), backend=%s%n",
                counts[0], counts[1], TerrainNoise.backend());
    }

    @Test
    public void sealedBandCellsStaySolidInPreparedField() {
        Assumptions.assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        Assumptions.assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE,
                "native backend inactive");

        CoastHeightMap src = new CoastHeightMap(SEED);
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, src);
        Density3D density3D = new Density3D(SEED, src);

        long[] counts = scanSealedBandCells(terrain, src, density3D);

        assertTrue(counts[0] > 0, "no sealed band cells across the scan — the coastline "
                + "did not cover any; the invariant proves nothing");
        assertTrue(counts[2] > 0, "the prepared field never ran — the native-fill path "
                + "was not exercised (prepareChunk returned null)");
        System.out.printf("[caves] band seal verified over %d sealed band cells in the "
                + "prepared field, backend=%s%n", counts[0], TerrainNoise.backend());
    }

    /**
     * Scans the coastline region and counts, per density backend:
     * [0] sealed band cells in the band-only depth range (shared), [1] sealed cells the
     * unguarded per-point backend would carve, [2] whether the prepared field ran. Every
     * sealed cell must stay solid in both backends — that is the invariant, asserted in place.
     */
    private long[] scanSealedBandCells(TerrainGenerationSystem terrain, CoastHeightMap src,
                                       Density3D density3D) {
        long sealedBandCells = 0;
        long unguardedWouldCarve = 0;
        long fieldRuns = 0;

        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                ColumnProfile profile = terrain.generateTerrainOnly(cx, cz).profile();
                int[] heights = profile.heights();
                int[] waterLevels = profile.waterLevels();
                BiomeType[] biomes = profile.biomes();
                // The same plane instance for both backends: each is required to respect
                // the same seal, so each must read the same seal geometry.
                int[] plane = WaterGuard.guardPlane(heights, waterLevels, src, cx, cz);
                Density3D.Field field =
                    density3D.prepareChunk(cx, cz, heights, waterLevels, biomes, plane);

                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int idx = lx * CHUNK + lz;
                        int surface = heights[idx];
                        BiomeType biome = biomes[idx];
                        int worldX = cx * CHUNK + lx;
                        int worldZ = cz * CHUNK + lz;
                        // Band-only depth range [1, fade START]: the tube test below
                        // the band cannot fire here, so the band branch is the only
                        // carve candidate and the invariant isolates it. The breach is
                        // at depth 1, directly beneath the source water cell.
                        int bandFloor = Math.max(CAVE_FLOOR, surface - SPAG_FADE_START);
                        int bandCeiling = surface - 1;
                        for (int y = bandFloor; y <= bandCeiling; y++) {
                            if (!WaterGuard.seals(plane, idx, y, CLEARANCE)) {
                                continue;
                            }
                            sealedBandCells++;
                            if (field != null) {
                                fieldRuns++;
                                assertTrue(field.isSolid(lx, y, lz, surface, biome),
                                        String.format(
                                                "prepared field carved a sealed band cell at "
                                                        + "local (%d,%d,%d), surface %d — the "
                                                        + "native-fill path breaches standing "
                                                        + "water",
                                                lx, y, lz, surface));
                            }
                            assertTrue(density3D.isSolid(worldX, y, worldZ, surface, biome,
                                            plane, idx),
                                    String.format(
                                            "per-point test carved a sealed band cell at world "
                                                    + "(%d,%d,%d), surface %d — the Java-backend "
                                                    + "path breaches standing water",
                                            worldX, y, worldZ, surface));
                            if (!density3D.isSolid(worldX, y, worldZ, surface, biome, null, idx)) {
                                unguardedWouldCarve++;
                            }
                        }
                    }
                }
            }
        }
        return new long[] {sealedBandCells, unguardedWouldCarve, fieldRuns};
    }

    /**
     * A deterministic coastline: a submerged ocean strip with a ripple (so the biome noise
     * varies across it and the band carve is exercised in band biomes), and a dry bank strip
     * beside it. The bank columns strip-adjacent to the ocean are sealed across their whole
     * band — plane = the ocean strip's lowest bed — which is conservative but is exactly the
     * mask carvers' bank policy (see {@code DryHillsHeightMap}: WaterGuard seals nearly every
     * near-surface column as somebody's bank — correctly).
     *
     * <p>Ocean heights stay under sea level at every ripple knot so every strip column is wet;
     * bank heights stay well above it so the bank strip is dry. Mirrors {@code DryHillsHeightMap}:
     * overriding a height oracle means overriding all of its height methods.
     */
    private static final class CoastHeightMap extends HeightMapGenerator {

        private static final int OCEAN_BASE = WorldConfiguration.SEA_LEVEL - 10;
        private static final int BANK_BASE = WorldConfiguration.SEA_LEVEL + 40;
        private static final int STRIP = 16;
        private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

        public CoastHeightMap(long seed) {
            super(new NoiseRouter(seed));
        }

        static int height(int worldX, int worldZ) {
            int base = Math.floorMod(worldZ, STRIP * 2) < STRIP ? OCEAN_BASE : BANK_BASE;
            return base + (int) Math.round(Math.sin(worldX * 0.05) * 3 + Math.cos(worldZ * 0.07) * 3);
        }

        @Override
        public int generateHeight(int x, int z) {
            return height(x, z);
        }

        @Override
        public int baseHeight(int x, int z) {
            return height(x, z);
        }

        @Override
        public int shapedHeight(int x, int z) {
            return height(x, z);
        }

        @Override
        public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    out[x * CHUNK_SIZE + z] =
                        height(chunkX * CHUNK_SIZE + x, chunkZ * CHUNK_SIZE + z);
                }
            }
        }
    }
}
