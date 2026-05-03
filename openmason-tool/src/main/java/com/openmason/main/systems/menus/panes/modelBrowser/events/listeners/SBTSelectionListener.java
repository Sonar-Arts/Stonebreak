package com.openmason.main.systems.menus.panes.modelBrowser.events.listeners;

import com.openmason.main.systems.menus.panes.modelBrowser.events.SBTSelectedEvent;

@FunctionalInterface
public interface SBTSelectionListener {
    void onSBTSelected(SBTSelectedEvent event);
}
