package com.openmason.main.systems.menus.animationEditor.panels;

import imgui.ImGui;
import org.joml.Vector3f;

/**
 * Shared ImGui helpers for animation editor panels. Kept tiny on purpose —
 * each panel does its own layout; this class only collapses 2–3-line idioms
 * that would otherwise be duplicated.
 */
public final class AnimUI {

    private AnimUI() {}

    /** Tooltip on the previously-rendered item. No-ops if {@code text} is null. */
    public static void tooltip(String text) {
        if (text == null) return;
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.textUnformatted(text);
            ImGui.endTooltip();
        }
    }

    /** Wrap a button (or any item) in a conditional disabled scope. */
    public static void beginDisabled(boolean disabled) {
        if (disabled) ImGui.beginDisabled();
    }

    public static void endDisabled(boolean disabled) {
        if (disabled) ImGui.endDisabled();
    }

    /** Copy a {@link Vector3f} into a 3-element float array buffer. */
    public static void copyVec3(Vector3f src, float[] dst) {
        dst[0] = src.x;
        dst[1] = src.y;
        dst[2] = src.z;
    }
}
