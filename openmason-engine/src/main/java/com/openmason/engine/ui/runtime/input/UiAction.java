package com.openmason.engine.ui.runtime.input;

/**
 * Device-independent UI actions (#288). Keys and controller buttons map to these through the
 * player's {@link UiActionMap}; {@code actionHints} in documents name them by {@link #id()}
 * ({@code "ui.submit"}), so a hint shows whatever the player bound.
 */
public enum UiAction {
    NAVIGATE_UP("ui.navigate.up", true),
    NAVIGATE_DOWN("ui.navigate.down", true),
    NAVIGATE_LEFT("ui.navigate.left", true),
    NAVIGATE_RIGHT("ui.navigate.right", true),
    /** Next in tab order (Tab, right bumper). */
    NEXT("ui.next", true),
    /** Previous in tab order (Shift+Tab, left bumper). */
    PREVIOUS("ui.previous", true),
    /** Activate the focused element (Enter, Space, controller A). Never auto-repeats. */
    SUBMIT("ui.submit", false),
    /** Back out: dismiss a popup, cancel a drag, revert a text edit, close a dialog. Never auto-repeats. */
    CANCEL("ui.cancel", false),
    PAGE_UP("ui.page.up", true),
    PAGE_DOWN("ui.page.down", true);

    private final String id;
    private final boolean repeats;

    UiAction(String id, boolean repeats) {
        this.id = id;
        this.repeats = repeats;
    }

    public String id() {
        return id;
    }

    /**
     * Held keys and buttons repeat this action. Confirm and back never do: a held key must not
     * answer the next prompt too (the Focus battle rule, kept for every screen).
     */
    public boolean repeats() {
        return repeats;
    }

    public boolean isDirection() {
        return this == NAVIGATE_UP || this == NAVIGATE_DOWN || this == NAVIGATE_LEFT || this == NAVIGATE_RIGHT;
    }

    public static UiAction fromId(String id) {
        for (UiAction a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}
