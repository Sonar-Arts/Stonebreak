package com.openmason.main.systems.menus.panes.modelBrowser.events;

import com.stonebreak.items.ItemType;

/**
 * Event fired when an item is selected in the Model Browser.
 */
public final class ItemSelectedEvent {

    private final ItemType itemType;
    private final long timestamp;

    /**
     * Creates a new item selection event.
     */
    public ItemSelectedEvent(ItemType itemType) {
        this.itemType = itemType;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected item type.
     */
    public ItemType getItemType() {
        return itemType;
    }

    @Override
    public String toString() {
        return "ItemSelectedEvent{" +
                "itemType=" + itemType +
                ", timestamp=" + timestamp +
                '}';
    }
}
