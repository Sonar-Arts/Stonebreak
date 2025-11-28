package com.openmason.ui.modelBrowser.events;

import com.stonebreak.items.ItemType;

/**
 * Event fired when an item is selected in the Model Browser.
 *
 * <p>This immutable event class encapsulates the item selection action,
 * following the Single Responsibility Principle by only carrying event data.</p>
 */
public final class ItemSelectedEvent {

    private final ItemType itemType;
    private final long timestamp;

    /**
     * Creates a new item selection event.
     *
     * @param itemType The type of item that was selected
     */
    public ItemSelectedEvent(ItemType itemType) {
        this.itemType = itemType;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected item type.
     *
     * @return The selected ItemType
     */
    public ItemType getItemType() {
        return itemType;
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
        return "ItemSelectedEvent{" +
                "itemType=" + itemType +
                ", timestamp=" + timestamp +
                '}';
    }
}
