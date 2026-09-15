package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerRegistry;

/**
 * Decides which {@link SampleRequest} the map should be asking for this frame.
 *
 * <p>The naive answer — the live viewport, every frame — is what used to make panning slow: a
 * drag changes the pan every frame, every changed request abandons the pass in flight, and so
 * no pass ever finished while the map was moving. This keeps an <em>anchor</em> instead: a
 * request sampled with a preload margin around the view, held while the view stays well inside
 * that margin. Small pans therefore change nothing and land on terrain the anchor already
 * sampled; only a pan approaching the preloaded edge re-anchors, and the new anchor's visible
 * core sits over tiles the old one has just fetched.
 *
 * <p>Pure state, no threads and no Skija, so it is called from the render thread and tested
 * without a terrain bridge.
 */
public final class PreviewRequestPlanner {

    private SampleRequest anchor;

    /**
     * The request for the current view: the anchor if it still serves, otherwise a new anchor
     * centered on the current pan.
     *
     * @param desiredStep sample step the view wants now — coarse while interacting, fine at rest
     */
    public SampleRequest plan(NoiseVisualizer visualizer, VisualizerRegistry registry,
                              int widthPx, int heightPx,
                              float panX, float panZ, float zoom, int desiredStep) {
        if (visualizer == null) return null;
        if (!stillServes(visualizer, registry, widthPx, heightPx, panX, panZ, zoom, desiredStep)) {
            // The source is read in the same frame as the visualizer, so the two always belong to
            // the same seed: a rebuild replaces both on this thread.
            anchor = new SampleRequest(visualizer, registry, widthPx, heightPx, desiredStep,
                    panX, panZ, zoom, marginFor(widthPx, heightPx),
                    registry == null ? null : registry.previewSource());
        }
        return anchor;
    }

    public void reset() {
        anchor = null;
    }

    static int marginFor(int widthPx, int heightPx) {
        return Math.round(TerrainMapperConfig.PRELOAD_MARGIN_FRACTION * Math.min(widthPx, heightPx));
    }

    private boolean stillServes(NoiseVisualizer visualizer, VisualizerRegistry registry,
                                int widthPx, int heightPx,
                                float panX, float panZ, float zoom, int desiredStep) {
        if (anchor == null) return false;
        if (anchor.visualizer() != visualizer || anchor.registry() != registry) return false;
        if (anchor.widthPx() != widthPx || anchor.heightPx() != heightPx) return false;
        if (anchor.zoom() != zoom) return false;
        // Coarser than wanted: the interaction that asked for it is over, so upgrade. The reverse
        // is deliberately not a reason — starting a drag must not throw away a finished fine map
        // just to resample the same ground coarsely.
        if (anchor.step() > desiredStep) return false;
        // Half the margin of drift, not all of it: re-anchoring at the very edge would expose
        // blank map the moment the pan carried on. A zero margin re-anchors on any movement at
        // all, exactly as the map behaved before preloading existed.
        float drift = anchor.marginPx() * 0.5f;
        return Math.abs(panX - anchor.panX()) * zoom <= drift
                && Math.abs(panZ - anchor.panZ()) * zoom <= drift;
    }
}
