package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hammer test for the palette's concurrency contract: readers are lock-free
 * and must never observe a torn state (exception, or a block that was never
 * written) while a writer drives structural transitions — uniform inflation,
 * palette growth past 256 entries, and byte→short widening.
 *
 * <p>Mirrors production: mesh worker threads read via {@code get} while the
 * main thread writes via {@code set}.
 */
class CcoPaletteConcurrencyTest {

    private static final int CELLS_PER_LAYER = 16 * 16;
    private static final int VOLUME = CELLS_PER_LAYER * CcoSectionIndexing.SECTION_HEIGHT;
    private static final int READER_THREADS = 4;
    private static final int WRITE_CYCLES = 400; // > 256 forces the short-widening transition

    @Test
    void readersNeverSeeTornStateDuringStructuralWrites() throws InterruptedException {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());

        // Everything the writer will ever store, plus the initial fill.
        Set<IBlockType> everWritten = new HashSet<>();
        everWritten.add(TestBlocks.air());
        for (int i = 1; i <= WRITE_CYCLES; i++) {
            everWritten.add(TestBlocks.block(i));
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch readersStarted = new CountDownLatch(READER_THREADS);

        Thread[] readers = new Thread[READER_THREADS];
        for (int t = 0; t < READER_THREADS; t++) {
            readers[t] = new Thread(() -> {
                readersStarted.countDown();
                int idx = 0;
                try {
                    while (!stop.get()) {
                        IBlockType observed = section.get(idx);
                        if (observed == null || !everWritten.contains(observed)) {
                            failure.compareAndSet(null, new AssertionError(
                                "Observed block that was never written: " + observed));
                            return;
                        }
                        idx = (idx + 7) % VOLUME;
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "palette-reader-" + t);
            readers[t].start();
        }

        readersStarted.await();

        // Writer: cycles through 400 distinct blocks across the section,
        // driving palette growth on nearly every write early on.
        try {
            for (int cycle = 1; cycle <= WRITE_CYCLES && failure.get() == null; cycle++) {
                IBlockType block = TestBlocks.block(cycle);
                for (int i = cycle % 13; i < VOLUME; i += 13) {
                    section.set(i, block);
                }
            }
        } finally {
            stop.set(true);
            for (Thread reader : readers) {
                reader.join(5000);
                assertTrue(!reader.isAlive(), "Reader thread did not terminate");
            }
        }

        assertNull(failure.get(), () -> "Concurrent failure: " + failure.get());
    }
}
