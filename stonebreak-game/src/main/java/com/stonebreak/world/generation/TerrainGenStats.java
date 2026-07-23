package com.stonebreak.world.generation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide terrain-generation timing counters, fed by
 * {@link TerrainGenerationSystem#generateTerrainOnly} and surfaced on the F3
 * debug overlay (the terrain-side sibling of the mesh pipeline's
 * {@code MmsStatistics}, which stays mesh-scoped).
 *
 * <p>Static because the overlay has no path to a {@code TerrainGenerationSystem}
 * instance; multiple worlds in one process (integrated server + render view)
 * share the counters, which is fine for a diagnostic average.
 */
public final class TerrainGenStats {

    /** Which generation path produced a chunk. */
    public enum Mode {
        /** Fused native kernel ({@code ck_generate_chunk}). */
        FUSED,
        /** Native noise/carver kernels with the Java block-fill loop. */
        MIXED,
        /** Pure-Java backend. */
        JAVA
    }

    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong FUSED = new AtomicLong();
    private static final AtomicLong MIXED = new AtomicLong();
    private static final AtomicLong JAVA = new AtomicLong();

    private TerrainGenStats() {
    }

    public static void record(long nanos, Mode mode) {
        CHUNKS.incrementAndGet();
        TOTAL_NANOS.addAndGet(nanos);
        switch (mode) {
            case FUSED -> FUSED.incrementAndGet();
            case MIXED -> MIXED.incrementAndGet();
            case JAVA -> JAVA.incrementAndGet();
        }
    }

    public static long chunkCount() {
        return CHUNKS.get();
    }

    public static double averageMicros() {
        long chunks = CHUNKS.get();
        return chunks == 0 ? 0.0 : TOTAL_NANOS.get() / 1000.0 / chunks;
    }

    /** Short path summary for the overlay, e.g. "fused" or "fused+java". */
    public static String modeSummary() {
        StringBuilder sb = new StringBuilder();
        if (FUSED.get() > 0) sb.append("fused");
        if (MIXED.get() > 0) sb.append(sb.isEmpty() ? "" : "+").append("mixed");
        if (JAVA.get() > 0) sb.append(sb.isEmpty() ? "" : "+").append("java");
        return sb.isEmpty() ? "none" : sb.toString();
    }
}
