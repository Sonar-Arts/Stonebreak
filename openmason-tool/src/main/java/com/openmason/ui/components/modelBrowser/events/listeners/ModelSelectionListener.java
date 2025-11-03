package com.openmason.ui.components.modelBrowser.events.listeners;

import com.openmason.ui.components.modelBrowser.events.ModelSelectedEvent;

/**
 * Listener interface for entity model selection events.
 *
 * <p>This focused interface follows the Interface Segregation Principle (ISP)
 * by allowing components to implement only model selection handling without
 * being forced to implement block or item selection methods.</p>
 *
 * <p>Use this interface when a component only needs to respond to entity model selections.</p>
 *
 * @see BlockSelectionListener
 * @see ItemSelectionListener
 */
@FunctionalInterface
public interface ModelSelectionListener {

    /**
     * Called when an entity model is selected in the Model Browser.
     *
     * @param event The model selection event containing the model name
     */
    void onModelSelected(ModelSelectedEvent event);
}
