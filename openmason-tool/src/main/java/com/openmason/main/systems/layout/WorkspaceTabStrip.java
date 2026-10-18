package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDescriptor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.type.ImBoolean;

import java.util.HashMap;
import java.util.Map;

/**
 * The main window's workspace tabs (Unity-style document tabs), one per registered workspace in
 * registration order: {@code Scene}, {@code UI}, while the Texture Editor is open {@code Texture},
 * then any plugin's, at the top of the main window above the front
 * workspace's toolbar. Closable tabs carry a close button.
 *
 * <p>Dragging a tab off the strip, or its context menu, pops it out into its own window, as long
 * as another tab stays in the main window. A popped-out tab stays on the strip as a trailing
 * button with a pop-out icon: clicking it raises its window, its context menu docks it back.
 * A closable tab always shows its close icon, in the main window and popped out alike; that is
 * the only way (besides its menu's Close) to end it.
 */
public final class WorkspaceTabStrip {

    /** Spaces after a popped-out tab's name, painted over by its pop-out icon. */
    static final String ICON_GAP = "  ";

    /** More spaces after a closable popped-out tab's icon, painted over by its close icon. */
    static final String CLOSE_GAP = "   ";

    /** {@code style.TabCloseButtonMinWidthUnselected}: -1 = the close button is always visible. */
    private static final float CLOSE_ALWAYS_VISIBLE = -1f;

    /** Vertical drag (px) that tears a tab off the strip. */
    private static final float TEAR_OFF_DISTANCE = 24f;

    private String shownTab;
    private String forceTab;
    private final Map<String, float[]> tearOffs = new HashMap<>();
    private final TabRightClickGuard rightClick = new TabRightClickGuard();
    private final ImBoolean tabOpen = new ImBoolean(true);

    public void render(WorkspaceState state) {
        String want = state.current();
        if (!java.util.Objects.equals(want, shownTab)) {
            forceTab = want;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 14f, 5f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 2f, 4f);
        rightClick.begin(); // a right-click opens the tab's menu without switching to it
        // Closable tabs show their close button even when they are not the front tab.
        var style = ImGui.getStyle();
        float closeMinWidth = style.getTabCloseButtonMinWidthUnselected();
        style.setTabCloseButtonMinWidthUnselected(CLOSE_ALWAYS_VISIBLE);
        if (ImGui.beginTabBar("##workspaceTabs", ImGuiTabBarFlags.FittingPolicyScroll)) {
            String clicked = null;
            String closed = null;
            for (WorkspaceDescriptor ws : state.workspaces()) {
                if (!state.isOpen(ws.id()) || state.isDetached(ws.id())) {
                    continue;
                }
                int flags = ImGuiTabItemFlags.NoReorder | (ws.id().equals(forceTab) ? ImGuiTabItemFlags.SetSelected : 0);
                String id = ws.label() + "###ws" + ws.id();
                boolean selected;
                if (ws.closable()) {
                    tabOpen.set(true);
                    selected = ImGui.beginTabItem(id, tabOpen, flags);
                    if (!tabOpen.get()) {
                        closed = ws.id();
                    }
                } else {
                    selected = ImGui.beginTabItem(id, flags);
                }
                boolean canDetach = state.canDetach(ws.id());
                if (ImGui.isItemHovered() && !ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
                    ImGui.setTooltip(ws.description());
                }
                if (canDetach && ImGui.isItemActive()
                        && Math.abs(ImGui.getMouseDragDeltaY(ImGuiMouseButton.Left)) > TEAR_OFF_DISTANCE) {
                    tearOffs.put(ws.id(), new float[]{ImGui.getMousePosX(), ImGui.getMousePosY()});
                    ImGui.resetMouseDragDelta(ImGuiMouseButton.Left);
                    state.detach(ws.id());
                }
                contextMenu(state, ws, canDetach);
                if (selected) {
                    clicked = ws.id();
                    ImGui.endTabItem();
                }
            }
            for (WorkspaceDescriptor ws : state.workspaces()) {
                if (state.isDetached(ws.id())) {
                    detachedButton(state, ws);
                }
            }
            ImGui.endTabBar();
            style.setTabCloseButtonMinWidthUnselected(closeMinWidth);
            rightClick.end();
            if (closed != null) {
                state.requestClose(closed);
            } else if (forceTab == null && clicked != null && !clicked.equals(state.current())) {
                // A forced frame only shows the new selection; afterwards, what the user picks wins.
                state.set(clicked);
            }
        }
        style.setTabCloseButtonMinWidthUnselected(closeMinWidth); // idempotent when restored inside the bar
        rightClick.end(); // no-op when already ended inside the bar
        ImGui.popStyleVar(2);
        forceTab = null;
        shownTab = state.current();
    }

