package com.openmason.main.systems.menus.textureCreator.layers;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable deep copy of a {@link LayerManager}'s whole layer stack (pixels,
 * names, visibility, opacity, active index). One capture before + one after a
 * script run make the run a single undoable step in the texture editor.
 *
 * <p>{@link #restore} hands the manager fresh copies each time, so the
 * snapshot stays pristine across repeated undo/redo cycles.
 */
public final class LayerStackSnapshot {

    private final List<Layer> layers;
    private final int activeIndex;

    private LayerStackSnapshot(List<Layer> layers, int activeIndex) {
        this.layers = layers;
        this.activeIndex = activeIndex;
    }

    public static LayerStackSnapshot capture(LayerManager manager) {
        List<Layer> copies = new ArrayList<>(manager.getLayerCount());
        for (int i = 0; i < manager.getLayerCount(); i++) {
            Layer layer = manager.getLayer(i);
            copies.add(layer.copy(layer.getName()));
        }
        return new LayerStackSnapshot(copies, manager.getActiveLayerIndex());
    }

    public void restore(LayerManager manager) {
        List<Layer> copies = new ArrayList<>(layers.size());
        for (Layer layer : layers) {
            copies.add(layer.copy(layer.getName()));
        }
        manager.replaceLayers(copies, activeIndex);
    }
}
