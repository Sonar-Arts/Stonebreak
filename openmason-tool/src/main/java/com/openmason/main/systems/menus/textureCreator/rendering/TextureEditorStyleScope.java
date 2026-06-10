package com.openmason.main.systems.menus.textureCreator.rendering;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Scoped Aseprite-style dark restyle for the texture editor. Pushed at the
 * start of {@code TextureEditorWindow.render()} and popped at the end — all
 * docked editor panels render inside that scope, so the editor gets its flat
 * dark look without touching the global Open Mason theme. Accent colors
 * (Header/Button) are intentionally left to the active theme.
 */
public final class TextureEditorStyleScope {

    // Flat dark palette (ImGui packed ABGR)
    private static final int PANEL_BG = 0xFF22201E;        // #1e2022-ish, flat
    private static final int FRAME_BG = 0xFF2E2B28;        // slightly raised inputs
    private static final int FRAME_BG_HOVERED = 0xFF383430;
    private static final int FRAME_BG_ACTIVE = 0xFF403B36;
    private static final int TAB = 0xFF262422;
    private static final int TAB_HOVERED = 0xFF3A3631;
    private static final int TAB_SELECTED = 0xFF322F2B;
    private static final int SEPARATOR = 0xFF3A3631;       // dimmed
    private static final int BORDER = 0xFF323029;          // subtle

    private static final int COLOR_COUNT = 11;
    private static final int VAR_COUNT = 4;

    private boolean pushed = false;

    public void push() {
        if (pushed) {
            return;
        }
        pushed = true;

        ImGui.pushStyleColor(ImGuiCol.WindowBg, PANEL_BG);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, FRAME_BG);
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, FRAME_BG_HOVERED);
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, FRAME_BG_ACTIVE);
        ImGui.pushStyleColor(ImGuiCol.Tab, TAB);
        ImGui.pushStyleColor(ImGuiCol.TabHovered, TAB_HOVERED);
        ImGui.pushStyleColor(ImGuiCol.TabSelected, TAB_SELECTED);
        ImGui.pushStyleColor(ImGuiCol.TabDimmed, TAB);
        ImGui.pushStyleColor(ImGuiCol.TabDimmedSelected, TAB_SELECTED);
        ImGui.pushStyleColor(ImGuiCol.Separator, SEPARATOR);
        ImGui.pushStyleColor(ImGuiCol.Border, BORDER);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.TabRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6.0f, 4.0f);
    }

    public void pop() {
        if (!pushed) {
            return;
        }
        pushed = false;
        ImGui.popStyleVar(VAR_COUNT);
        ImGui.popStyleColor(COLOR_COUNT);
    }
}
