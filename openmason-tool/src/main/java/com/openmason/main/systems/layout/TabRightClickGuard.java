package com.openmason.main.systems.layout;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiMouseButton;

/**
 * Keeps a right-click on a tab from selecting it. ImGui's tab items select themselves on a right
 * click or release (hardcoded, so the context-menu idiom highlights the clicked widget), which
 * switches workspaces or documents just to open a menu. Between {@link #begin()} and
 * {@link #end()} the right button's click/release are hidden from the tab items; the context menu
 * is opened explicitly with {@link #openContextMenu(String)} instead. Both halves must be hidden:
 * ImGui 1.92 reads a press from {@code io.MouseDownDuration == 0} and a release from
 * {@code io.MouseReleased}.
 *
 * <pre>
 * guard.begin();
 * if (ImGui.beginTabBar(...)) {
 *     ImGui.beginTabItem(...);
 *     guard.openContextMenu("##ctx");          // before beginPopupContextItem("##ctx")
 *     if (ImGui.beginPopupContextItem("##ctx")) { ... }
 *     ImGui.endTabBar();
 * }
 * guard.end();                                  // always, even when the bar did not begin
 * </pre>
 */
public final class TabRightClickGuard {

    /** A held-for-a-while duration: the press is not "this frame" while the tab bar is submitted. */
    private static final float PRESS_HIDDEN = 1f;

    private boolean clicked;
    private boolean released;
    private boolean active;
    /** The right button's "just pressed" duration is hidden until {@link #end()}. */
    private boolean pressHidden;

    /** Hides this frame's right-button click/release; call right before {@code beginTabBar}. */
    public void begin() {
        ImGuiIO io = ImGui.getIO();
        clicked = io.getMouseClicked(ImGuiMouseButton.Right);
        released = io.getMouseReleased(ImGuiMouseButton.Right);
        io.setMouseClicked(ImGuiMouseButton.Right, false);
        io.setMouseReleased(ImGuiMouseButton.Right, false);
        pressHidden = io.getMouseDownDuration(ImGuiMouseButton.Right) == 0f;
        if (pressHidden) {
            io.setMouseDownDuration(ImGuiMouseButton.Right, PRESS_HIDDEN);
        }
        active = true;
    }

    /**
     * Opens {@code popupId} when the last item was right-clicked this frame (on release, as
     * {@code beginPopupContextItem} does). Call right after the tab item, in the same ID scope as
     * the matching {@code beginPopupContextItem(popupId)}.
     */
    public void openContextMenu(String popupId) {
        if (active && released && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenBlockedByPopup)) {
            ImGui.openPopup(popupId);
        }
    }

    /** Gives the right button its state back for the rest of the frame. */
    public void end() {
        if (!active) {
            return;
        }
        ImGuiIO io = ImGui.getIO();
        io.setMouseClicked(ImGuiMouseButton.Right, clicked);
        io.setMouseReleased(ImGuiMouseButton.Right, released);
        if (pressHidden) {
            io.setMouseDownDuration(ImGuiMouseButton.Right, 0f);
            pressHidden = false;
        }
        active = false;
    }
}
