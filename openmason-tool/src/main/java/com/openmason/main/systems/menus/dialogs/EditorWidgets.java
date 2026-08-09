package com.openmason.main.systems.menus.dialogs;

import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Small shared ImGui idioms for the SBO/SBE editor sub-sections, so every tab
 * formats sizes, errors and destructive buttons the same way instead of each
 * section carrying its own copy.
 */
final class EditorWidgets {

    private EditorWidgets() {
    }

    /** "512 B" / "12.3 KB" / "4.0 MB". */
    static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    /** Red-tinted destructive button; returns true when clicked. */
    static boolean dangerButton(String label, float width) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
        boolean clicked = width > 0f ? ImGui.button(label, width, 0.0f) : ImGui.button(label);
        ImGui.popStyleColor();
        return clicked;
    }

    /** Inline per-row validation error, indented under the row it flags. */
    static void inlineError(String error) {
        ImGui.textColored(1.0f, 0.55f, 0.45f, 1.0f, "  " + error);
    }

    /** Dim uppercase group heading with breathing room, for long form tabs. */
    static void sectionLabel(String label) {
        ImGui.dummy(0, 6);
        ImGui.textDisabled(label.toUpperCase());
        ImGui.separator();
        ImGui.dummy(0, 2);
    }

    /**
     * One labelled asset slot ("Model:", "Clip:") with source name + size and
     * Set/Replace/Clear buttons. Widget IDs are scoped by {@code label} so two
     * slots in the same row never collide (ImGui would route every click to
     * the first slot otherwise).
     */
    static void assetSlot(String label, String sourceLabel, byte[] bytes,
                          Runnable onPick, Runnable onClear) {
        ImGui.pushID(label);
        ImGui.indent(20.0f);
        ImGui.textDisabled(label);
        ImGui.sameLine(80.0f);

        if (bytes != null) {
            ImGui.text(sourceLabel != null ? sourceLabel : "(loaded)");
            ImGui.sameLine();
            ImGui.textDisabled("(" + humanBytes(bytes.length) + ")");
            ImGui.sameLine();
            if (ImGui.smallButton("Replace...")) onPick.run();
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) onClear.run();
        } else {
            ImGui.textDisabled("(unset)");
            ImGui.sameLine();
            if (ImGui.smallButton("Set...")) onPick.run();
        }
        ImGui.unindent(20.0f);
        ImGui.popID();
    }
}
