package com.openmason.ui.modelBrowser.events.listeners;

import com.openmason.ui.modelBrowser.events.ItemSelectedEvent;

/**
 * Listener interface for item selection events.
 *
 * <p>This focused interface follows the Interface Segregation Principle (ISP)
 * by allowing components to implement only item selection handling without
 * being forced to implement block or model selection methods.</p>
 *
 * <p>Use this interface when a component only needs to respond to item selections.</p>
 *
 * @see BlockSelectionListener
 * @see ModelSelectionListener
 */
@FunctionalInterface
public interface ItemSelectionListener {

    /**
     * Called when an item is selected in the Model Browser.
     *
     * @param event The item selection event containing the selected ItemType
     */
    void onItemSelected(ItemSelectedEvent event);
}
