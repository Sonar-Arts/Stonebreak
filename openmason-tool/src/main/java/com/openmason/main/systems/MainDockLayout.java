package com.openmason.main.systems;

import com.openmason.main.systems.layout.CenterTabFocusRequest;
import com.openmason.main.systems.layout.MainLayoutBuilder;
import com.openmason.main.systems.services.LayoutService;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

/**
 * Dock layout for the main editor shell: submits the full-work-area host window
 * ("OpenMason Dockspace") with the toolbar inline, creates the central dockspace,
 * builds the versioned default layout on first use or after a reset (via
 * {@link MainLayoutBuilder}, which the View menu's reset-layout action also drives
 * through {@link LayoutService}), and owns the deferred centre-tab focus request.
 * Extracted from {@link MainImGuiInterface}; ImGui call order and ids are unchanged.
 */
public final class MainDockLayout {

    /** Title of the host window that fills the main viewport's work area. */
    public static final String DOCKSPACE_WINDOW_TITLE = "OpenMason Dockspace";

    /** String id the central dockspace node is keyed by. */
    public static final String DOCKSPACE_ID = "OpenMasonDockSpace";

    /** String id of the UI Editor workspace's dockspace (#293). */
    public static final String UI_DOCKSPACE_ID = com.openmason.main.systems.uiEditor.view.UiWorkspaceLayout.DOCKSPACE_ID;

    /** Builds a secondary workspace's default layout when its dockspace has none. */
    public interface WorkspaceDock {
        void applyLayout(int dockspaceId, float width, float height);
    }

    private com.openmason.main.systems.layout.WorkspaceState workspaceState;
    private WorkspaceDock uiDock;

    /**
     * Adds the UI Editor workspace: while it is in front its own dockspace fills the host window
     * and the Modeling dockspace is kept alive invisibly (and the other way round), so each
     * workspace's docked windows keep their places across switches.
     */
    public void setUiWorkspace(com.openmason.main.systems.layout.WorkspaceState state, WorkspaceDock dock) {
        this.workspaceState = state;
        this.uiDock = dock;
    }

    // Dock layout: versioned so each release adding a window forces exactly one rebuild.
    private final MainLayoutBuilder mainLayoutBuilder;
    private final CenterTabFocusRequest centerTabFocus = new CenterTabFocusRequest();

    /**
     * @param mainLayoutBuilder builds the default layout and tracks the layout version
     * @param layoutService     the View menu's layout service; receives the builder so
     *                          "Reset Layout" can request a rebuild
     */
    public MainDockLayout(MainLayoutBuilder mainLayoutBuilder, LayoutService layoutService) {
        if (mainLayoutBuilder == null) {
            throw new IllegalArgumentException("MainLayoutBuilder cannot be null");
        }
        this.mainLayoutBuilder = mainLayoutBuilder;
        if (layoutService != null) {
            layoutService.setLayoutBuilder(mainLayoutBuilder);
        }
    }

    /**
     * Render main docking space with integrated toolbar.
     *
     * @param toolbar rendered inline inside the host window, above the dockspace
     *                (pushes content down naturally; draws its own bottom border)
     */
    public void render(Runnable toolbar) {
        int windowFlags = ImGuiWindowFlags.NoDocking;

        ImGuiViewport viewport = ImGui.getMainViewport();
        // Note: getWorkPosY() already accounts for the menu bar

        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY());
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY());
        ImGui.setNextWindowViewport(viewport.getID());

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4.0f, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0.0f, 0.0f);

        windowFlags |= ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove |
                ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;

        ImGui.begin(DOCKSPACE_WINDOW_TITLE, windowFlags);
        ImGui.popStyleVar(4);

        // Render toolbar inline (pushes content down naturally)
        // Bottom border is drawn by the toolbar itself
        if (toolbar != null) {
            toolbar.run();
        }

        // Reset padding for dockspace area
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        int dockspaceId = ImGui.getID(DOCKSPACE_ID);
        ImGuiViewport mainViewport = ImGui.getMainViewport();
        boolean uiFront = workspaceState != null && workspaceState.isUi() && uiDock != null;
        if (uiFront) {
            ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.KeepAliveOnly);
            int uiId = ImGui.getID(UI_DOCKSPACE_ID);
            ImGui.dockSpace(uiId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);
            uiDock.applyLayout(uiId, mainViewport.getWorkSizeX(), mainViewport.getWorkSizeY());
            ImGui.popStyleVar(1);
            ImGui.end();
            return;
        }
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);
        if (uiDock != null) {
            ImGui.dockSpace(ImGui.getID(UI_DOCKSPACE_ID), 0.0f, 0.0f, ImGuiDockNodeFlags.KeepAliveOnly);
        }

        if (mainLayoutBuilder.applyIfNeeded(dockspaceId,
                mainViewport.getWorkSizeX(), mainViewport.getWorkSizeY())) {
            // A rebuild's focus is only a fallback: a project's recorded centre tab
            // (already pending from the restore hook) must win over it.
            String rebuildFocus = mainLayoutBuilder.takePendingFocusWindow();
            if (!centerTabFocus.isPending()) {
                centerTabFocus.request(rebuildFocus);
            }
        }

        ImGui.popStyleVar(1);

        ImGui.end();
    }

    /**
     * Bring a centre-dock tab to the front (Scene Viewer or 3D Viewport).
     * Deferred by a few frames, because focusing a window ImGui has not submitted yet
     * silently does nothing.
     */
    public void requestCenterTab(String windowTitle) {
        centerTabFocus.request(windowTitle);
    }

    /**
     * Advance the deferred centre-tab focus. Must run after every window has been
     * submitted for the frame, which is what makes focusing a freshly docked tab
     * actually take effect.
     */
    public void tickCenterTabFocus() {
        centerTabFocus.tick();
    }

    /** Ask for the default layout to be rebuilt on the next frame. */
    public void resetLayout() {
        mainLayoutBuilder.requestReset();
    }

    public MainLayoutBuilder getLayoutBuilder() {
        return mainLayoutBuilder;
    }
}
