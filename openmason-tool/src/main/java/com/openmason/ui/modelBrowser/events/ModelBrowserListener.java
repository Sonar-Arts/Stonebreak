package com.openmason.ui.modelBrowser.events;

import com.openmason.ui.modelBrowser.events.listeners.BlockSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ItemSelectionListener;

/**
 * Composite listener interface for all Model Browser events.
 * Supports block and item selection only (legacy model selection removed).
 */
public interface ModelBrowserListener extends
        BlockSelectionListener,
        ItemSelectionListener {
}
