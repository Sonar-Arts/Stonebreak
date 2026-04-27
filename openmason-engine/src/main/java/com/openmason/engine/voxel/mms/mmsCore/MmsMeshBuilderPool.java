package com.openmason.engine.voxel.mms.mmsCore;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe pool of {@link MmsMeshBuilder} instances.
 *
 * <p>Chunk meshing allocates eight {@code float[]} + one {@code int[]} per
 * builder — roughly 640 KB at the default 16 384-vertex capacity. Without
 * pooling, every chunk mesh build allocates and discards that whole
 * structure, dumping ~hundreds of MB/sec into the young generation when a
 * region is loaded. The builder already supports {@link MmsMeshBuilder#reset}
 * so the work here is purely lifecycle.
 *
 * <p>The async mesh pipeline checks a builder out on a worker thread, fills it,
 * the GL thread uploads the data, and the same thread returns the builder.
 * Acquire/release crosses threads, hence the lock-free {@link ConcurrentLinkedDeque}.
 *
 * <p>The pool is bounded so a transient burst of mesh builds doesn't pin
 * memory permanently. Builders beyond the cap are dropped on return — their
 * backing arrays become eligible for GC like any other unreachable object.
 *
 * @since MMS 1.2
 */
public final class MmsMeshBuilderPool {

    /** Hard cap on retained builders. Each ≈640 KB at default capacity. */
    private static final int MAX_POOLED = 32;

    private static final MmsMeshBuilderPool INSTANCE = new MmsMeshBuilderPool();

    private final ConcurrentLinkedDeque<MmsMeshBuilder> pool = new ConcurrentLinkedDeque<>();
    private final AtomicInteger pooled = new AtomicInteger(0);
    private final AtomicInteger acquires = new AtomicInteger(0);
    private final AtomicInteger reuses = new AtomicInteger(0);

    private MmsMeshBuilderPool() {}

    public static MmsMeshBuilderPool getInstance() {
        return INSTANCE;
    }

    /**
     * Acquires a reset, ready-to-fill builder. If the pool is empty, a fresh
     * builder is allocated at the requested capacity. Pooled builders ignore
     * the capacity hint — their arrays grow on demand and are retained.
     *
     * @param estimatedVertexCount initial capacity hint for fresh allocations
     * @return a builder with logical size 0
     */
    public MmsMeshBuilder acquire(int estimatedVertexCount) {
        acquires.incrementAndGet();
        MmsMeshBuilder b = pool.pollFirst();
        if (b != null) {
            pooled.decrementAndGet();
            reuses.incrementAndGet();
            return b.reset();
        }
        return MmsMeshBuilder.createWithCapacity(estimatedVertexCount);
    }

    /**
     * Returns a builder for reuse. Drops it on the floor if the pool is full.
     * Safe to call from the GL thread once mesh upload has consumed the data.
     */
    public void release(MmsMeshBuilder builder) {
        if (builder == null) return;
        // Pre-reset so the next acquirer sees a clean state; also drops any
        // dangling face state if release fired during an aborted build.
        builder.reset();
        if (pooled.get() >= MAX_POOLED) {
            return;
        }
        pool.offerFirst(builder);
        pooled.incrementAndGet();
    }

    /** Hard reset — drops every pooled builder. Call on world unload. */
    public void clear() {
        pool.clear();
        pooled.set(0);
    }

    public int getPooledCount()  { return pooled.get(); }
    public int getAcquireCount() { return acquires.get(); }
    public int getReuseCount()   { return reuses.get(); }

    /** Hit-rate as a percentage; 0 if no acquires yet. */
    public double getReuseRate() {
        int a = acquires.get();
        return a == 0 ? 0.0 : (double) reuses.get() / a * 100.0;
    }
}
