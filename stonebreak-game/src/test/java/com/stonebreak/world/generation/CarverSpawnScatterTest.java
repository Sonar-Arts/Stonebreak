package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.noise.NoiseRouter;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worm, cavern and megacavern spawn chunks must scatter along z, not repeat on a lattice
 * (issue #243).
 *
 * <p>The spawn hashes XOR {@code cz * K} in after their only rotate. The low k bits of that
 * product depend only on {@code cz mod 2^k}, and {@code floorMod(h, D)} for D = 8, 48, 192
 * reads only the low 3, 4 and 6 bits' worth of residue. Unmixed, each chunk column could
 * spawn at exactly one residue of {@code cz mod 8} (worms), {@code mod 16} (caverns) or
 * {@code mod 64} (megacaverns) — spawns every 128, 256 and 1024 blocks along z, visible as
 * stripes. The splitmix64 finalizer breaks that; this pins it.
 *
 * <p>The native kernel ({@code terrain_ctx.hpp hasWorm}, {@code generator.cpp hasCavern})
 * mirrors these hashes; {@code WormCarverParityTest} and {@code FusedChunkGenParityTest}
 * keep the two spellings in step.
 */
class CarverSpawnScatterTest {

    private static final long SEED = 12345L;
    private static final int COLUMNS = 8;
    private static final int CZ_MIN = -16384;
    private static final int CZ_MAX = 16384;

    @Test
    void wormSpawnsCoverEveryResidueMod8() {
        PerlinWormCarver worms =
                new PerlinWormCarver(SEED, new HeightMapGenerator(new NoiseRouter(SEED)));
        assertScattered("worm", worms::hasWormAt, 8, 8, 8);
    }

    @Test
    void cavernSpawnsCoverEveryResidueMod16() {
        CavernCarver caverns = new CavernCarver(SEED, null);
        assertScattered("cavern", caverns::hasCavern, 16, 16, 48);
    }

    @Test
    void megaCavernSpawnsCoverMostResiduesMod64() {
        MegaCavernCarver mega = new MegaCavernCarver(SEED, null);
        // ~170 spawns per column over 64 residues: near-full coverage, lattice would give 1.
        assertScattered("megacavern", mega::hasCavern, 64, 48, 192);
    }

    /**
     * For each column, the set of {@code cz mod period} residues that spawn must reach
     * {@code minResidues}, and the spawn rate must stay near {@code 1/divisor} so the
     * finalizer did not change feature density.
     */
    private static void assertScattered(String name, BiPredicate<Integer, Integer> spawns,
                                        int period, int minResidues, int divisor) {
        long totalSpawns = 0;
        for (int cx = 0; cx < COLUMNS; cx++) {
            BitSet residues = new BitSet(period);
            for (int cz = CZ_MIN; cz < CZ_MAX; cz++) {
                if (spawns.test(cx, cz)) {
                    residues.set(Math.floorMod(cz, period));
                    totalSpawns++;
                }
            }
            assertTrue(residues.cardinality() >= minResidues,
                    name + " cx=" + cx + " spawns at only " + residues.cardinality()
                            + " residues of cz mod " + period + " (need " + minResidues
                            + ") — spawn hash is periodic in z");
        }
        double expected = (double) COLUMNS * (CZ_MAX - CZ_MIN) / divisor;
        double ratio = totalSpawns / expected;
        assertTrue(ratio > 0.85 && ratio < 1.15,
                name + " spawn rate " + totalSpawns + " vs expected " + (long) expected);
    }
}
