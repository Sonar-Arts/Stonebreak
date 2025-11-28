package com.openmason.ui.modelBrowser.events;

import com.stonebreak.blocks.BlockType;

/**
 * Event fired when a block is selected in the Model Browser.
 *
 * <p>This immutable event class encapsulates the block selection action,
 * following the Single Responsibility Principle by only carrying event data.</p>
 */
public final class BlockSelectedEvent {

    private final BlockType blockType;
    private final long timestamp;

    /**
     * Creates a new block selection event.
     *
     * @param blockType The type of block that was selected
     */
    public BlockSelectedEvent(BlockType blockType) {
        this.blockType = blockType;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected block type.
     *
     * @return The selected BlockType
     */
    public BlockType getBlockType() {
        return blockType;
    }

    /**
     * Gets the timestamp when this event was created.
     *
     * @return The timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "BlockSelectedEvent{" +
                "blockType=" + blockType +
                ", timestamp=" + timestamp +
                '}';
    }
}
