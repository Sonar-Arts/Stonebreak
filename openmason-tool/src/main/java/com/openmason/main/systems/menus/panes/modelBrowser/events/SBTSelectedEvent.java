package com.openmason.main.systems.menus.panes.modelBrowser.events;

import com.openmason.engine.format.sbt.SBTFileManager;

/** Event fired when a .SBT texture is selected in the Model Browser. */
public record SBTSelectedEvent(SBTFileManager.SBTFileEntry entry, long timestamp) {
    public SBTSelectedEvent(SBTFileManager.SBTFileEntry entry) {
        this(entry, System.currentTimeMillis());
    }
}
