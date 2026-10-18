package com.openmason.main.systems;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.CenterTabFocusRequest;
import com.openmason.main.systems.layout.DetachedWorkspaceWindow;
import com.openmason.main.systems.layout.WorkspaceLayouts;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.layout.WorkspaceTabStrip;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dock layout for the main editor shell: submits the full-work-area host window
 * ("OpenMason Dockspace") with the workspace tabs and the front workspace's toolbar inline,
 * submits every registered workspace's dockspace (the front one for real, the others kept alive
 * so their docked windows keep their places), hosts popped-out workspaces in their own windows,
 * builds each workspace's versioned default layout through {@link WorkspaceLayouts}, and owns the
 * deferred centre-tab focus request.
 *
 * <p>Workspaces come from the plugin registry (#379); nothing here knows which ones exist.
 */
public final class MainDockLayout {

    /** Title of the host window that fills the main viewport's work area. */
    public static final String DOCKSPACE_WINDOW_TITLE = "OpenMason Dockspace";

    /** Dockspace string id of the Scene workspace. */
    public static final String DOCKSPACE_ID = "OpenMasonDockSpace";

    /** Dockspace string id of the UI Editor workspace (#293). */
    public static final String UI_DOCKSPACE_ID = com.openmason.main.systems.uiEditor.view.UiWorkspaceLayout.DOCKSPACE_ID;

    /** Dockspace string id of the Texture Editor workspace. */
    public static final String TEXTURE_DOCKSPACE_ID = "OpenMasonTextureDockSpace";

    private final WorkspaceState workspaceState;
    private final WorkspaceLayouts layouts;
    private final WorkspaceTabStrip tabStrip = new WorkspaceTabStrip();
    private final Map<String, DetachedWorkspaceWindow> detachedWindows = new HashMap<>();
    private final Set<String> detachedVisible = new HashSet<>();
    /** Dockspace ids, hashed in the host window so they are stable whichever window submits them. */
    private final Map<String, Integer> dockspaceIds = new HashMap<>();
    private final CenterTabFocusRequest centerTabFocus;

    public MainDockLayout(WorkspaceState workspaceState, WorkspaceLayouts layouts, CenterTabFocusRequest centerTabFocus) {
        this.workspaceState = Objects.requireNonNull(workspaceState, "workspaceState");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.centerTabFocus = Objects.requireNonNull(centerTabFocus, "centerTabFocus");
    }

    /**
     * Submits the window of every popped-out workspace; call right after {@link #render()} and
     * before any workspace panels.
     */
    public void renderDetached() {
        detachedVisible.clear();
        for (WorkspaceDescriptor ws : workspaceState.workspaces()) {
            if (!dockspaceIds.containsKey(ws.id())) {
                continue; // registered after this frame's host window: its dockspace starts next frame
            }
            DetachedWorkspaceWindow window = detachedWindows.computeIfAbsent(ws.id(), id -> new DetachedWorkspaceWindow(ws));
            if (window.render(workspaceState, () -> ws.dock().renderHeader(),
                    (w, h) -> submitDockspace(ws, w, h), tabStrip.takeTearOffPosition(ws.id()))) {
                detachedVisible.add(ws.id());
            }
        }
    }

    /**
     * True when workspace {@code ws}'s panels should draw this frame: it is the main window's front
     * tab, or it is popped out and its window showed its contents. Valid after {@link #renderDetached()}.
     */
    public boolean isShown(String ws) {
        if (ws == null || !workspaceState.isOpen(ws)) {
            return false;
        }
        return ws.equals(workspaceState.current()) || detachedVisible.contains(ws);
    }

    /** Render the main docking space with the workspace tabs and the front workspace's toolbar. */
    public void render() {
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

        // Workspace tabs on top, then the front workspace's toolbar inline (pushes content down
        // naturally; each toolbar draws its own bottom border)
        tabStrip.render(workspaceState);
        WorkspaceDescriptor front = workspaceState.descriptor(workspaceState.current()).orElse(null);
        if (front != null) {
            front.dock().renderHeader();
        }

        // Reset padding for dockspace area
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        ImGuiViewport mainViewport = ImGui.getMainViewport();
        for (WorkspaceDescriptor ws : workspaceState.workspaces()) {
            dockspaceIds.put(ws.id(), ImGui.getID(ws.dockspaceName()));
            if (ws == front) {
                submitDockspace(ws, mainViewport.getWorkSizeX(), mainViewport.getWorkSizeY());
            } else {
                // Kept alive here even while popped out or closed: the detached window submits it for
                // real later in the frame, and a minimised, closing or closed workspace must not undock
                // its panels.
                ImGui.dockSpace(dockspaceIds.get(ws.id()), 0.0f, 0.0f, ImGuiDockNodeFlags.KeepAliveOnly);
            }
        }

        ImGui.popStyleVar(1);

        ImGui.end();
    }

    /** The workspace's real dockspace (in the main host or its own window) and its default layout. */
    private void submitDockspace(WorkspaceDescriptor ws, float width, float height) {
        ws.dock().submitBody(dockspaceIds.get(ws.id()), width, height, layouts.gate(ws));
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

    /** Ask for the Scene workspace's default layout to be rebuilt on the next frame. */
    public void resetLayout() {
        layouts.requestReset(BuiltinWorkspaces.MODELING);
    }
}
