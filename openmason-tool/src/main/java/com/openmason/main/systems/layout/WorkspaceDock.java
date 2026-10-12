package com.openmason.main.systems.layout;

import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;

/** A secondary workspace's dock contents: its default layout and the toolbar row above its dockspace. */
public interface WorkspaceDock {

    /** Builds the default layout when the dockspace has none (or a reset was requested). */
    void applyLayout(int dockspaceId, float width, float height);

    /** The workspace's own toolbar, drawn above its dockspace in whichever window holds it. */
    void renderHeader();

    /**
     * Everything below the toolbar, in whichever window holds the workspace: by default just the
     * dockspace filling the rest of the window. A workspace with fixed chrome around its dock (the
     * Texture Editor's tool columns and status bar) submits {@code dockspaceId} itself.
     */
    default void submitBody(int dockspaceId, float width, float height) {
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);
        applyLayout(dockspaceId, width, height);
    }
}
