package com.stonebreak.world.generation.water;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dedup contract both water caches lean on. The thing being pinned is not
 * "the right answer" — every loader here is a pure function — but "the answer
 * was computed once", because the loaders in production are a cold GPU
 * elevation fetch and a basin solve, and paying for either a dozen times over
 * is what a slow world load is made of.
 */
class SingleFlightTest {

    private static final int THREADS = 8;

    /** Run {@code body} on {@link #THREADS} threads released together. */
    private static void inParallel(Runnable body) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    body.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "a caller never returned");
    }

    @Test
    void oneCallerComputesAndTheRestWaitForIt() throws Exception {
        SingleFlight<String, Integer> flight = new SingleFlight<>();
        AtomicInteger runs = new AtomicInteger();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();

        inParallel(() -> seen.add(flight.compute("region", k -> {
            int n = runs.incrementAndGet();
            // Held open so every other caller is inside compute() while this
            // one is still working — the shape of a world load asking a dozen
            // chunk threads for the same region at once.
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return n;
        })));

        assertEquals(1, runs.get(), "the loader ran more than once for one key");
        assertEquals(Set.of(1), seen, "every caller must get the one computed value");
    }

    @Test
    void distinctKeysRunConcurrently() throws Exception {
        SingleFlight<Integer, Integer> flight = new SingleFlight<>();
        // Each key's loader waits for every other key to have started, so this
        // deadlocks (and times out) if one key's work excluded another's.
        CountDownLatch arrived = new CountDownLatch(THREADS);
        AtomicInteger next = new AtomicInteger();

        inParallel(() -> flight.compute(next.getAndIncrement(), k -> {
            arrived.countDown();
            try {
                assertTrue(arrived.await(10, TimeUnit.SECONDS), "keys are serialized");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return k;
        }));

        assertEquals(0, arrived.getCount());
    }

    @Test
    void aFailureIsNotCachedAndReachesEveryWaiter() throws Exception {
        SingleFlight<String, Integer> flight = new SingleFlight<>();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        inParallel(() -> {
            try {
                flight.compute("doomed", k -> {
                    runs.incrementAndGet();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // The shape CoarseDem throws in when the bridge is down.
                    throw new IllegalStateException("bridge is down");
                });
            } catch (IllegalStateException e) {
                // Thrown in its original shape, not wrapped in a
                // CompletionException a caller's catch clause would miss.
                failures.incrementAndGet();
            }
        });

        assertEquals(1, runs.get());
        assertEquals(THREADS, failures.get(), "every waiter must see the owner's failure");

        // Nothing was retained, so the next caller retries rather than
        // inheriting a permanent failure from one unlucky moment.
        assertEquals(7, flight.compute("doomed", k -> 7));
        assertThrows(IllegalStateException.class, () -> flight.compute("other", k -> {
            throw new IllegalStateException("still down");
        }));
    }
}
