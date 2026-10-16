package com.openmason.api.workspace;

import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;

/**
 * A workspace's contents: its default dock arrangement, the toolbar row above its dockspace, the
 * body around it, and the panels that dock into it.
 *
 * <p>The host submits the workspace's dockspace in whichever window holds it (the main window when
 * the workspace is the front tab, or its own window when popped out) and decides when the default
 * layout must be rebuilt (no saved arrangement, the {@link WorkspaceDescriptor#layoutVersion()}
 * went up, or the user reset it): {@link #buildDefaultLayout} is only called then.
 */
public interface WorkspaceDock {

    /** A dock with an empty dockspace and no toolbar or panels. */
    WorkspaceDock EMPTY = (dockspaceId, width, height) -> { };

    /**
     * Arranges the default layout into {@code dockspaceId} (DockBuilder calls). The host already
     * decided a rebuild is due; the node may hold a saved arrangement that is to be replaced.
     */
    void buildDefaultLayout(int dockspaceId, float width, float height);

    /** The workspace's own toolbar, drawn above its body in whichever window holds it. */
    default void renderHeader() {
    }

    /**
     * Everything below the toolbar: by default the dockspace filling the rest of the window. A
     * workspace with fixed chrome around its dock (the Texture Editor's tool columns and status
     * bar) submits {@code dockspaceId} itself and then calls {@link WorkspaceLayout#applyIfNeeded}.
     */
    default void submitBody(int dockspaceId, float width, float height, WorkspaceLayout layout) {
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);
        layout.applyIfNeeded(dockspaceId, width, height);
    }

    /**
     * The workspace's docked panels and dialogs; called every frame after the windows holding the
     * workspaces were submitted.
     *
     * @param shown whether the workspace is in front of the main window or in its own visible window
     */
    default void renderPanels(float deltaTime, boolean shown) {
    }

    /**
     * The user asked to close this (closable) workspace.
     *
     * @param proceed closes it; call later when this method took over (e.g. it is asking the user)
     * @return true to close now; false when the dock took over
     */
    default boolean allowClose(Runnable proceed) {
        return true;
    }
}
