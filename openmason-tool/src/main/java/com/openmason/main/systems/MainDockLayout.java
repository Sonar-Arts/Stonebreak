package com.openmason.main.systems;

import com.openmason.main.systems.layout.CenterTabFocusRequest;
import com.openmason.main.systems.layout.Workspace;
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

    private com.openmason.main.systems.layout.WorkspaceState workspaceState;
    private com.openmason.main.systems.layout.WorkspaceDock uiDock;
    private final com.openmason.main.systems.layout.WorkspaceTabStrip tabStrip =
            new com.openmason.main.systems.layout.WorkspaceTabStrip();
    private final java.util.Map<Workspace, com.openmason.main.systems.layout.DetachedWorkspaceWindow> detachedWindows =
            new java.util.EnumMap<>(Workspace.class);
    private final java.util.EnumSet<Workspace> detachedVisible = java.util.EnumSet.noneOf(Workspace.class);
    /** Dockspace ids, hashed in the host window so they are stable whichever window submits them. */
    private int sceneDockspaceId;
    private int uiDockspaceId;
    /** The Scene workspace's toolbar, as last passed to {@link #render(Runnable)}. */
    private Runnable sceneToolbar;

    /**
     * Adds the UI workspace behind the main window's {@code Scene | UI} tabs: the front tab's
     * dockspace fills the host window and the other is kept alive invisibly, so each workspace's
     * docked windows keep their places across switches. Popped out, a workspace's dockspace moves
     * into its {@link com.openmason.main.systems.layout.DetachedWorkspaceWindow}.
     */
    public void setUiWorkspace(com.openmason.main.systems.layout.WorkspaceState state,
                               com.openmason.main.systems.layout.WorkspaceDock dock) {
        this.workspaceState = state;
        this.uiDock = dock;
        for (Workspace ws : Workspace.values()) {
            detachedWindows.put(ws, new com.openmason.main.systems.layout.DetachedWorkspaceWindow(ws));
        }
    }

    /**
     * Submits the window of every popped-out workspace; call right after {@link #render(Runnable)}
     * and before any workspace panels.
     */
    public void renderDetached() {
        detachedVisible.clear();
        if (workspaceState == null || uiDock == null) {
            return;
        }
        for (Workspace ws : Workspace.values()) {
            if (detachedWindows.get(ws).render(workspaceState, () -> renderHeader(ws),
                    (w, h) -> submitDockspace(ws, w, h), tabStrip.takeTearOffPosition(ws))) {
                detachedVisible.add(ws);
            }
        }
    }

    /**
     * True when {@code ws}'s panels should draw this frame: it is the main window's front tab, or
     * it is popped out and its window showed its contents. Valid after {@link #renderDetached()}.
     */
    public boolean isShown(Workspace ws) {
        if (workspaceState == null || uiDock == null) {
            return ws == Workspace.MODELING;
        }
        return workspaceState.current() == ws || detachedVisible.contains(ws);
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
     * @param toolbar the Scene workspace's toolbar, rendered below the workspace tabs and above the
     *                dockspace of whichever window holds Scene (pushes content down naturally;
     *                draws its own bottom border); the UI workspace draws its own header instead
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

        // Workspace tabs (Scene | UI) on top, then the front workspace's toolbar inline
        // (pushes content down naturally; each toolbar draws its own bottom border)
        sceneToolbar = toolbar;
        Workspace front = Workspace.MODELING;
        if (workspaceState != null && uiDock != null) {
            tabStrip.render(workspaceState);
            front = workspaceState.current();
        }
        renderHeader(front);

        // Reset padding for dockspace area
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        sceneDockspaceId = ImGui.getID(DOCKSPACE_ID);
        uiDockspaceId = ImGui.getID(UI_DOCKSPACE_ID);
        ImGuiViewport mainViewport = ImGui.getMainViewport();
        for (Workspace ws : Workspace.values()) {
            if (ws == Workspace.UI && uiDock == null) {
                continue;
            }
            if (ws == front) {
                submitDockspace(ws, mainViewport.getWorkSizeX(), mainViewport.getWorkSizeY());
            } else {
                // Kept alive here even while popped out: the detached window submits it for real later
                // in the frame, and a minimised or closing window must not undock its panels.
                ImGui.dockSpace(dockspaceId(ws), 0.0f, 0.0f, ImGuiDockNodeFlags.KeepAliveOnly);
            }
        }

        ImGui.popStyleVar(1);

        ImGui.end();
    }

    private int dockspaceId(Workspace ws) {
        return ws == Workspace.UI ? uiDockspaceId : sceneDockspaceId;
    }

    private void renderHeader(Workspace ws) {
        if (ws == Workspace.UI && uiDock != null) {
            uiDock.renderHeader();
        } else if (ws == Workspace.MODELING && sceneToolbar != null) {
            sceneToolbar.run();
        }
    }

    /** The workspace's real dockspace (in the main host or its own window) and its default layout. */
    private void submitDockspace(Workspace ws, float width, float height) {
        if (ws == Workspace.UI) {
            ImGui.dockSpace(uiDockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);
            uiDock.applyLayout(uiDockspaceId, width, height);
            return;
        }
        ImGui.dockSpace(sceneDockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);
        if (mainLayoutBuilder.applyIfNeeded(sceneDockspaceId, width, height)) {
            // A rebuild's focus is only a fallback: a project's recorded centre tab
            // (already pending from the restore hook) must win over it.
            String rebuildFocus = mainLayoutBuilder.takePendingFocusWindow();
            if (!centerTabFocus.isPending()) {
                centerTabFocus.request(rebuildFocus);
            }
        }
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
