package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDescriptor;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiViewportFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * A workspace tab popped out of the main window: a standalone, WM-decorated OS window (the
 * Texture Editor's treatment, so KWin owns drag/resize/close and nothing shakes under XWayland)
 * hosting the workspace's toolbar and the same dockspace the main window uses, so every panel
 * keeps its place whichever window holds it. Closing the window docks the tab back; a
 * {@link WorkspaceDescriptor#closable() closable} tab (the Texture Editor) also comes to the front there,
 * since only its tab's own close button ends it.
 */
public final class DetachedWorkspaceWindow {

    private static final float DEFAULT_W = 1440f;
    private static final float DEFAULT_H = 900f;

    /** What the window holds below its toolbar: the workspace's dockspace, sized to the window. */
    public interface Content {
        void submit(float width, float height);
    }

    private final WorkspaceDescriptor workspace;
    private final String title;
    private final ImGuiWindowClass windowClass = new ImGuiWindowClass();
    private final ImBoolean open = new ImBoolean(true);
    private boolean wasDetached;

    public DetachedWorkspaceWindow(WorkspaceDescriptor workspace) {
        this.workspace = workspace;
        this.title = "Open Mason Toolset - " + workspace.label() + "###openMasonWorkspaceWindow" + workspace.id();
        windowClass.setViewportFlagsOverrideClear(ImGuiViewportFlags.NoDecoration | ImGuiViewportFlags.NoTaskBarIcon);
        windowClass.setViewportFlagsOverrideSet(ImGuiViewportFlags.NoAutoMerge);
    }

    /**
     * Submits the window while its tab is detached. Must run after the main dockspace host (which
     * keeps the dockspace alive) and before the workspace's panels.
     *
     * @param tearOff screen position the tab was dragged to, or null
     * @return true when the window's contents are visible this frame
     */
    public boolean render(WorkspaceState state, Runnable header, Content content, float[] tearOff) {
        if (!state.isDetached(workspace.id())) {
            wasDetached = false;
            return false;
        }
        ImGuiViewport main = ImGui.getMainViewport();
        if (!wasDetached) {
            wasDetached = true;
            if (tearOff != null) {
                ImGui.setNextWindowPos(tearOff[0] - 120f, tearOff[1] - 16f, ImGuiCond.Always);
            } else {
                ImGui.setNextWindowPos(main.getCenterX() - DEFAULT_W / 2f, main.getCenterY() - DEFAULT_H / 2f,
                        ImGuiCond.FirstUseEver);
            }
            ImGui.setNextWindowSize(Math.max(DEFAULT_W, workspace.minWidth()),
                    Math.max(DEFAULT_H, workspace.minHeight()), ImGuiCond.FirstUseEver);
        }
        boolean raise = state.takeFocusRequest(workspace.id());
        if (raise) {
            ImGui.setNextWindowFocus();
        }
        ImGui.setNextWindowSizeConstraints(workspace.minWidth(), workspace.minHeight(), Float.MAX_VALUE, Float.MAX_VALUE);
        ImGui.setNextWindowClass(windowClass);
        open.set(true);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4f, 2f);
        // No ImGui title bar: popped out, the window manager draws it (its close button clears `open`).
        boolean visible = ImGui.begin(title, open, ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        if (raise) {
            raiseOsWindow();
        }
        if (visible) {
            if (header != null) {
                header.run();
            }
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
            content.submit(ImGui.getWindowWidth(), ImGui.getWindowHeight());
            ImGui.popStyleVar();
        }
        ImGui.end();
        if (!open.get()) {
            // Closing the window docks the tab back: behind the front tab, or for a closable tab (still
            // open, it was not closed) in front, so the editor visibly lands back in the main window.
            state.attach(workspace.id(), workspace.closable());
        }
        return visible;
    }

    /**
     * ImGui focus only moves keyboard focus inside the app; bring the window's OS window itself
     * to the front (restoring it first when minimised). Call between this window's begin and end.
     */
    private static void raiseOsWindow() {
        ImGuiViewport vp = ImGui.getWindowViewport();
        long handle = vp.getID() == ImGui.getMainViewport().getID() ? 0L : vp.getPlatformHandle();
        if (handle == 0L) {
            return; // not its own OS window yet (first frame) or viewports unavailable
        }
        if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) {
            org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle);
        }
        org.lwjgl.glfw.GLFW.glfwShowWindow(handle);
        org.lwjgl.glfw.GLFW.glfwFocusWindow(handle);
    }
}
