package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.VegetationGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The generator's lifetime gate (2026-11-19, the quit crash): closing a {@link NativeCubeGenerator} /
 * {@link NativeTerrain} while other threads are inside native queries - FastLOD workers, the weather relief - must
 * wait for those calls and refuse later ones with {@link TerrainClosedException}, never free the generator under a
 * running call. Before the gate the game died at quit with SIGSEGV in {@code Generator::base} / {@code buildCarve}
 * on a FastLOD worker; without it this test takes the JVM down the same way.
 */
class NativeGeneratorLifetimeTest {

    /** A slow rolling profile (like a tile cache read): every column cube costs ~1 ms. */
    private static boolean slowProfile(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                                       byte[] biome, byte[] riverFlow, short[] carve) {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                int c = lz * 32 + lx;
                long x = cx * 32 + lx, z = cz * 32 + lz;
                height[c] = 80 + (int) ((x * 7 + z * 3) & 15);
                water[c] = NativeCubeGenerator.NO_WATER;
                riverFloor[c] = 0;
                riverRoof[c] = 0;
                biome[c] = (byte) BiomeType.PLAINS.ordinal();
                riverFlow[c] = -1;
                carve[c] = 0;
            }
        }
        return true;
    }

    private static NativeCubeGenerator profileGenerator(NativeCubeGenerator.ColumnProfiles profiles) {
        NativeCubeGenerator.Config c = new NativeCubeGenerator.Config();
        c.seed = 777L;
        c.kind = NativeCubeGenerator.Kind.PROFILE;
        c.profiles = profiles;
        c.cacheColumns = 64;   // small: the workers keep building (and upcalling)
        c.blockIds = NativeCubeGenerator.blockIds(n -> NativeCubeGenerator.Block.valueOf(n).ordinal());
        return NativeCubeGenerator.create(c);
    }

    /** Hammers {@code query} on {@code threads} threads until {@code stop}; counts outcomes. */
    private static final class Hammer {
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean closing = new AtomicBoolean();
        final List<Thread> threads = new ArrayList<>();

        interface Query {
            void run(int thread, long n);
        }

        Hammer(int count, Query query) {
            for (int t = 0; t < count; t++) {
                final int id = t;
                Thread th = new Thread(() -> {
                    long n = 0;
                    while (!stop.get()) {
                        boolean afterClose = closing.get();
                        try {
                            query.run(id, n++);
                            ok.incrementAndGet();
                        } catch (TerrainClosedException e) {
                            refused.incrementAndGet();
                        } catch (Throwable e) {
                            // a failure that is not a refusal is only acceptable once closing began (none expected)
                            unexpected.compareAndSet(null, afterClose ? null : e);
                            if (!afterClose) return;
                        }
                    }
                }, "lifetime-hammer-" + t);
                th.setDaemon(true);
                threads.add(th);
                th.start();
            }
        }

        void awaitOk(int calls) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (ok.get() < calls && unexpected.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(ok.get() >= calls, "the workers never got going: " + unexpected.get());
        }

        void stopAndJoin() throws InterruptedException {
            stop.set(true);
            for (Thread th : threads) {
                th.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(th.isAlive(), th.getName() + " did not stop");
            }
        }
    }

    @Test
    void closeWaitsForCallsInFlightAndRefusesLaterOnes() throws Exception {
        assumeTrue(NativeCubeGenerator.isAvailable(), NativeCubeGenerator.unavailableReason());
        for (int round = 0; round < 3; round++) {
            NativeCubeGenerator g = profileGenerator(NativeGeneratorLifetimeTest::slowProfile);
            final int r = round;
            Hammer h = new Hammer(8, (t, n) -> {
                long x0 = ((long) t * 100_000 + n * 96) * (r + 1), z0 = (n % 7) * 512 - 1024;
                switch ((int) (n % 3)) {
                    case 0 -> g.sampleColumns(x0, z0, 16, 6, true, new int[256], new int[256], new short[256],
                        new int[256]);
                    case 1 -> g.queryColumn(x0 + 5, z0 + 9);
                    default -> g.sampleColumns(x0, z0, 16, 6, false, new int[256], null, null, null);
                }
            });
            h.awaitOk(4 + 4 * round);                   // close while the workers are busy, not before they start
            h.closing.set(true);
            g.close();                                   // waits for the calls in flight, then frees
            assertEquals(0, g.callsInFlight(), "close returned with a call still inside the generator");
            Thread.sleep(50);
            h.stopAndJoin();
            assertNull(h.unexpected.get(), () -> "a query failed with something other than a refusal: "
                + h.unexpected.get());
            assertTrue(h.ok.get() > 0, "no query finished before close");
            assertTrue(h.refused.get() > 0, "no query was refused after close");
            assertThrows(TerrainClosedException.class, () -> g.queryColumn(0, 0));
            assertThrows(TerrainClosedException.class,
                () -> g.sampleColumns(0, 0, 2, 1, false, new int[4], null, null, null));
            g.close();                                   // idempotent
        }
    }

    @Test
    void closeNeverFreesUnderABlockedProfileUpcall() throws Exception {
        assumeTrue(NativeCubeGenerator.isAvailable(), NativeCubeGenerator.unavailableReason());
        CountDownLatch inUpcall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        NativeCubeGenerator g = profileGenerator((cx, cz, height, water, floor, roof, biome, flow, carve) -> {
            if (first.getAndSet(false)) {
                inUpcall.countDown();
                try {
                    release.await();                     // a tile read blocked on the service
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return slowProfile(cx, cz, height, water, floor, roof, biome, flow, carve);
        });
        AtomicReference<Throwable> queryOutcome = new AtomicReference<>();
        Thread query = new Thread(() -> {
            try {
                g.queryColumn(40_000, 40_000);
            } catch (Throwable t) {
                queryOutcome.set(t);
            }
        }, "lifetime-blocked-query");
        query.start();
        assertTrue(inUpcall.await(30, TimeUnit.SECONDS), "the query never reached its profile upcall");

        Thread closer = new Thread(g::close, "lifetime-closer");
        closer.start();
        closer.join(400);
        assertTrue(closer.isAlive(), "close() returned while a call was still inside the generator");
        assertTrue(g.isClosing());
        assertThrows(TerrainClosedException.class, () -> g.queryColumn(0, 0), "a new call during close");

        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(closer.isAlive(), "close() did not finish after the call in flight returned");
        query.join(TimeUnit.SECONDS.toMillis(30));
        Throwable t = queryOutcome.get();
        assertTrue(t == null || t instanceof TerrainClosedException,
            "the call in flight either finished or was refused as closing, got " + t);
        assertEquals(0, g.callsInFlight());
    }

    @Test
    void standardTerrainClosedUnderSamplersRefusesQuietly() throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), NativeCubeGenerator.unavailableReason());
        NativeTerrain terrain = NativeTerrain.create(4242L, TerrainGeneratorType.STANDARD, null, null);
        Hammer h = new Hammer(6, (t, n) -> {
            int x0 = (int) (t * 50_000 + n * 64), z0 = (int) ((n % 9) * 256);
            int count = 8;
            switch ((int) (n % 3)) {
                case 0 -> terrain.sampleColumns(x0, z0, count, 4, new int[count * count], new int[count * count],
                    new BlockType[count * count], new VegetationGenerator.TreeSample[count * count]);
                case 1 -> terrain.getFinalTerrainHeightAt(x0, z0);
                default -> terrain.sampleCellOpenings(x0, z0, 2, 8, new int[] {200, 200, 200, 200}, new int[4],
                    new byte[4], new int[4]);
            }
        });
        h.awaitOk(12);
        h.closing.set(true);
        terrain.close();
        assertTrue(terrain.isClosing());
        Thread.sleep(50);
        h.stopAndJoin();
        assertNull(h.unexpected.get(), () -> "a query failed with something other than a refusal: "
            + h.unexpected.get());
        assertTrue(h.ok.get() > 0);
        assertTrue(h.refused.get() > 0);
        TerrainClosedException e = assertThrows(TerrainClosedException.class, () -> terrain.getBiomeAt(1, 2));
        assertTrue(TerrainClosedException.isCause(new RuntimeException(e)));
    }
}
