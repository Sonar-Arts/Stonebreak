package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;

/**
 * The one modal-dialog contract for Open Mason (issue #275).
 *
 * <ul>
 *   <li>Title Case titles with a {@code ##id} suffix, no question marks.</li>
 *   <li>{@code AlwaysAutoResize | NoSavedSettings}, centred (pivot 0.5, on
 *       {@code Appearing}) on the viewport that owns the dialog - never
 *       {@code io.displaySize}, which is wrong with multi-viewport.</li>
 *   <li>Escape = Cancel, Enter = primary (only when the primary is enabled,
 *       and not while a text field is capturing input unless the dialog asks
 *       for it).</li>
 *   <li>Button row: primary (verb label) on the left, secondaries, then
 *       "Cancel" last; one 110x26 size, widened only for long labels.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * if (ModalDialogs.begin(POPUP_ID, 420)) {
 *     ... body ...
 *     ModalDialogs.buttonsBegin();
 *     if (ModalDialogs.danger("Delete", true)) { ...; ModalDialogs.close(); }
 *     if (ModalDialogs.cancel()) { ...; ModalDialogs.close(); }
 *     ModalDialogs.end();
 * }
 * </pre>
 */
public final class ModalDialogs {

    /** Standard modal button width. */
    public static final float BTN_W = 110f;
    /** Standard modal button height (matches EditorChrome's button height). */
    public static final float BTN_H = 26f;
    private static final float LABEL_PAD = 16f;
    private static final int FLAGS = ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings;

    /** True until the first button of the current row is drawn. */
    private static boolean rowFirst = true;
    /** Enter is ignored on frames before this one (set when a modal appears). */
    private static int enterBlockedUntilFrame = 0;

    private ModalDialogs() {
    }

    /** Where a modal centres: the centre of the viewport that owns it. */
    public record Anchor(float x, float y) {
        /**
         * The viewport of the window currently being submitted (so a dialog
         * opened from a popped-out window centres there), else the main viewport.
         */
        public static Anchor capture() {
            ImGuiViewport vp = ImGui.getWindowViewport();
            if (vp == null) {
                vp = ImGui.getMainViewport();
            }
            return new Anchor(vp.getCenterX(), vp.getCenterY());
        }
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    /** Begin a modal centred on the current window's viewport. See {@link #begin(String, Anchor, float)}. */
    public static boolean begin(String popupId, float minContentWidth) {
        return begin(popupId, Anchor.capture(), minContentWidth);
    }

