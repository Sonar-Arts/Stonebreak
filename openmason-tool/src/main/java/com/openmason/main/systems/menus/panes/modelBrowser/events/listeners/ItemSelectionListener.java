package com.openmason.main.systems.menus.panes.modelBrowser.events.listeners;

import com.openmason.main.systems.menus.panes.modelBrowser.events.ItemSelectedEvent;

/**
 * Listener interface for item selection events.
 */
@FunctionalInterface
public interface ItemSelectionListener {

    /**
     * Called when an item is selected in the Model Browser.
     */
    void onItemSelected(ItemSelectedEvent event);
}
