package com.openmason.main.systems.viewport.state;

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

    private RenderingMode mode = RenderingMode.BLOCK_MODEL;

    // Model state (for BlockModels)
    private String currentModelName = null;
    private String currentTextureVariant = "default";

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
     * Switch to model rendering mode (legacy cow functionality removed).
     * @deprecated Legacy cow model support has been removed
     */
    @Deprecated
    public void setModelMode(String modelName, Object model) {
        logger.debug("setModelMode called but legacy MODEL rendering mode is no longer supported: {}", modelName);
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
        this.currentModelName = null;
        this.selectedItem = null;
        logger.debug("Switched to BLOCK rendering mode: {}", blockType.name());
    }

    /**
     * Switch to block model rendering mode (.OMO editable models).
     */
    public void setBlockModelMode(String modelName) {
        this.mode = RenderingMode.BLOCK_MODEL;
        this.currentModelName = modelName;
        this.selectedBlock = null;
        this.selectedItem = null;
        logger.debug("Switched to BLOCK_MODEL rendering mode: {}", modelName);
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
        this.currentModelName = null;
        this.selectedBlock = null;
        logger.debug("Switched to ITEM rendering mode: {}", itemType.name());
    }

    /**
     * Set current model (legacy cow functionality removed).
     * @deprecated Legacy cow model support has been removed
     */
    @Deprecated
    public void setCurrentModel(Object model) {
        logger.trace("setCurrentModel called but legacy model support is removed");
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

    // Getters
    public RenderingMode getMode() { return mode; }
    public String getCurrentModelName() { return currentModelName; }
    public String getCurrentTextureVariant() { return currentTextureVariant; }
    public BlockType getSelectedBlock() { return selectedBlock; }
    public ItemType getSelectedItem() { return selectedItem; }

    /**
     * Check if model is ready for rendering (legacy cow functionality removed).
     * @deprecated Legacy MODEL mode is no longer supported
     */
    @Deprecated
    public boolean isModelReady() {
        return false; // Legacy MODEL mode no longer supported
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
            case BLOCK_MODEL -> currentModelName != null ? "BlockModel: " + currentModelName : "No block model";
            case BLOCK -> selectedBlock != null ? "Block: " + selectedBlock.name() : "No block";
            case ITEM -> selectedItem != null ? "Item: " + selectedItem.name() : "No item";
        };
    }

    @Override
    public String toString() {
        return String.format("RenderingState{mode=%s, %s}", mode, getStateDescription());
    }
}
