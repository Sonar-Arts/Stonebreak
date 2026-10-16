package com.openmason.main.systems.menus.textureCreator.rendering;

import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the texture editor's curated Aseprite-style default dock layout:
 *
 * <pre>
 * ┌─────────┬─────┬──────────────────┬─────────┐
 * │ Color   │Tools│                  │ Layers  │  (Layers node keeps its tab
 * │ picker  │     │      Canvas      │         │   bar — hosts Noise Filter
 * │ recent  │fixed│                  │         │   and Symmetry as tabs)
 * │ palettes│width│                  │         │
 * └─────────┴─────┴──────────────────┴─────────┘
 *  └── fixed chrome ──┘└────── dockspace ──────┘
 * </pre>
 *
 * Only Canvas and Layers live in the dockspace. The Color and Tools columns
 * are fixed chrome rendered by {@code PanelRenderingCoordinator
 * .renderLeftColumns()} — as dock nodes, a fixed-width Tools column could
 * not coexist with a resizable Color column (dock splitters either froze
 * both or dumped freed space into the Tools node).
 *
 * The layout is rebuilt when: no saved layout exists, the saved layout
 * predates {@link #LAYOUT_VERSION} (the Texture workspace's layout version,
 * recorded per workspace so an old imgui.ini can't pin users to a stale
 * arrangement), or the user picks View → Reset Layout.
 */
public final class TextureEditorLayoutBuilder {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorLayoutBuilder.class);

    /** Bump when the curated layout changes to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 8;

    private static final float RIGHT_COLUMN_RATIO = 0.25f;

    /**
     * Arranges the default layout; whether that is due is decided by the host's
     * {@code WorkspaceLayouts} gate (the Texture workspace's layout version is {@link #LAYOUT_VERSION}).
     */
    public void build(int dockspaceId, float width, float height) {
        logger.info("Building texture editor default layout (version {})", LAYOUT_VERSION);

        if (width <= 0 || height <= 0) {
            width = 1200;
            height = 800;
        }

        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        // DockSpace flag: the node is a host-submitted dockspace, not a floating dock node. Without it
        // the rebuilt node is updated as a floating node at the next NewFrame, and the workspace
        // window's DockSpace() call then trips "Cannot call DockSpace() twice a frame" (popped out).
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);

        ImInt right = new ImInt();
        ImInt center = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Right, RIGHT_COLUMN_RATIO, right, center);

        imgui.internal.ImGui.dockBuilderDockWindow("Canvas", center.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Layers", right.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Noise Filter", right.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Symmetry", right.get());

        // Aseprite-style fixed look: hide the tab bar on the canvas node.
        // The Layers node keeps its tab bar — it hosts Noise Filter/Symmetry
        // tabs and is the recovery point for re-docking floating windows.
        //
        // NoDockingOverMe is essential alongside NoTabBar: without it, a
        // window dragged onto the node tabs in BEHIND the resident window
        // with no tab bar to reach it — invisible and unrecoverable except
        // via Reset Layout.
        //
        // CentralNode makes the canvas absorb dockspace resizing: when the
        // window widens, the central node grows and the sibling Layers node
        // keeps its pixel width instead of scaling proportionally.
        int hidden = imgui.internal.flag.ImGuiDockNodeFlags.NoTabBar
                | imgui.internal.flag.ImGuiDockNodeFlags.NoWindowMenuButton
                | imgui.internal.flag.ImGuiDockNodeFlags.NoDockingOverMe
                | imgui.internal.flag.ImGuiDockNodeFlags.CentralNode;
        addLocalFlags(center.get(), hidden);

        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    private static void addLocalFlags(int nodeId, int flags) {
        var node = imgui.internal.ImGui.dockBuilderGetNode(nodeId);
        if (node != null) {
            node.addLocalFlags(flags);
        }
    }
}
