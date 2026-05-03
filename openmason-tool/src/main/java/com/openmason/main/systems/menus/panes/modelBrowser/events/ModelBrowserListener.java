package com.openmason.main.systems.menus.panes.modelBrowser.events;

import com.openmason.main.systems.menus.panes.modelBrowser.events.listeners.OMOSelectionListener;
import com.openmason.main.systems.menus.panes.modelBrowser.events.listeners.SBTSelectionListener;

/**
 * Composite listener interface for all Model Browser events.
 * The browser only deals with file-backed assets now — .OMO models and
 * .SBT textures.
 */
public interface ModelBrowserListener extends
        OMOSelectionListener,
        SBTSelectionListener {
}
