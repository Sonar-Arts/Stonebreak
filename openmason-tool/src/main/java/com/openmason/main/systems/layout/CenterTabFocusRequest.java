package com.openmason.main.systems.layout;

import imgui.ImGui;

/**
 * Brings a centre-dock window to the front, a few frames after being asked.
 *
 * <p>{@code dockBuilderDockWindow} sets tab <em>order</em>, not which tab is selected, so
 * focusing has to be an explicit call. It cannot be a single shot: on a cold start neither
 * centre window exists in ImGui's window list on the frame the layout is built, and
 * {@code setWindowFocus} on an unknown title silently does nothing. Retrying for a few
 * frames costs nothing and is robust to the Scene Viewer being submitted later in the
 * frame than the dockspace.
 */
public final class CenterTabFocusRequest {

    /** Enough frames for the target window to have been submitted at least once. */
    private static final int RETRY_FRAMES = 3;

    private String target;
    private int framesLeft;

    /** Ask for a window to be focused; replaces any pending request. */
    public void request(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank()) {
            return;
        }
        this.target = windowTitle;
        this.framesLeft = RETRY_FRAMES;
    }

    /** Call once per frame, after all windows have been submitted. */
    public void tick() {
        if (target == null || framesLeft <= 0) {
            target = null;
            return;
        }
        ImGui.setWindowFocus(target);
        if (--framesLeft <= 0) {
            target = null;
        }
    }

    public boolean isPending() {
        return target != null && framesLeft > 0;
    }
}
