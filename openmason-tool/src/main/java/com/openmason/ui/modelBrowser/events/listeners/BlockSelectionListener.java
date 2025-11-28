package com.openmason.ui.modelBrowser.events.listeners;

import com.openmason.ui.modelBrowser.events.BlockSelectedEvent;

/**
 * Listener interface for block selection events.
 *
 * <p>This focused interface follows the Interface Segregation Principle (ISP)
 * by allowing components to implement only block selection handling without
 * being forced to implement item or model selection methods.</p>
 *
 * <p>Use this interface when a component only needs to respond to block selections.</p>
 *
 * @see ItemSelectionListener
 * @see ModelSelectionListener
 */
@FunctionalInterface
public interface BlockSelectionListener {

    /**
     * Called when a block is selected in the Model Browser.
     *
     * @param event The block selection event containing the selected BlockType
     */
    void onBlockSelected(BlockSelectedEvent event);
}
