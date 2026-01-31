package com.openmason.main.systems.menus.panes.modelBrowser.events;

import com.stonebreak.blocks.BlockType;

/**
 * Event fired when a block is selected in the Model Browser.
 */
public final class BlockSelectedEvent {

    private final BlockType blockType;
    private final long timestamp;

    /**
     * Creates a new block selection event.
     */
    public BlockSelectedEvent(BlockType blockType) {
        this.blockType = blockType;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected block type.
     */
    public BlockType getBlockType() {
        return blockType;
    }

    /**
     * Gets the timestamp when this event was created.
     */

    @Override
    public String toString() {
        return "BlockSelectedEvent{" +
                "blockType=" + blockType +
                ", timestamp=" + timestamp +
                '}';
    }
}
