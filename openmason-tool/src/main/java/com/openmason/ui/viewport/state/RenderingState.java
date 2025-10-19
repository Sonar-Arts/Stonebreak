package com.openmason.ui.viewport.state;

import com.openmason.model.StonebreakModel;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages current rendering state (mode, selected model/block/item).
 * Mutable state object for tracking what content to display.
 */
public class RenderingState {

    private static final Logger logger = LoggerFactory.getLogger(RenderingState.class);

    private RenderingMode mode = RenderingMode.MODEL;
    private boolean modelRenderingEnabled = true;

    // Model state
    private String currentModelName = null;
    private String currentTextureVariant = "default";
    private StonebreakModel currentModel = null;

    // Block state
    private BlockType selectedBlock = null;

    // Item state
    private ItemType selectedItem = null;

    /**
     * Create default rendering state (model mode).
     */
    public RenderingState() {
        // Defaults already set
    }

    /**
     * Switch to model rendering mode.
     */
    public void setModelMode(String modelName, StonebreakModel model) {
        this.mode = RenderingMode.MODEL;
        this.currentModelName = modelName;
        this.currentModel = model;
        this.selectedBlock = null;
        this.selectedItem = null;
        logger.debug("Switched to MODEL rendering mode: {}", modelName);
    }

    /**
     * Switch to block rendering mode.
     */
    public void setBlockMode(BlockType blockType) {
        if (blockType == null) {
            logger.warn("Cannot set null block type");
            return;
        }
        this.mode = RenderingMode.BLOCK;
        this.selectedBlock = blockType;
        this.currentModel = null;
        this.currentModelName = null;
        this.selectedItem = null;
        logger.debug("Switched to BLOCK rendering mode: {}", blockType.name());
    }

    /**
     * Switch to item rendering mode.
     */
    public void setItemMode(ItemType itemType) {
        if (itemType == null) {
            logger.warn("Cannot set null item type");
            return;
        }
        this.mode = RenderingMode.ITEM;
        this.selectedItem = itemType;
        this.currentModel = null;
        this.currentModelName = null;
        this.selectedBlock = null;
        logger.debug("Switched to ITEM rendering mode: {}", itemType.name());
    }

    /**
     * Clear current model.
     */
    public void clearModel() {
        this.currentModel = null;
        this.currentModelName = null;
        this.currentTextureVariant = "default";
        logger.debug("Model cleared");
    }

    /**
     * Set current model (without switching mode).
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        logger.trace("Current model updated: {}", model != null ? "loaded" : "null");
    }

    /**
     * Set current texture variant.
     */
    public void setCurrentTextureVariant(String variant) {
        if (variant == null || variant.equals(currentTextureVariant)) {
            return;
        }
        String previousVariant = currentTextureVariant;
        this.currentTextureVariant = variant;
        logger.debug("Texture variant changed: {} → {}", previousVariant, variant);
    }

    /**
     * Set model rendering enabled.
     */
    public void setModelRenderingEnabled(boolean enabled) {
        this.modelRenderingEnabled = enabled;
        logger.trace("Model rendering enabled: {}", enabled);
    }

    // Getters
    public RenderingMode getMode() { return mode; }
    public boolean isModelRenderingEnabled() { return modelRenderingEnabled; }
    public String getCurrentModelName() { return currentModelName; }
    public String getCurrentTextureVariant() { return currentTextureVariant; }
    public StonebreakModel getCurrentModel() { return currentModel; }
    public BlockType getSelectedBlock() { return selectedBlock; }
    public ItemType getSelectedItem() { return selectedItem; }

    /**
     * Check if model is ready for rendering.
     */
    public boolean isModelReady() {
        return mode == RenderingMode.MODEL && currentModel != null && modelRenderingEnabled;
    }

    /**
     * Check if block is ready for rendering.
     */
    public boolean isBlockReady() {
        return mode == RenderingMode.BLOCK && selectedBlock != null;
    }

    /**
     * Check if item is ready for rendering.
     */
    public boolean isItemReady() {
        return mode == RenderingMode.ITEM && selectedItem != null;
    }

    /**
     * Get description of current state.
     */
    public String getStateDescription() {
        return switch (mode) {
            case MODEL -> currentModelName != null ? "Model: " + currentModelName : "No model";
            case BLOCK -> selectedBlock != null ? "Block: " + selectedBlock.name() : "No block";
            case ITEM -> selectedItem != null ? "Item: " + selectedItem.name() : "No item";
        };
    }

    @Override
    public String toString() {
        return String.format("RenderingState{mode=%s, %s}", mode, getStateDescription());
    }
}
