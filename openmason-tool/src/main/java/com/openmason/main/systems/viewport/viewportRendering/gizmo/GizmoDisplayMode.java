package com.openmason.main.systems.viewport.viewportRendering.gizmo;

/**
 * Controls how the transform gizmo is shown/hidden in the viewport.
 */
public enum GizmoDisplayMode {

    /**
     * User explicitly toggles the gizmo on/off with Ctrl+T or the checkbox.
     */
    MANUAL_TOGGLE("Manual Toggle"),

    /**
     * Gizmo automatically appears when model parts are selected,
     * and hides when no parts are selected.
     */
    AUTO_SHOW_ON_SELECT("Auto-Show on Selection");

    private final String displayName;

    GizmoDisplayMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parses a display mode from its stored string value.
     * Returns the default (AUTO_SHOW_ON_SELECT) if the value is unrecognized.
     */
    public static GizmoDisplayMode fromString(String value) {
        if (value != null) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return AUTO_SHOW_ON_SELECT;
    }
}
