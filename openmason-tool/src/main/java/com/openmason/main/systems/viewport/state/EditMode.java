package com.openmason.main.systems.viewport.state;

/**
 * Enumeration of editing modes for mesh manipulation in the viewport.
 * Each mode restricts which geometry type can be selected and edited.
 */
public enum EditMode {
    NONE("None"),
    VERTEX("Vertex"),
    EDGE("Edge"),
    FACE("Face");

    private final String displayName;

    EditMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Gets the display name for UI overlay.
     *
     * @return Human-readable mode name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the next mode in the cycle: NONE -> VERTEX -> EDGE -> FACE -> NONE
     *
     * @return The next EditMode in sequence
     */
    public EditMode next() {
        EditMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
