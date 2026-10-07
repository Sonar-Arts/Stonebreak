package com.openmason.main.systems.layout;

/** A secondary workspace's dock contents: its default layout and the toolbar row above its dockspace. */
public interface WorkspaceDock {

    /** Builds the default layout when the dockspace has none (or a reset was requested). */
    void applyLayout(int dockspaceId, float width, float height);

    /** The workspace's own toolbar, drawn above its dockspace in whichever window holds it. */
    void renderHeader();
}
