package com.openmason.main.systems.menus.textureCreator.rendering;

import com.openmason.main.systems.menus.preferences.PreferencesManager;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the texture editor's curated Aseprite-style default dock layout:
 *
 * <pre>
 * ┌──────┬─────────────────────────┬───────────┐
 * │Tools │                         │ Layers    │  (Layers node keeps its tab
 * │      │        Canvas           │           │   bar — hosts Noise Filter
 * │      │                         ├───────────┤   and Symmetry as tabs)
 * │      │                         │ Color     │
 * ├──────┴─────────────────────────┴───────────┤
 * │ Palette                                    │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * The layout is rebuilt when: no saved layout exists, the saved layout
 * predates {@link #LAYOUT_VERSION} (stamped in preferences so an old
 * imgui.ini can't pin users to a stale arrangement), or the user picks
 * View → Reset Layout.
 */
public final class TextureEditorLayoutBuilder {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorLayoutBuilder.class);

    /** Bump when the curated layout changes to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 2;

    private static final float TOOLS_RATIO = 0.05f;
    private static final float PALETTE_RATIO = 0.08f;
    private static final float RIGHT_COLUMN_RATIO = 0.26f;
    private static final float LAYERS_RATIO = 0.55f;

    private final PreferencesManager preferences;
    private boolean resetRequested = false;
    private boolean appliedThisSession = false;

    public TextureEditorLayoutBuilder(PreferencesManager preferences) {
        this.preferences = preferences;
    }

    /** Request a rebuild on the next frame (View → Reset Layout). */
    public void requestReset() {
        resetRequested = true;
    }

    /**
     * Apply the default layout if needed. Call once per frame, immediately
     * after {@code ImGui.dockSpace()} has submitted the dockspace.
     *
     * @return true if the layout was rebuilt this frame
     */
    public boolean applyIfNeeded(int dockspaceId, float width, float height) {
        boolean rebuild = resetRequested;

        if (!rebuild && !appliedThisSession) {
            var node = imgui.internal.ImGui.dockBuilderGetNode(dockspaceId);
            boolean hasSavedLayout = node != null && node.isSplitNode();
            boolean versionCurrent = preferences.getTextureEditorLayoutVersion() >= LAYOUT_VERSION;
            rebuild = !hasSavedLayout || !versionCurrent;
        }

        appliedThisSession = true;
        if (!rebuild) {
            return false;
        }
        resetRequested = false;

        build(dockspaceId, width, height);
        preferences.setTextureEditorLayoutVersion(LAYOUT_VERSION);
        return true;
    }

    private void build(int dockspaceId, float width, float height) {
        logger.info("Building texture editor default layout (version {})", LAYOUT_VERSION);

        if (width <= 0 || height <= 0) {
            width = 1200;
            height = 800;
        }

        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.flag.ImGuiDockNodeFlags.None);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);

        ImInt tools = new ImInt();
        ImInt rest = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, TOOLS_RATIO, tools, rest);

        ImInt palette = new ImInt();
        ImInt mid = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Down, PALETTE_RATIO, palette, mid);

        ImInt right = new ImInt();
        ImInt center = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(mid.get(), ImGuiDir.Right, RIGHT_COLUMN_RATIO, right, center);

        ImInt layers = new ImInt();
        ImInt color = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(right.get(), ImGuiDir.Up, LAYERS_RATIO, layers, color);

        imgui.internal.ImGui.dockBuilderDockWindow("Tools", tools.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Palette", palette.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Canvas", center.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Layers", layers.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Noise Filter", layers.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Symmetry", layers.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Color", color.get());

        // Aseprite-style fixed look: hide tab bars on single-purpose nodes.
        // The Layers node keeps its tab bar — it hosts Noise Filter/Symmetry
        // tabs and is the recovery point for re-docking floating windows.
        int hidden = imgui.internal.flag.ImGuiDockNodeFlags.NoTabBar
                | imgui.internal.flag.ImGuiDockNodeFlags.NoWindowMenuButton;
        addLocalFlags(tools.get(), hidden);
        addLocalFlags(palette.get(), hidden);
        addLocalFlags(center.get(), hidden);
        addLocalFlags(color.get(), hidden);

        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    private static void addLocalFlags(int nodeId, int flags) {
        var node = imgui.internal.ImGui.dockBuilderGetNode(nodeId);
        if (node != null) {
            node.addLocalFlags(flags);
        }
    }
}
