package com.openmason.engine.diagnostics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Categorized GPU-memory accountant. Subsystems call {@link #track} when they
 * allocate a GL object and {@link #untrack} when they free it. The debug
 * overlay reads {@link #snapshot} to show who owns the VRAM.
 *
 * <p>Bytes are best-effort estimates of the GPU-side allocation (e.g. VBO
 * size, texture bytes). The tracker does not query the driver — it only sums
 * what callers report. Untracked allocations are invisible.
 *
 * <p>Thread-safe: counters are atomic; counts can be read from any thread.
 */
public final class GpuMemoryTracker {

    /** What kind of GPU resource is being charged. Add as new systems are wired in. */
    public enum Category {
        /** Active chunk meshes uploaded to GPU and currently bound to a renderable. */
        CHUNK_MESH,
        /** Buffers sitting idle in MmsBufferPool (allocated, not in use). */
        BUFFER_POOL_IDLE,
        /** Texture atlas + mipmaps. */
        TEXTURE_ATLAS,
        /** Entity/model meshes (cow, drops, etc.). */
        ENTITY_MESH,
        /** Held-item / first-person arm geometry. */
        PLAYER_GEOMETRY,
        /** UI quads, font atlases, anything not otherwise categorized. */
        OTHER
    }

    private static final GpuMemoryTracker INSTANCE = new GpuMemoryTracker();

    private final Map<Category, AtomicLong> bytes = new EnumMap<>(Category.class);
    private final Map<Category, AtomicLong> counts = new EnumMap<>(Category.class);

    private GpuMemoryTracker() {
        for (Category c : Category.values()) {
            bytes.put(c, new AtomicLong(0));
            counts.put(c, new AtomicLong(0));
        }
    }

    public static GpuMemoryTracker getInstance() {
        return INSTANCE;
    }

    /** Records that {@code byteCount} GPU bytes were allocated under {@code category}. */
    public void track(Category category, long byteCount) {
        if (byteCount <= 0) return;
        bytes.get(category).addAndGet(byteCount);
        counts.get(category).incrementAndGet();
    }

    /** Records that {@code byteCount} GPU bytes were freed from {@code category}. */
    public void untrack(Category category, long byteCount) {
        if (byteCount <= 0) return;
        bytes.get(category).addAndGet(-byteCount);
        counts.get(category).decrementAndGet();
    }

    /** Moves {@code byteCount} bytes from one category to another (e.g. active mesh → idle pool). */
    public void transfer(Category from, Category to, long byteCount) {
        untrack(from, byteCount);
        track(to, byteCount);
    }

    /** Returns the current byte count for one category. */
    public long getBytes(Category category) {
        return bytes.get(category).get();
    }

    /** Returns the current allocation count for one category. */
    public long getCount(Category category) {
        return counts.get(category).get();
    }

    /** Returns total tracked bytes across every category. */
    public long getTotalBytes() {
        long sum = 0;
        for (AtomicLong v : bytes.values()) sum += v.get();
        return sum;
    }

    /** Immutable point-in-time snapshot for display. */
    public Snapshot snapshot() {
        EnumMap<Category, Long> b = new EnumMap<>(Category.class);
        EnumMap<Category, Long> c = new EnumMap<>(Category.class);
        for (Category cat : Category.values()) {
            b.put(cat, bytes.get(cat).get());
            c.put(cat, counts.get(cat).get());
        }
        return new Snapshot(b, c);
    }

    public static final class Snapshot {
        private final Map<Category, Long> bytes;
        private final Map<Category, Long> counts;

        Snapshot(Map<Category, Long> bytes, Map<Category, Long> counts) {
            this.bytes = bytes;
            this.counts = counts;
        }

        public long bytesOf(Category c) { return bytes.getOrDefault(c, 0L); }
        public long countOf(Category c) { return counts.getOrDefault(c, 0L); }

        public long totalBytes() {
            long sum = 0;
            for (Long v : bytes.values()) sum += v;
            return sum;
        }
    }
}
