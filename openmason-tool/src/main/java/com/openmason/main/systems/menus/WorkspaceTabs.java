package com.openmason.main.systems.menus;

import com.openmason.main.systems.layout.Workspace;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Workspace tabs in the menu bar (Blender/Unreal style): the active workspace sits on an accent
 * pill, the others are quiet text that lights up on hover.
 */
final class WorkspaceTabs {

    private static final float PAD_X = 12f;
    private static final float GAP = 2f;

    private WorkspaceTabs() {
    }

    static float width() {
        float w = 0;
        for (Workspace ws : Workspace.values()) {
            w += ImGui.calcTextSize(ws.label()).x + PAD_X * 2 + GAP;
        }
        return w;
    }

    static void render(WorkspaceState state) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getFrameHeight();
        for (Workspace ws : Workspace.values()) {
            float w = ImGui.calcTextSize(ws.label()).x + PAD_X * 2;
            float x = ImGui.getCursorScreenPosX();
            float y = ImGui.getCursorScreenPosY();
            boolean active = state.current() == ws;
            if (ImGui.invisibleButton("##ws" + ws.name(), w, h)) {
                state.set(ws);
            }
            boolean hovered = ImGui.isItemHovered();
            if (active) {
                dl.addRectFilled(x, y + 2, x + w, y + h - 2, ThemeColors.u32(ImGuiCol.HeaderActive, 0.9f), h / 2f);
            } else if (hovered) {
                dl.addRectFilled(x, y + 2, x + w, y + h - 2, ThemeColors.u32(ImGuiCol.HeaderHovered, 0.45f), h / 2f);
            }
            int text;
            if (active) {
                ThemeColors.pushOnAccent(ImGuiCol.Text);
                text = ImGui.getColorU32(ImGuiCol.Text);
                ImGui.popStyleColor();
            } else {
                text = ThemeColors.u32(hovered ? ImGuiCol.Text : ImGuiCol.TextDisabled, 1f);
            }
            dl.addText(x + PAD_X, y + (h - ImGui.getTextLineHeight()) / 2f, text, ws.label());
            if (hovered) {
                ImGui.setTooltip(ws == Workspace.UI ? "Build game UI screens and components" : "Models, textures, scenes");
            }
            ImGui.sameLine(0, GAP);
        }
        ImGui.dummy(0, 0);
    }
}
