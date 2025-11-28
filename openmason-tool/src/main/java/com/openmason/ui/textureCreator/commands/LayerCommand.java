package com.openmason.ui.textureCreator.commands;

import com.openmason.ui.textureCreator.layers.Layer;
import com.openmason.ui.textureCreator.layers.LayerManager;

/**
 * Command for layer operations (add, remove, reorder, etc.).
 *
 * @author Open Mason Team
 */
public class LayerCommand implements Command {

    public enum Type {
        ADD_LAYER,
        REMOVE_LAYER,
        MOVE_LAYER,
        DUPLICATE_LAYER
    }

    private final LayerManager layerManager;
    private final Type type;
    private final String description;

    // Command-specific state
    private Layer layer;
    private int index;
    private int fromIndex;
    private int toIndex;

    /**
     * Create an ADD_LAYER command.
     */
    public static LayerCommand addLayer(LayerManager manager, String layerName) {
        LayerCommand cmd = new LayerCommand(manager, Type.ADD_LAYER, "Add Layer: " + layerName);
        cmd.layer = new Layer(layerName, manager.getActiveLayer().getCanvas().getWidth(),
                             manager.getActiveLayer().getCanvas().getHeight());
        return cmd;
    }

    /**
     * Create a REMOVE_LAYER command.
     */
    public static LayerCommand removeLayer(LayerManager manager, int index) {
        LayerCommand cmd = new LayerCommand(manager, Type.REMOVE_LAYER, "Remove Layer");
        cmd.index = index;
        cmd.layer = manager.getLayer(index); // Store for undo
        return cmd;
    }

    /**
     * Create a MOVE_LAYER command.
     */
    public static LayerCommand moveLayer(LayerManager manager, int fromIndex, int toIndex) {
        LayerCommand cmd = new LayerCommand(manager, Type.MOVE_LAYER, "Move Layer");
        cmd.fromIndex = fromIndex;
        cmd.toIndex = toIndex;
        return cmd;
    }

    /**
     * Create a DUPLICATE_LAYER command.
     */
    public static LayerCommand duplicateLayer(LayerManager manager, int index) {
        LayerCommand cmd = new LayerCommand(manager, Type.DUPLICATE_LAYER, "Duplicate Layer");
        cmd.index = index;
        cmd.layer = manager.getLayer(index).copy();
        return cmd;
    }

    private LayerCommand(LayerManager layerManager, Type type, String description) {
        this.layerManager = layerManager;
        this.type = type;
        this.description = description;
    }

    @Override
    public void execute() {
        switch (type) {
            case ADD_LAYER:
                layerManager.addLayerAt(layerManager.getLayerCount(), layer);
                break;
            case REMOVE_LAYER:
                layerManager.removeLayer(index);
                break;
            case MOVE_LAYER:
                layerManager.moveLayer(fromIndex, toIndex);
                break;
            case DUPLICATE_LAYER:
                layerManager.addLayerAt(index + 1, layer);
                break;
        }
    }

    @Override
    public void undo() {
        switch (type) {
            case ADD_LAYER:
                layerManager.removeLayer(layerManager.getLayerCount() - 1);
                break;
            case REMOVE_LAYER:
                layerManager.addLayerAt(index, layer);
                break;
            case MOVE_LAYER:
                layerManager.moveLayer(toIndex, fromIndex);
                break;
            case DUPLICATE_LAYER:
                layerManager.removeLayer(index + 1);
                break;
        }
    }

    @Override
    public String getDescription() {
        return description;
    }
}
