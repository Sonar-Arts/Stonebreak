package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.world.generation.TerrainGeneratorType;

import java.util.List;
import java.util.Map;

/**
 * Binds the selected generator's {@link VisualizerKind}s to concrete {@link NoiseVisualizer}s
 * for one seed. Nothing is bound until {@link #selectGenerator} is called. Rebuild with
 * {@link #rebuild(long)} when the seed changes; everything downstream (cache, renderer) reads
 * through this registry so a single rebuild swaps all channels atomically.
 */
public final class VisualizerRegistry {

    private final StandardPreview standard = new StandardPreview();
    private final DiffusionPreview diffusion = new DiffusionPreview();

    /**
     * Outlives {@link #rebuild}: values are keyed by seed, so switching back to a seed already
     * explored shows its terrain again without resampling. Emptied only by {@link #clearPreviewData()}.
     * Only Diffusion caches (Standard is cheaper to resample than to store), so the key needs
     * no generator in it.
     */
    private final PreviewSampleStore previewStore =
            new PreviewSampleStore(TerrainMapperConfig.PREVIEW_CACHE_BUDGET_BYTES);
    private TerrainGeneratorType generatorType;
    private GeneratorPreview generator;
    private Map<VisualizerKind, NoiseVisualizer> visualizers = Map.of();
    private PreviewSource previewSource;
    /** What the current visualizers read through, so {@link #rebuild} can release it instead of leaking its threads. */
    private AutoCloseable resources;
    private long seed;

    public VisualizerRegistry(long seed) {
        this.seed = seed;
    }

    public long seed() { return seed; }

    /** The selected generator, or null before the player has picked one. */
    public TerrainGeneratorType generatorType() { return generatorType; }

    /** The selected generator's modes in sidebar order; empty before one is picked. */
    public List<VisualizerKind> modes() {
        return generator == null ? List.of() : generator.modes();
    }

    public NoiseVisualizer get(VisualizerKind kind) {
        return kind == null ? null : visualizers.get(kind);
    }

    /** Cached terrain values for the current seed, or null when the generator caches nothing. */
    public PreviewSource previewSource() {
        return previewSource;
    }

    /**
     * Switches generator and rebuilds against the current seed. Leaving Diffusion stops its
     * services; picking it does not start them — the first preview job does, off the render
     * thread (see {@link #ensureServices()}).
     */
    public void selectGenerator(TerrainGeneratorType type) {
        if (type == generatorType) return;
        if (generatorType == TerrainGeneratorType.DIFFUSION) {
            diffusion.stopServices();
        }
        generatorType = type;
        generator = previewFor(type);
        if (type == TerrainGeneratorType.DIFFUSION) {
            diffusion.activate();
        }
        rebuild(seed);
    }

    /**
     * Forgets the selection without touching the services — for leaving the screen into the
     * world just created, which may be about to use them.
     */
    public void clearGenerator() {
        generatorType = null;
        generator = null;
        rebuild(seed);
    }

    /** Stops the Diffusion services if this screen started them. Leaves the selection alone. */
    public void stopServices() {
        if (generatorType == TerrainGeneratorType.DIFFUSION) {
            diffusion.stopServices();
        }
    }

    /** Forgets every sampled value, for every seed. Called when the mapper is closed. */
    public void clearPreviewData() {
        previewStore.clear();
    }

    /**
     * Brings up whatever the selected generator's previews read from, blocking until it is
     * healthy — for Diffusion, the local CUDA model server and bridge (~a minute cold). Called by
     * {@code TerrainPreviewLoader}'s worker as the first step of each sampling job, never from
     * the render path. A cheap no-op once running, and always for Standard.
     */
    public void ensureServices() {
        GeneratorPreview current = generator;
        if (current != null) {
            current.startServices(seed);
        }
    }

    /** Rebuild every visualizer against a fresh seed. Does not touch the services — see {@link #ensureServices()}. */
    public void rebuild(long newSeed) {
        // The outgoing preview owns bridge clients (HTTP + retry executor); dropping the
        // reference alone leaked them every seed change.
        closeResources();
        this.seed = newSeed;
        if (generator == null) {
            visualizers = Map.of();
            previewSource = null;
            return;
        }
        GeneratorPreview.Built built = generator.build(newSeed, previewStore);
        visualizers = built.visualizers();
        previewSource = built.source();
        resources = built.resources();
    }

    private GeneratorPreview previewFor(TerrainGeneratorType type) {
        if (type == null) return null;
        return switch (type) {
            case STANDARD -> standard;
            case DIFFUSION -> diffusion;
        };
    }

    /** Releases the current resources. Safe to call more than once. */
    private void closeResources() {
        if (resources != null) {
            try {
                resources.close();
            } catch (Exception e) {
                System.err.println("[VisualizerRegistry] preview resources close failed: " + e);
            }
        }
        resources = null;
    }
}
