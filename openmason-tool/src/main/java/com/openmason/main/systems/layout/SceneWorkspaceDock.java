package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.api.workspace.WorkspaceLayout;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;

import java.util.Objects;

/**
 * The Scene workspace's contents: the model editor toolbar above a passthrough dockspace holding
 * Model Properties / Rigging, the Scene Viewer and Model Editor centre tabs, the Scene Outliner /
 * Inspector / Assistant column and the Project Browser ({@link MainLayoutBuilder}).
 *
 * <p>A rebuild of the default arrangement brings a centre tab forward, unless a project's recorded
 * centre tab is already pending (that one wins).
 */
public final class SceneWorkspaceDock implements WorkspaceDock {

    /** Draws the workspace's centre views (model viewport, scene viewer) for a frame. */
    @FunctionalInterface
    public interface Panels {
        void render(float deltaTime);
    }

    private final MainLayoutBuilder layoutBuilder = new MainLayoutBuilder();
    private final CenterTabFocusRequest centerTabFocus;
    private final Runnable toolbar;
    private Panels panels;

    /**
     * @param centerTabFocus the shell's deferred centre-tab focus
     * @param toolbar        the model editor toolbar, drawn above the dockspace
     */
    public SceneWorkspaceDock(CenterTabFocusRequest centerTabFocus, Runnable toolbar) {
        this.centerTabFocus = Objects.requireNonNull(centerTabFocus, "centerTabFocus");
        this.toolbar = toolbar;
    }

    /** The centre views, drawn while the workspace shows (wired once they exist). */
    public void setPanels(Panels panels) {
        this.panels = panels;
    }

    @Override
    public void buildDefaultLayout(int dockspaceId, float width, float height) {
        layoutBuilder.build(dockspaceId, width, height);
        // A rebuild's focus is only a fallback: a project's recorded centre tab
        // (already pending from the restore hook) must win over it.
        String rebuildFocus = layoutBuilder.takePendingFocusWindow();
        if (!centerTabFocus.isPending()) {
            centerTabFocus.request(rebuildFocus);
        }
    }

    @Override
    public void renderHeader() {
        if (toolbar != null) {
            toolbar.run();
        }
    }

    @Override
    public void submitBody(int dockspaceId, float width, float height, WorkspaceLayout layout) {
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);
        layout.applyIfNeeded(dockspaceId, width, height);
    }

    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (shown && panels != null) {
            panels.render(deltaTime);
        }
    }
}
