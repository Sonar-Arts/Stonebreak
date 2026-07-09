package com.openmason.main.systems.scripting.doc;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.layers.LayerStackSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CanvasSurface} test fake over a real {@link LayerManager}. Mirrors
 * {@code LiveCanvasSurface}'s contract: {@link #beginMutation()} captures the
 * before-snapshot lazily on the first call, {@link #rollback()} restores it.
 * {@link #exportPng} records the requested path instead of touching the
 * filesystem (set {@link #failExports()} to simulate a write failure).
 */
public final class FakeCanvasSurface implements CanvasSurface {

    private final LayerManager layers;
    private LayerStackSnapshot before;
    private int modifiedCount;
    private boolean exportSucceeds = true;
    private final List<String> exportedPaths = new ArrayList<>();

    public FakeCanvasSurface(int width, int height) {
        this.layers = new LayerManager(width, height);
    }

    @Override
    public LayerManager layers() {
        return layers;
    }

    @Override
    public PixelCanvas activeCanvas() {
        Layer active = layers.getActiveLayer();
        return active != null ? active.getCanvas() : null;
    }

    @Override
    public void beginMutation() {
        if (before == null) {
            before = LayerStackSnapshot.capture(layers);
        }
    }

    @Override
    public void notifyModified() {
        modifiedCount++;
    }

    @Override
    public boolean exportPng(String absolutePath) {
        if (!exportSucceeds) {
            return false;
        }
        exportedPaths.add(absolutePath);
        return true;
    }

    // ===================== Test hooks =====================

    /** True once any mutation captured the before-snapshot. */
    public boolean touched() {
        return before != null;
    }

    /** Restore the pre-run layer stack (what the live service does on failure). */
    public void rollback() {
        if (before != null) {
            before.restore(layers);
        }
    }

    public int modifiedCount() {
        return modifiedCount;
    }

    /** Paths "written" by exportPng, in call order. */
    public List<String> exportedPaths() {
        return exportedPaths;
    }

    /** Make every subsequent exportPng report failure. */
    public void failExports() {
        exportSucceeds = false;
    }
}
