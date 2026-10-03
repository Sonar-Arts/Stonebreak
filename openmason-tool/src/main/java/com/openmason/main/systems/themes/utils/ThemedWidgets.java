package com.openmason.main.systems.themes.utils;

import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import imgui.ImGui;

/**
 * The shared ImGui idioms for status and action colors: destructive and
 * primary buttons, inline validation errors, status text and group headings.
 * Every color comes from the active theme ({@link ThemeColors}), so call
 * sites never carry their own red/green/orange literals.
 *
 * <p>The Skija/Mortar counterparts are the {@code error}, {@code warning},
 * {@code success}, {@code danger} and {@code onAccent} tokens on
 * {@link com.openmason.main.systems.mortar.theme.MortarTheme}.</p>
 */
public final class ThemedWidgets {

    private ThemedWidgets() {
    }

    /**
     * Opaque destructive button (the primary of a Delete/Discard confirm);
     * returns true when clicked. {@code width}/{@code height} of 0 size to the label.
     */
    public static boolean dangerButton(String label, float width, float height) {
        ThemeColors.pushDangerButton();
        boolean clicked = button(label, width, height);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /**
     * Subtle red-tinted destructive button for list-row actions ("Remove");
     * returns true when clicked. {@code width}/{@code height} of 0 size to the label.
     */
    public static boolean dangerSoftButton(String label, float width, float height) {
        ThemeColors.pushDangerSoftButton();
        boolean clicked = button(label, width, height);
        ImGui.popStyleColor(3);
        return clicked;
    }

    /**
     * Opaque accent (primary action) button with contrast-picked text;
     * returns true when clicked. {@code width}/{@code height} of 0 size to the label.
     */
    public static boolean accentButton(String label, float width, float height) {
        ThemeColors.pushAccentButton();
        boolean clicked = button(label, width, height);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /** Inline per-row validation error, indented under the field it flags. */
    public static void inlineError(String error) {
        statusText(Tone.ERROR, "  " + error);
    }

    /** One line of text in a status tone (error / warning / success). */
    public static void statusText(Tone tone, String text) {
        ThemeColors.push(imgui.flag.ImGuiCol.Text, tone);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    /** Wrapped text in a status tone, for multi-line messages. */
    public static void statusTextWrapped(Tone tone, String text) {
        ThemeColors.push(imgui.flag.ImGuiCol.Text, tone);
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }

    /** Dim uppercase group heading with breathing room, for long form tabs. */
    public static void sectionLabel(String label) {
        ImGui.dummy(0, 6);
        ImGui.textDisabled(label.toUpperCase());
        ImGui.separator();
        ImGui.dummy(0, 2);
    }

    private static boolean button(String label, float width, float height) {
        return (width > 0f || height > 0f) ? ImGui.button(label, width, height) : ImGui.button(label);
    }
}
