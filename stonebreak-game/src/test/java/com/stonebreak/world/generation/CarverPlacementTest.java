package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.heightmap.RavineCarver;
import com.stonebreak.world.generation.heightmap.SinkholeCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where features are placed, and that placement staying the answer it was.
 *
 * <p>Every carver decides whether a chunk hosts it with a hand-written integer hash of
 * {@code (seed, cx, cz)}, mixed with a splitmix64 finaliser and taken modulo a divisor. Five
 * of these, all spelled slightly differently, and three of them carry a comment explaining
 * that the finaliser is not optional — because without it the low bits stay correlated and
 * {@code floorMod} against a small divisor pulls features into stripes, or into nothing at
 * all. A hash that quietly stops firing is precisely the bug {@code CaveReachabilityTest}'s
 * history records, and none of the aggregate cave tests can name which carver went quiet:
 * they measure the finished world, where one absent feature is absorbed by the others.
 *
 * <p>Placement must also be a pure function of {@code (seed, chunk)}. Chunks generate
 * concurrently and in arbitrary order, and a carver that carried any state between calls
 * would give a chunk different neighbours depending on what was loaded first — a world that
 * fails to line up with itself, and only sometimes.
 */
public class CarverPlacementTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    /** Chunks per side sampled for placement statistics. */
    private static final int REGION = 400;

    private final DryHillsHeightMap heightMap = new DryHillsHeightMap(SEED);

    // ---- spawn rates -------------------------------------------------------------------

    /**
     * Expected rate per carver, from the divisor each one documents. Retuning a divisor is a
     * real design change — it moves the numbers in {@code CaveVolumeTest} and
     * {@code CaveReachabilityTest} too — so it should update this list rather than slip past
     * it. The band is wide enough that hash sampling noise never trips it.
     */
    @Test
    public void everyCarverSpawnsAtTheRateItDocuments() {
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        MegaCavernCarver megaCaverns = new MegaCavernCarver(SEED, heightMap);
        RavineCarver ravines = new RavineCarver(SEED, heightMap);
        SinkholeCarver sinkholes = new SinkholeCarver(SEED, heightMap);

        assertRate("worm", worm::hasWormAt, 8);
        assertRate("cavern", caverns::hasCavern, 48);
        assertRate("megacavern", megaCaverns::hasCavern, 192);
        assertRate("ravine", ravines::hasRavine, 450);
        assertRate("sinkhole", sinkholes::hasSinkhole, 40);
    }

    private static void assertRate(String what, BiPredicate<Integer, Integer> has, int divisor) {
        int hits = 0;
        int[] quadrant = new int[4];
        for (int cx = -REGION / 2; cx < REGION / 2; cx++) {
            for (int cz = -REGION / 2; cz < REGION / 2; cz++) {
                if (!has.test(cx, cz)) continue;
                hits++;
                quadrant[(cx < 0 ? 0 : 1) + (cz < 0 ? 0 : 2)]++;
            }
        }

        double rate = hits / (double) (REGION * REGION);
        double expected = 1.0 / divisor;
        System.out.printf("[carvers] %s spawn rate: 1 in %.1f chunks (documented 1 in %d)%n",
                what, hits == 0 ? Double.POSITIVE_INFINITY : (REGION * REGION) / (double) hits,
                divisor);

        assertTrue(rate > expected * 0.75 && rate < expected * 1.25, String.format(
                "%s spawns in 1 chunk in %.1f, but its divisor says 1 in %d — either the "
                        + "placement hash has stopped mixing or the divisor was retuned "
                        + "without updating this test",
                what, hits == 0 ? Double.POSITIVE_INFINITY : (REGION * REGION) / (double) hits,
                divisor));

        // Spread, not stripes: a hash whose low bits stay correlated with cx or cz still
        // produces the right overall count while piling every feature into part of the map.
        for (int q = 0; q < 4; q++) {
            assertTrue(quadrant[q] > hits / 8, String.format(
                    "%s placement is lopsided — quadrant %d holds %d of %d features, so the "
                            + "hash is correlated with the chunk coordinates instead of "
                            + "mixing them",
                    what, q, quadrant[q], hits));
        }
    }

    /** Placement must vary along both axes, not just one. */
    @Test
    public void placementDependsOnBothChunkAxes() {
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        MegaCavernCarver megaCaverns = new MegaCavernCarver(SEED, heightMap);
        RavineCarver ravines = new RavineCarver(SEED, heightMap);
        SinkholeCarver sinkholes = new SinkholeCarver(SEED, heightMap);

        assertVariesOnBothAxes("worm", worm::hasWormAt);
        assertVariesOnBothAxes("cavern", caverns::hasCavern);
        assertVariesOnBothAxes("megacavern", megaCaverns::hasCavern);
        assertVariesOnBothAxes("ravine", ravines::hasRavine);
        assertVariesOnBothAxes("sinkhole", sinkholes::hasSinkhole);
    }

    private static void assertVariesOnBothAxes(String what, BiPredicate<Integer, Integer> has) {
        int span = 3000;
        boolean variesAlongX = false;
        boolean variesAlongZ = false;
        for (int i = 1; i < span && !(variesAlongX && variesAlongZ); i++) {
            if (has.test(i, 0) != has.test(0, 0)) variesAlongX = true;
            if (has.test(0, i) != has.test(0, 0)) variesAlongZ = true;
        }
        assertTrue(variesAlongX, what + " placement is constant along x — the hash ignores cx");
        assertTrue(variesAlongZ, what + " placement is constant along z — the hash ignores cz");
    }

    /**
     * Changing the seed must move the features. Four of the five carvers do; the fifth is
     * pinned separately below.
     */
    @Test
    public void adifferentSeedPlacesFeaturesDifferently() {
        assertSeedMoves("cavern",
                s -> new CavernCarver(s, heightMap)::hasCavern);
        assertSeedMoves("megacavern",
                s -> new MegaCavernCarver(s, heightMap)::hasCavern);
        assertSeedMoves("ravine",
                s -> new RavineCarver(s, heightMap)::hasRavine);
        assertSeedMoves("sinkhole",
                s -> new SinkholeCarver(s, heightMap)::hasSinkhole);
    }

    private static void assertSeedMoves(String what, SeededPredicate build) {
        BiPredicate<Integer, Integer> a = build.forSeed(SEED);
        int chunks = 0;
        for (long delta : new long[] { 1L, 2L, 7L, 1L << 20 }) {
            BiPredicate<Integer, Integer> b = build.forSeed(SEED + delta);
            int diffs = 0;
            for (int cx = 0; cx < 200; cx++) {
                for (int cz = 0; cz < 200; cz++) {
                    if (a.test(cx, cz) != b.test(cx, cz)) diffs++;
                }
            }
            chunks += diffs;
            assertTrue(diffs > 0, String.format(
                    "seeds %d and %d place %ss identically across 40000 chunks — the seed is "
                            + "not reaching the placement hash",
                    SEED, SEED + delta, what));
        }
        System.out.printf("[carvers] %s placement moved in %d chunk-comparisons across 4 seeds%n",
                what, chunks);
    }

    /**
     * <b>Known defect, pinned deliberately.</b> Worm placement responds to three bits of the
     * seed and ignores the other sixty-one.
     *
     * <p>{@code hasWorm} mixes {@code seed}, {@code cx} and {@code cz} with multiplies, one
     * {@code rotateLeft(h, 23)} and no avalanche step, then takes {@code floorMod(h, 8)}.
     * Eight is a power of two, so that modulo reads three bits of {@code h} and nothing else,
     * and those three come back — through the rotate — from seed bits 41, 42 and 43. Every
     * seed sharing those three bits gets the identical set of worm-bearing chunks, which for
     * ordinary seeds (small integers, hash codes, {@code Random.nextLong()} values that
     * happen to agree there) means eight possible worm layouts for the whole seed space.
     *
     * <p>This is the failure mode the other carvers' javadocs are about: {@code hasRavine}
     * and {@code hasSinkhole} both run a splitmix64 finaliser and say it "is not optional",
     * and {@code hasCavern} gets away without one only because 48 is not a power of two.
     * Worms are the densest feature at 1 chunk in 8, so this is the placement that matters
     * most.
     *
     * <p>Not fixed here because it is not a test's call to make: the one-line finaliser moves
     * every existing world's tunnels. It is cheap when wanted — the native kernel takes worm
     * placement from Java as a precomputed anchor list rather than re-deriving it, so unlike
     * the cavern hashes this one has no {@code generator.cpp} twin to keep in step.
     *
     * <p><b>When it is fixed, this test fails.</b> Delete it and fold worms back into
     * {@link #adifferentSeedPlacesFeaturesDifferently}.
     */
    @Test
    public void wormPlacementStillIgnoresAlmostTheWholeSeed() {
        PerlinWormCarver reference = new PerlinWormCarver(SEED, heightMap);
        List<Integer> liveBits = new ArrayList<>();
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if (placementMoves(reference, new PerlinWormCarver(SEED ^ (1L << bit), heightMap))) {
                liveBits.add(bit);
            }
        }
        System.out.println("[carvers] worm placement responds to seed bits " + liveBits
                + " of 64");
        assertEquals(List.of(41, 42, 43), liveBits,
                "worm placement's dependence on the seed has changed. If the splitmix64 "
                        + "finaliser was added to hasWorm, that is the fix — delete this test "
                        + "and add worms to adifferentSeedPlacesFeaturesDifferently instead.");
    }

    private static boolean placementMoves(PerlinWormCarver a, PerlinWormCarver b) {
        for (int cx = 0; cx < 100; cx++) {
            for (int cz = 0; cz < 100; cz++) {
                if (a.hasWormAt(cx, cz) != b.hasWormAt(cx, cz)) {
                    return true;
                }
            }
        }
        return false;
    }


    @FunctionalInterface
    private interface SeededPredicate {
        BiPredicate<Integer, Integer> forSeed(long seed);
    }

    // ---- purity ------------------------------------------------------------------------

    /**
     * Two carvers built from the same seed must agree block for block, and one carver asked
     * twice must not change its mind. Chunks are generated concurrently and out of order, so
     * anything else shows up as neighbouring chunks that do not line up.
     */
    @Test
    public void masksAreAPureFunctionOfSeedAndChunk() {
        DryHillsHeightMap otherOracle = new DryHillsHeightMap(SEED);
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        PerlinWormCarver wormTwin = new PerlinWormCarver(SEED, otherOracle);
        CavernCarver cavern = new CavernCarver(SEED, heightMap);
        CavernCarver cavernTwin = new CavernCarver(SEED, otherOracle);
        MegaCavernCarver mega = new MegaCavernCarver(SEED, heightMap);
        MegaCavernCarver megaTwin = new MegaCavernCarver(SEED, otherOracle);
        RavineCarver ravine = new RavineCarver(SEED, heightMap);
        RavineCarver ravineTwin = new RavineCarver(SEED, otherOracle);
        SinkholeCarver sinkhole = new SinkholeCarver(SEED, heightMap);
        SinkholeCarver sinkholeTwin = new SinkholeCarver(SEED, otherOracle);

        // Walked in opposite orders so a carver that cached anything between calls sees a
        // different history on each side.
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                int tx = 5 - cx;
                int tz = 5 - cz;
                int[] h = heights(cx, cz);
                int[] w = waterLevels(cx, cz);
                int[] th = heights(tx, tz);
                int[] tw = waterLevels(tx, tz);

                assertEquals(worm.carveMaskForChunk(cx, cz, h, w),
                        wormTwin.carveMaskForChunk(cx, cz, h, w), "worm mask is not pure");
                assertEquals(cavern.buildForChunk(cx, cz, h, w).carveMask,
                        cavernTwin.buildForChunk(cx, cz, h, w).carveMask,
                        "cavern mask is not pure");
                assertEquals(cavern.buildForChunk(cx, cz, h, w).formationMask,
                        cavernTwin.buildForChunk(cx, cz, h, w).formationMask,
                        "cavern formation mask is not pure");
                assertEquals(mega.buildForChunk(cx, cz, h, w).carveMask,
                        megaTwin.buildForChunk(cx, cz, h, w).carveMask,
                        "megacavern mask is not pure");
                assertEquals(ravine.carveMaskForChunk(cx, cz, h, w),
                        ravineTwin.carveMaskForChunk(cx, cz, h, w), "ravine mask is not pure");
                assertEquals(sinkhole.carveMaskForChunk(cx, cz, h, w),
                        sinkholeTwin.carveMaskForChunk(cx, cz, h, w),
                        "sinkhole mask is not pure");

                // Touch the mirrored chunk on the twins only, so the next iteration's
                // comparison is between a carver that has seen it and one that has not.
                wormTwin.carveMaskForChunk(tx, tz, th, tw);
                cavernTwin.buildForChunk(tx, tz, th, tw);
                megaTwin.buildForChunk(tx, tz, th, tw);
                ravineTwin.carveMaskForChunk(tx, tz, th, tw);
                sinkholeTwin.carveMaskForChunk(tx, tz, th, tw);
            }
        }
    }

    /** A different seed must actually carve differently, not just place features differently. */
    @Test
    public void adifferentSeedCarvesDifferently() {
        PerlinWormCarver a = new PerlinWormCarver(SEED, heightMap);
        PerlinWormCarver b = new PerlinWormCarver(SEED + 7, heightMap);
        BitSet different = new BitSet();
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                int[] h = heights(cx, cz);
                int[] w = waterLevels(cx, cz);
                BitSet ma = a.carveMaskForChunk(cx, cz, h, w);
                BitSet mb = b.carveMaskForChunk(cx, cz, h, w);
                ma.xor(mb);
                different.or(ma);
            }
        }
        assertNotEquals(0, different.cardinality(),
                "two seeds carved identical worm tunnels — the seed is not reaching the walk");
    }

    private int[] heights(int cx, int cz) {
        int[] h = new int[CHUNK * CHUNK];
        heightMap.populateChunkHeights(cx, cz, h);
        return h;
    }

    private int[] waterLevels(int cx, int cz) {
        int[] h = new int[CHUNK * CHUNK];
        int[] w = new int[CHUNK * CHUNK];
        heightMap.populateChunkHeights(cx, cz, h, w);
        return w;
    }
}
