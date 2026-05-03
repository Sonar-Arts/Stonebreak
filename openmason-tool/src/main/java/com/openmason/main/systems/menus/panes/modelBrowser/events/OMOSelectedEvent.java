package com.openmason.main.systems.menus.panes.modelBrowser.events;

import com.openmason.engine.format.omo.OMOFileManager;

/** Event fired when a .OMO model is selected in the Model Browser. */
public record OMOSelectedEvent(OMOFileManager.OMOFileEntry entry, long timestamp) {
    public OMOSelectedEvent(OMOFileManager.OMOFileEntry entry) {
        this(entry, System.currentTimeMillis());
    }
}