    /**
     * Where {@code ws}'s tab was torn off (screen px), consumed by its detached window to open
     * under the pointer; null when it was popped out another way.
     */
    public float[] takeTearOffPosition(String ws) {
        return tearOffs.remove(ws);
    }

    private void contextMenu(WorkspaceState state, WorkspaceDescriptor ws, boolean canDetach) {
        rightClick.openContextMenu("##wsctx" + ws.id());
        if (!ImGui.beginPopupContextItem("##wsctx" + ws.id())) {
            return;
        }
        if (ImGui.menuItem("Open in New Window", "", false, canDetach)) {
            state.detach(ws.id());
        }
        if (!canDetach && ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("The main window keeps at least one tab:\ndock another one back first");
        }
        closeItem(state, ws);
        ImGui.endPopup();
    }

    /**
     * A popped-out tab: its name plus a pop-out icon (and a close icon when closable); clicking
     * raises its window, clicking the close icon closes the tab.
     */
    private void detachedButton(WorkspaceState state, WorkspaceDescriptor ws) {
        // Trailing spaces reserve room for the icons, painted over them (the ImGui font is ASCII-only).
        boolean closable = ws.closable();
        boolean pressed = ImGui.tabItemButton(ws.label() + ICON_GAP + (closable ? CLOSE_GAP : "")
                + "###wsOut" + ws.id(), ImGuiTabItemFlags.Trailing | ImGuiTabItemFlags.NoTooltip);
        float minY = ImGui.getItemRectMinY();
        float maxX = ImGui.getItemRectMaxX();
        float maxY = ImGui.getItemRectMaxY();
        boolean hovered = ImGui.isItemHovered();
        ImDrawList dl = ImGui.getWindowDrawList();
        int text = ImGui.getColorU32(ImGuiCol.Text);
        float right = maxX - ImGui.getStyle().getFramePaddingX();

        boolean overClose = false;
        if (closable) {
            // Same footprint as ImGui's own tab close button: a font-sized square at the right end.
            float box = ImGui.getFontSize();
            float bx = right - box;
            float by = (minY + maxY - box) / 2f;
            overClose = hovered && ImGui.getMousePosX() >= bx && ImGui.getMousePosX() <= bx + box
                    && ImGui.getMousePosY() >= by && ImGui.getMousePosY() <= by + box;
            if (overClose) {
                dl.addCircleFilled(bx + box / 2f, by + box / 2f, box / 2f,
                        ImGui.getColorU32(ImGui.isMouseDown(ImGuiMouseButton.Left)
                                ? ImGuiCol.ButtonActive : ImGuiCol.ButtonHovered));
            }
            closeIcon(dl, bx + box / 2f, by + box / 2f, box * 0.25f, text);
            right = bx - ImGui.getStyle().getItemInnerSpacingX();
        }
        float size = ImGui.getFontSize() * 0.72f;
        popOutIcon(dl, right - size, (minY + maxY - size) / 2f, size, text);

        if (pressed) {
            if (overClose) {
                state.requestClose(ws.id());
                return;
            }
            state.set(ws.id()); // raises the detached window
        }
        if (hovered) {
            ImGui.setTooltip(overClose ? "Close " + ws.label() : ws.label() + " is in its own window");
        }
        rightClick.openContextMenu("##wsctxOut" + ws.id());
        if (ImGui.beginPopupContextItem("##wsctxOut" + ws.id())) {
            if (ImGui.menuItem("Dock Back into Main Window")) {
                state.attach(ws.id(), true);
            }
            closeItem(state, ws);
            ImGui.endPopup();
        }
    }

    /** "Close" in a closable tab's context menu. */
    private static void closeItem(WorkspaceState state, WorkspaceDescriptor ws) {
        if (ws.closable()) {
            ImGui.separator();
            if (ImGui.menuItem("Close " + ws.label())) {
                state.requestClose(ws.id());
            }
        }
    }

    /** An "x" centred on ({@code cx}, {@code cy}), {@code r} from the centre to each arm's end. */
    private static void closeIcon(ImDrawList dl, float cx, float cy, float r, int col) {
        dl.addLine(cx - r, cy - r, cx + r, cy + r, col, 1.3f);
        dl.addLine(cx + r, cy - r, cx - r, cy + r, col, 1.3f);
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
