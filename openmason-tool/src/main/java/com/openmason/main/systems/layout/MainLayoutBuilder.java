package com.openmason.main.systems.layout;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserImGui;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.panes.riggingPane.RiggingPaneImGui;
import com.openmason.main.systems.scene.views.SceneInspectorImGui;
import com.openmason.main.systems.scene.views.SceneOutlinerImGui;
import com.openmason.main.systems.scene.views.SceneViewerMainView;
import com.openmason.main.systems.viewport.views.ViewportMainView;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.type.ImInt;

/**
 * Builds the main dockspace's default arrangement.
 *
 * <pre>
 * +--------------+----------------------------------+------------------+
 * | Model        |  [Scene Viewer] [Model Editor]   | Scene Outliner   |
 * | Properties   |                                  +------------------+
 * | / Rigging    |        (central node)            | Scene Inspector  |
 * +--------------+----------------------------------+------------------+
 * |  Project Browser                                                    |
 * +---------------------------------------------------------------------+
 * </pre>
 *
 * <p>The Scene workspace's {@code layoutVersion} is {@link #LAYOUT_VERSION}: each release that adds
 * a docked window bumps it and every user gets exactly one rebuild ({@link WorkspaceLayouts}).
 */
public final class MainLayoutBuilder {

    /**
     * Bump when the default arrangement gains or moves a window.
     * <ul>
     *   <li>1 — Model Properties / Rigging / the model viewport / Project Browser</li>
     *   <li>2 — adds Scene Viewer, Scene Outliner and Scene Inspector</li>
     *   <li>3 — "3D Viewport" renamed to "Model Editor". A title change orphans that
     *       window's saved imgui.ini entry, so without a rebuild it would reopen
     *       floating instead of docked.</li>
     * </ul>
     */
    /**
     * v4 (2026-08-23): Assistant pane docked into the right-top stack. A new
     * docked window MUST bump this so existing imgui.ini users get a rebuild —
     * otherwise the pane opens floating (possibly off-window) on first load.
     */
    public static final int LAYOUT_VERSION = 4;

    private String pendingFocusWindow;

    /** The tab to focus after a rebuild; consumed once. */
    public String takePendingFocusWindow() {
        String window = pendingFocusWindow;
        pendingFocusWindow = null;
        return window;
    }

    /**
     * Arranges the default layout into {@code dockspaceId}; whether that is due (no saved layout,
     * a newer {@link #LAYOUT_VERSION}, a reset) is decided by {@link WorkspaceLayouts}.
     */
    public void build(int dockspaceId, float width, float height) {
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        // ImGuiDockNodeFlags.None, not PassthruCentralNode: this call expects *internal*
        // node flags, and the passthru behaviour actually comes from the ImGui.dockSpace()
        // call in the host window.
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, ImGuiDockNodeFlags.None);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);

        ImInt bottom = new ImInt();
        ImInt top = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, imgui.flag.ImGuiDir.Down, 0.30f, bottom, top);

        ImInt left = new ImInt();
        ImInt rest = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(top.get(), imgui.flag.ImGuiDir.Left, 0.19f, left, rest);

        ImInt right = new ImInt();
        ImInt center = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), imgui.flag.ImGuiDir.Right, 0.22f, right, center);

        ImInt rightBottom = new ImInt();
        ImInt rightTop = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(right.get(), imgui.flag.ImGuiDir.Down, 0.55f, rightBottom, rightTop);

        // Centre node: tab order follows dock order, left to right.
        imgui.internal.ImGui.dockBuilderDockWindow(SceneViewerMainView.WINDOW_TITLE, center.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ViewportMainView.WINDOW_TITLE, center.get());

        imgui.internal.ImGui.dockBuilderDockWindow(PropertyPanelImGui.WINDOW_TITLE, left.get());
        imgui.internal.ImGui.dockBuilderDockWindow(RiggingPaneImGui.WINDOW_TITLE, left.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SceneOutlinerImGui.WINDOW_TITLE, rightTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(
                com.openmason.main.systems.assistant.ui.AssistantPaneImGui.WINDOW_TITLE, rightTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SceneInspectorImGui.WINDOW_TITLE, rightBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ProjectBrowserImGui.WINDOW_TITLE, bottom.get());

        // Mark the centre as the central node so window resizing is absorbed there and the
        // side columns keep their widths. Deliberately NOT NoTabBar — that would hide the
        // Scene Viewer / Model Editor tabs and make one of them unreachable.
        var centerNode = imgui.internal.ImGui.dockBuilderGetNode(center.get());
        if (centerNode != null) {
            centerNode.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode);
        }

        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);

        pendingFocusWindow = SceneViewerMainView.WINDOW_TITLE;
    }

    /** Work-area size helper for callers that just want the current viewport. */
    public static float[] workAreaSize() {
        ImGuiViewport viewport = ImGui.getMainViewport();
        return new float[]{viewport.getWorkSizeX(), viewport.getWorkSizeY()};
    }
}
