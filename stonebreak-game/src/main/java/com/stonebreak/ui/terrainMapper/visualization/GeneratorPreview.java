package com.stonebreak.ui.terrainMapper.visualization;

import java.util.List;
import java.util.Map;

/** One terrain generator's side of the mapper: the modes it offers and how to build them. */
interface GeneratorPreview {

    /** The visualizers for one seed, and whatever must be closed when they are replaced. */
    record Built(Map<VisualizerKind, NoiseVisualizer> visualizers, PreviewSource source,
                 AutoCloseable resources) {}

    /** Sidebar order. */
    List<VisualizerKind> modes();

    /** Cheap — no I/O and no services; see {@link #startServices}. */
    Built build(long seed, PreviewSampleStore store);

    /** Blocks until anything the previews read from is up. Worker thread only. */
    default void startServices(long seed) {
    }

    /** The player moved off this generator: release anything {@link #startServices} started. */
    default void stopServices() {
    }
}
