package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.components.TerrainMapViewport;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.PreviewSampleStore;
import com.stonebreak.ui.terrainMapper.visualization.PreviewSource;
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
 * <p><b>Two geometries.</b> The pan, zoom and pixel fields describe the <em>view</em> that asked.
 * What actually gets sampled is a world-aligned <em>lattice</em> covering that view: points
 * {@link #spacing()} blocks apart, a power of two chosen from the zoom. Aligning to the world
 * rather than to the screen is what makes sampled values reusable — the same ground seen again,
 * at a slightly different zoom or after a pan, lands on exactly the same points, which
 * {@link PreviewSampleStore} already holds.
 *
 * @param registry kept so the worker can call {@link VisualizerRegistry#ensureServices()} —
 *                 the blocking terrain-service boot — off the render thread
 * @param widthPx/heightPx the visible map rect
 * @param marginPx extra pixels sampled beyond every edge of the visible rect, so a pan lands on
 *                 terrain that is already there. Zero samples exactly what is visible.
 * @param source   cached terrain values for the seed {@code visualizer} belongs to, or null to
 *                 sample the visualizer directly
 */
public record SampleRequest(
        NoiseVisualizer visualizer,
        VisualizerRegistry registry,
        int widthPx,
        int heightPx,
        int step,
        float panX,
        float panZ,
        float zoom,
        int marginPx,
        PreviewSource source
) {

    private static final float SQRT_2 = (float) Math.sqrt(2.0);

    /** A request for exactly the visible rect, with no preload margin and no cache. */
    public SampleRequest(NoiseVisualizer visualizer, VisualizerRegistry registry,
                         int widthPx, int heightPx, int step,
                         float panX, float panZ, float zoom) {
        this(visualizer, registry, widthPx, heightPx, step, panX, panZ, zoom, 0, null);
    }

    /** Width of the view sampled: the visible rect plus the margin on both sides. */
    public int sampledWidthPx() {
        return widthPx + 2 * marginPx;
    }

    public int sampledHeightPx() {
        return heightPx + 2 * marginPx;
    }

    /** The same request without its margin — the part the user can currently see. */
    public SampleRequest core() {
        return marginPx == 0 ? this
                : new SampleRequest(visualizer, registry, widthPx, heightPx, step, panX, panZ, zoom, 0, source);
    }

    /**
     * View-to-world along X, anchored on the viewport center. Mirrors
     * {@link TerrainMapViewport#screenToWorldX} — that class owns the live, mutable transform;
     * this is the frozen copy of it that travels to the worker thread. {@code screenX} is
     * relative to the sampled view's left edge, which is the map rect's left edge less the margin.
     */
    public float worldXAt(float screenX) {
        return panX + (screenX - sampledWidthPx() * 0.5f) / zoom;
    }

    public float worldZAt(float screenZ) {
        return panZ + (screenZ - sampledHeightPx() * 0.5f) / zoom;
    }

    /** World blocks the view wants each sample to stand for. */
    public float blocksPerSample() {
        return step / zoom;
    }

    /**
     * Blocks between lattice points: the power of two nearest {@link #blocksPerSample()} on a log
     * scale, so a view gets between half and twice the detail it asked for — never a whole octave
     * off — clamped to what {@link PreviewSampleStore} keeps.
     */
    public int spacing() {
        float wanted = blocksPerSample();
        int spacing = 1;
        while (spacing < PreviewSampleStore.MAX_SPACING && spacing * SQRT_2 <= wanted) {
            spacing <<= 1;
        }
        return spacing;
    }

    /** World X of the first lattice column: the lattice point at or west of the view's left edge. */
    public int latticeOriginX() {
        return Math.floorDiv((int) Math.floor(worldXAt(0f)), spacing()) * spacing();
    }

    public int latticeOriginZ() {
        return Math.floorDiv((int) Math.floor(worldZAt(0f)), spacing()) * spacing();
    }

    /** Lattice columns needed to reach the view's right edge. */
    public int latticeColumns() {
        return Math.max(1, (int) Math.ceil((worldXAt(sampledWidthPx()) - latticeOriginX()) / spacing()));
    }

    public int latticeRows() {
        return Math.max(1, (int) Math.ceil((worldZAt(sampledHeightPx()) - latticeOriginZ()) / spacing()));
    }
}
