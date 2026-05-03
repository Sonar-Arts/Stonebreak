package com.openmason.main.systems.menus.panes.modelBrowser.events.listeners;

import com.openmason.main.systems.menus.panes.modelBrowser.events.OMOSelectedEvent;

@FunctionalInterface
public interface OMOSelectionListener {
    void onOMOSelected(OMOSelectedEvent event);
}