    /**
     * Begin the modal popup {@code popupId} (must be Title Case with a {@code ##id}).
     * The caller is responsible for {@code ImGui.openPopup} (see {@link #openIfNeeded}).
     *
     * @param anchor          centre point, or null for the current viewport
     * @param minContentWidth minimum window width; wrapped text sizes against it
     * @return true when the popup is visible; then call {@link #end()}
     */
    public static boolean begin(String popupId, Anchor anchor, float minContentWidth) {
        Anchor a = anchor != null ? anchor : Anchor.capture();
        ImGui.setNextWindowPos(a.x(), a.y(), ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSizeConstraints(minContentWidth, 0f, 10000f, 10000f);
        boolean visible = ImGui.beginPopupModal(popupId, FLAGS);
        if (visible && ImGui.isWindowAppearing()) {
            // The Enter that opened (or confirmed the previous) dialog must not also confirm this one.
            enterBlockedUntilFrame = ImGui.getFrameCount() + 1;
        }
        return visible;
    }

    /** Open the popup if it is not already open (idempotent, call every frame while showing). */
    public static void openIfNeeded(String popupId) {
        if (!ImGui.isPopupOpen(popupId)) {
            ImGui.openPopup(popupId);
        }
    }

    public static void end() {
        ImGui.endPopup();
    }

    public static void close() {
        ImGui.closeCurrentPopup();
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    /** Separator + spacing, and start a new button row. */
    public static void buttonsBegin() {
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
        rowFirst = true;
    }

    /**
     * Accent primary button. Returns true when clicked or when Enter is pressed
     * (Enter only counts when {@code enabled}).
     */
    public static boolean primary(String label, boolean enabled) {
        return primary(label, enabled, false);
    }

    /** As {@link #primary(String, boolean)}; {@code enterWhileTyping} lets Enter fire from a single-line field. */
    public static boolean primary(String label, boolean enabled, boolean enterWhileTyping) {
        return styled(label, enabled, enterWhileTyping, false);
    }

    /** Accent primary that only reacts to clicks (the dialog handles Enter itself). */
    public static boolean primaryClickOnly(String label) {
        beforeButton();
        return ThemedWidgets.accentButton(label, widthFor(ImGui.calcTextSize(label).x), BTN_H);
    }

    /** Destructive primary (Delete / Discard / Reassign); same key handling as {@link #primary}. */
    public static boolean danger(String label, boolean enabled) {
        return styled(label, enabled, false, true);
    }

    /** Plain secondary action ("Don't Save", "Deny"). Never triggered by a key. */
    public static boolean secondary(String label) {
        beforeButton();
        return ImGui.button(label, widthFor(ImGui.calcTextSize(label).x), BTN_H);
    }

    /** "Cancel" - true when clicked or Escape is pressed. */
    public static boolean cancel() {
        return cancel("Cancel");
    }

    /** Last-in-row dismiss button with a custom label; Escape also triggers it. */
    public static boolean cancel(String label) {
        beforeButton();
        boolean clicked = ImGui.button(label, widthFor(ImGui.calcTextSize(label).x), BTN_H);
        return clicked || escapePressed();
    }

    /** Single "Close" for informational dialogs: clicked, Escape or Enter. */
    public static boolean closeButton() {
        beforeButton();
        boolean clicked = ImGui.button("Close", BTN_W, BTN_H);
        return clicked || escapePressed() || enterPressed(false);
    }

    private static boolean styled(String label, boolean enabled, boolean enterWhileTyping, boolean danger) {
        beforeButton();
        if (!enabled) {
            ImGui.beginDisabled();
        }
        float w = widthFor(ImGui.calcTextSize(label).x);
        boolean clicked = danger ? ThemedWidgets.dangerButton(label, w, BTN_H)
                : ThemedWidgets.accentButton(label, w, BTN_H);
        if (!enabled) {
            ImGui.endDisabled();
        }
        return enabled && (clicked || enterPressed(enterWhileTyping));
    }

    private static void beforeButton() {
        if (!rowFirst) {
            ImGui.sameLine();
        }
        rowFirst = false;
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** Escape pressed while this modal is focused (a nested combo/popup keeps its own Escape). */
    public static boolean escapePressed() {
        return dialogFocused() && ImGui.isKeyPressed(ImGuiKey.Escape, false);
    }

    /**
     * Enter / Keypad Enter pressed this frame (no key repeat, so holding Enter
     * cannot confirm a destructive action). Suppressed while a text field is
     * capturing input unless {@code whileTyping}, so a multiline field keeps its Enter.
     */
    public static boolean enterPressed(boolean whileTyping) {
        if (!dialogFocused() || ImGui.getFrameCount() < enterBlockedUntilFrame) {
            return false;
        }
        if (!whileTyping && ImGui.getIO().getWantTextInput()) {
            return false;
        }
        return ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false);
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    /** Button width for a label of the given pixel width: 110 unless the label needs more. */
    public static float widthFor(float textWidth) {
        return Math.max(BTN_W, textWidth + LABEL_PAD * 2f);
    }

    /**
     * Whether {@code title} follows the modal title convention: a non-empty
     * Title Case name (each word capitalised, small words excepted), no
     * question mark, and a {@code ##id} suffix.
     */
    public static boolean isValidPopupId(String popupId) {
        if (popupId == null) {
            return false;
        }
        int hash = popupId.indexOf("##");
        if (hash <= 0 || hash + 2 >= popupId.length()) {
            return false;
        }
        String title = popupId.substring(0, hash);
        if (title.contains("?")) {
            return false;
        }
        for (String word : title.split(" ")) {
            if (word.isEmpty()) {
                return false;
            }
            boolean small = word.equals("a") || word.equals("an") || word.equals("the") || word.equals("to")
                    || word.equals("of") || word.equals("and") || word.equals("or") || word.equals("for")
                    || word.equals("in") || word.equals("on");
            if (!small && !Character.isUpperCase(word.charAt(0)) && !Character.isDigit(word.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The modal or one of its child windows (lists, scrolling tables) has focus.
     * Popups opened from the modal (combos) don't count, so their Escape stays theirs.
     */
    private static boolean dialogFocused() {
        return ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows | ImGuiFocusedFlags.NoPopupHierarchy);
    }
}
