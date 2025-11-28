package com.openmason.ui.modelBrowser.events;

import com.openmason.ui.modelBrowser.events.listeners.BlockSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ItemSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ModelSelectionListener;

/**
 * Composite listener interface for all Model Browser events.
 */
public interface ModelBrowserListener extends
        BlockSelectionListener,
        ItemSelectionListener,
        ModelSelectionListener {
}
