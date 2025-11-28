package com.openmason.main.systems.menus.panes.modelBrowser.events.listeners;

import com.openmason.main.systems.menus.panes.modelBrowser.events.ModelSelectedEvent;

/**
 * Listener interface for entity model selection events.
 */
@FunctionalInterface
public interface ModelSelectionListener {

    /**
     * Called when an entity model is selected in the Model Browser.
     */
    void onModelSelected(ModelSelectedEvent event);
}
