package com.openmason.main.systems.layout;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;

import java.util.EnumMap;
import java.util.Map;

/**
 * The main window's workspace tabs (Unity-style document tabs): {@code Scene} and {@code UI}, at
 * the top of the main window above the front workspace's toolbar.
 *
 * <p>Dragging a tab off the strip, or its context menu, pops it out into its own window, as long
 * as another tab stays in the main window. A popped-out tab stays on the strip as a trailing
 * button with a pop-out icon: clicking it raises its window, its context menu docks it back.
 */
public final class WorkspaceTabStrip {

    /** Spaces after a popped-out tab's name, painted over by its pop-out icon. */
    static final String ICON_GAP = "  ";

    /** Vertical drag (px) that tears a tab off the strip. */
    private static final float TEAR_OFF_DISTANCE = 24f;

    private Workspace shownTab;
    private Workspace forceTab;
    private final Map<Workspace, float[]> tearOffs = new EnumMap<>(Workspace.class);
    private final TabRightClickGuard rightClick = new TabRightClickGuard();

    public void render(WorkspaceState state) {
        Workspace want = state.current();
        if (want != shownTab) {
            forceTab = want;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 14f, 5f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 2f, 4f);
        rightClick.begin(); // a right-click opens the tab's menu without switching to it
        if (ImGui.beginTabBar("##workspaceTabs", ImGuiTabBarFlags.FittingPolicyScroll)) {
            Workspace clicked = null;
            for (Workspace ws : Workspace.values()) {
                if (state.isDetached(ws)) {
                    continue;
                }
                int flags = ImGuiTabItemFlags.NoReorder | (ws == forceTab ? ImGuiTabItemFlags.SetSelected : 0);
                boolean selected = ImGui.beginTabItem(ws.label() + "###ws" + ws.name(), flags);
                boolean canDetach = state.canDetach(ws);
                if (ImGui.isItemHovered() && !ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
                    ImGui.setTooltip(ws.description());
                }
                if (canDetach && ImGui.isItemActive()
                        && Math.abs(ImGui.getMouseDragDeltaY(ImGuiMouseButton.Left)) > TEAR_OFF_DISTANCE) {
                    tearOffs.put(ws, new float[]{ImGui.getMousePosX(), ImGui.getMousePosY()});
                    ImGui.resetMouseDragDelta(ImGuiMouseButton.Left);
                    state.detach(ws);
                }
                contextMenu(state, ws, canDetach);
                if (selected) {
                    clicked = ws;
                    ImGui.endTabItem();
                }
            }
            for (Workspace ws : Workspace.values()) {
                if (state.isDetached(ws)) {
                    detachedButton(state, ws);
                }
            }
            ImGui.endTabBar();
            rightClick.end();
            // A forced frame only shows the new selection; afterwards, what the user picks wins.
            if (forceTab == null && clicked != null && clicked != state.current()) {
                state.set(clicked);
            }
        }
        rightClick.end(); // no-op when already ended inside the bar
        ImGui.popStyleVar(2);
        forceTab = null;
        shownTab = state.current();
    }

    /**
     * Where {@code ws}'s tab was torn off (screen px), consumed by its detached window to open
     * under the pointer; null when it was popped out another way.
     */
    public float[] takeTearOffPosition(Workspace ws) {
        return tearOffs.remove(ws);
    }

    private void contextMenu(WorkspaceState state, Workspace ws, boolean canDetach) {
        rightClick.openContextMenu("##wsctx" + ws.name());
        if (!ImGui.beginPopupContextItem("##wsctx" + ws.name())) {
            return;
        }
        if (ImGui.menuItem("Open in New Window", "", false, canDetach)) {
            state.detach(ws);
        }
        if (!canDetach && ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("The main window keeps at least one tab:\ndock the other one back first");
        }
        ImGui.endPopup();
    }

    /** A popped-out tab: its name plus a pop-out icon; clicking raises its window. */
    private void detachedButton(WorkspaceState state, Workspace ws) {
        // Trailing spaces reserve room for the icon, painted over them (the ImGui font is ASCII-only).
        if (ImGui.tabItemButton(ws.label() + ICON_GAP + "###wsOut" + ws.name(),
                ImGuiTabItemFlags.Trailing | ImGuiTabItemFlags.NoTooltip)) {
            state.set(ws); // raises the detached window
        }
        float size = ImGui.getFontSize() * 0.72f;
        float x = ImGui.getItemRectMaxX() - ImGui.getStyle().getFramePaddingX() - size;
        float y = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY() - size) / 2f;
        popOutIcon(ImGui.getWindowDrawList(), x, y, size, ImGui.getColorU32(ImGuiCol.Text));
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(ws.label() + " is in its own window");
        }
        rightClick.openContextMenu("##wsctxOut" + ws.name());
        if (ImGui.beginPopupContextItem("##wsctxOut" + ws.name())) {
            if (ImGui.menuItem("Dock Back into Main Window")) {
                state.attach(ws, true);
            }
            ImGui.endPopup();
        }
    }

    /** A box with an arrow leaving its top-right corner: "this lives in another window". */
    private static void popOutIcon(ImDrawList dl, float x, float y, float s, int col) {
        float t = 1.3f;
        // box, open toward the top-right where the arrow leaves
        dl.addLine(x + s * 0.45f, y + s * 0.2f, x, y + s * 0.2f, col, t);
        dl.addLine(x, y + s * 0.2f, x, y + s, col, t);
        dl.addLine(x, y + s, x + s * 0.8f, y + s, col, t);
        dl.addLine(x + s * 0.8f, y + s, x + s * 0.8f, y + s * 0.55f, col, t);
        // arrow
        dl.addLine(x + s * 0.4f, y + s * 0.6f, x + s, y, col, t);
        dl.addLine(x + s, y, x + s * 0.58f, y, col, t);
        dl.addLine(x + s, y, x + s, y + s * 0.42f, col, t);
    }
}
