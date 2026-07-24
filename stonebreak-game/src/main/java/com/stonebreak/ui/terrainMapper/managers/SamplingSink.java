package com.stonebreak.ui.terrainMapper.managers;

/**
 * The sampler's channel back to whoever asked for the work. Exists so
 * {@link TerrainPreviewSampler} can be interrupted and can hand over rows as they finish without
 * knowing anything about threads, queues, or what is currently on screen — that all stays in
 * {@link TerrainPreviewLoader}.
 *
 * <p>Both methods are called from the sampling thread. {@link #abandoned()} is polled inside the
 * innermost loops, so it must be cheap — a field read, not a lock.
 */
public interface SamplingSink {

    /**
     * True once the result of this sampling can no longer be used (a newer viewport, a different
     * visualizer, or a screen reset). The sampler stops as soon as it notices and returns null.
     *
     * <p>Matters because one pass over a zoomed-out viewport is thousands of diffusion tiles on a
     * single-GPU queue — tens of minutes. Without this, selecting a different visualizer would sit
     * behind the whole of it.
     */
    boolean abandoned();

    /**
     * Offers the rows finished so far as a drawable snapshot. Called once per row band, so a long
     * pass fills the map in from the top instead of showing nothing until the end.
     *
     * <p>The slice shares its {@code raw} array with the passes that follow it: subsequent bands
     * only ever write rows past the slice's {@code sampleH}, so nothing already published is
     * mutated afterwards.
     */
    void partial(PreviewSnapshot slice);
}
