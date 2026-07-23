package com.stonebreak.world.chunk.utils;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide per-stage counters for the chunk streaming pipeline
 * (generate → feature-populate → stream → install → mesh → upload), surfaced
 * on the F3 overlay so each throttle/backpressure lever's effect is visible
 * and future pacing regressions are measurable. Totals only — the overlay
 * derives per-second rates from frame-to-frame deltas.
 */
public final class ChunkPipelineStats {

    /** Chunks fully terrain-generated (server side). */
    public static final LongAdder GENERATED = new LongAdder();
    /** Chunks feature-populated (server tick drain). */
    public static final LongAdder POPULATED = new LongAdder();
    /** Chunk snapshots streamed to clients (server side). */
    public static final LongAdder STREAMED = new LongAdder();
    /** Network chunk payloads installed into the render world (client side). */
    public static final LongAdder INSTALLED = new LongAdder();
    /** Mesh builds completed (worker side; includes rebuilds). */
    public static final LongAdder MESHED = new LongAdder();
    /** Mesh GL uploads applied (render thread; includes rebuilds). */
    public static final LongAdder UPLOADED = new LongAdder();

    private ChunkPipelineStats() {
    }
}
