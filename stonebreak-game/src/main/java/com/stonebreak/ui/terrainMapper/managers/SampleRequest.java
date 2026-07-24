package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.components.TerrainMapViewport;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerRegistry;

/**
 * Everything the loader's worker thread needs to produce one preview image: an immutable
 * copy of the render-thread state at the moment the request was made. Nothing here is read
 * back off the live UI objects, so the worker can never observe a half-updated viewport.
 *
 * <p>Doubles as the cache key — the record's generated {@code equals} covers geometry,
 * viewport and visualizer identity, which is exactly "would this sample differ from the last
 * one". A seed change is caught through {@code visualizer}: {@link VisualizerRegistry#rebuild}
 * installs fresh visualizer instances, so the reference comparison changes with the seed.
 *
 * @param registry kept so the worker can call {@link VisualizerRegistry#ensureServices()} —
 *                 the blocking terrain-service boot — off the render thread
 */
public record SampleRequest(
        NoiseVisualizer visualizer,
        VisualizerRegistry registry,
        int widthPx,
        int heightPx,
        int step,
        float panX,
        float panZ,
        float zoom
) {

    /**
     * Screen-to-world along X, anchored on the viewport center. Mirrors
     * {@link TerrainMapViewport#screenToWorldX} — that class owns the live, mutable transform;
     * this is the frozen copy of it that travels to the worker thread. {@code screenX} is
     * relative to the map rect, not the window.
     */
    public float worldXAt(float screenX) {
        return panX + (screenX - widthPx * 0.5f) / zoom;
    }

    public float worldZAt(float screenZ) {
        return panZ + (screenZ - heightPx * 0.5f) / zoom;
    }

    /** World blocks covered by one sample cell — how much detail a single pixel has to stand for. */
    public float blocksPerSample() {
        return step / zoom;
    }
}
