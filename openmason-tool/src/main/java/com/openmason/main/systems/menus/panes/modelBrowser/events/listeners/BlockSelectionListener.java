package com.openmason.main.systems.menus.panes.modelBrowser.events.listeners;

import com.openmason.main.systems.menus.panes.modelBrowser.events.BlockSelectedEvent;

/**
 * Listener interface for block selection events.
 */
@FunctionalInterface
public interface BlockSelectionListener {

    /**
     * Called when a block is selected in the Model Browser.
     */
    void onBlockSelected(BlockSelectedEvent event);
}
