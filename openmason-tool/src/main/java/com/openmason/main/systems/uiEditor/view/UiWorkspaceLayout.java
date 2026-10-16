package com.openmason.main.systems.uiEditor.view;

import imgui.flag.ImGuiDir;
import imgui.type.ImInt;

/**
 * The UI workspace's default dock arrangement, Unreal UMG-style:
 *
 * <pre>
 * +-----------+-------------------------------------+----------------+
 * | Palette   |  [doc tabs] toolbar                 |                |
 * +-----------+                                     |    Details     |
 * | Hierarchy |          Designer (canvas)          |                |
 * |           |                                     |                |
 * |           +-------------------------------------+                |
 * |           | Style Sheets | Script | ... | Timeline        |
 * +-----------+-------------------------------------+----------------+
 * </pre>
 *
 * Built when the workspace's dockspace has no saved split yet, or on request (its Window menu).
 */
public final class UiWorkspaceLayout {

    /** The dockspace's string id inside the main host window. */
    public static final String DOCKSPACE_ID = "OpenMasonUiDockSpace";

    /** The UI workspace's default layout version (see {@code WorkspaceDescriptor.layoutVersion}). */
    public static final int LAYOUT_VERSION = 1;

    /**
     * Arranges the default layout; whether that is due (no saved split, a newer version, a reset)
     * is decided by the host's {@code WorkspaceLayouts} gate.
     */
    public void build(int dockspaceId, float width, float height) {
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);

        ImInt left = new ImInt();
        ImInt rest = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.18f, left, rest);
        ImInt right = new ImInt();
        ImInt center = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.27f, right, center);
        ImInt bottom = new ImInt();
        ImInt canvas = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.30f, bottom, canvas);
        ImInt leftBottom = new ImInt();
        ImInt leftTop = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(left.get(), ImGuiDir.Down, 0.58f, leftBottom, leftTop);

        imgui.internal.ImGui.dockBuilderDockWindow(PalettePanel.TITLE, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(AssetsPanel.TITLE, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(HierarchyPanel.TITLE, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(DesignerPanel.TITLE, canvas.get());
        imgui.internal.ImGui.dockBuilderDockWindow(DetailsPanel.TITLE, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(StyleSheetPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ScriptPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(DiagnosticsPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(HistoryPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SpritesPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(TimelinePanel.TITLE, bottom.get());

        var canvasNode = imgui.internal.ImGui.dockBuilderGetNode(canvas.get());
        if (canvasNode != null) {
            canvasNode.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode
                | imgui.internal.flag.ImGuiDockNodeFlags.NoTabBar);
        }
        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }
}
