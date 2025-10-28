package com.openmason.ui.components.textureCreator.tools.move.modules;

/**
 * Enum representing the different transformation handles.
 * Each handle type knows its transformation behavior.
 */
public enum HandleType {
    // Corner handles - scale in both dimensions, can maintain aspect ratio
    TOP_LEFT(true, true, true),
    TOP_RIGHT(true, true, true),
    BOTTOM_LEFT(true, true, true),
    BOTTOM_RIGHT(true, true, true),

    // Edge handles - scale in one dimension only
    TOP_CENTER(false, true, false),
    BOTTOM_CENTER(false, true, false),
    MIDDLE_LEFT(true, false, false),
    MIDDLE_RIGHT(true, false, false),

    // Special handle - rotation
    ROTATION(false, false, false);

    private final boolean affectsScaleX;
    private final boolean affectsScaleY;
    private final boolean isCorner;

    HandleType(boolean affectsScaleX, boolean affectsScaleY, boolean isCorner) {
        this.affectsScaleX = affectsScaleX;
        this.affectsScaleY = affectsScaleY;
        this.isCorner = isCorner;
    }

    public boolean affectsScaleX() {
        return affectsScaleX;
    }

    public boolean affectsScaleY() {
        return affectsScaleY;
    }

    public boolean isCorner() {
        return isCorner;
    }

    public boolean isEdge() {
        return !isCorner && this != ROTATION;
    }

    public boolean isRotation() {
        return this == ROTATION;
    }

    /**
     * Returns the cursor direction for this handle.
     * Used for visual feedback.
     */
    public String getCursorName() {
        switch (this) {
            case TOP_LEFT:
            case BOTTOM_RIGHT:
                return "nwse-resize";
            case TOP_RIGHT:
            case BOTTOM_LEFT:
                return "nesw-resize";
            case TOP_CENTER:
            case BOTTOM_CENTER:
                return "ns-resize";
            case MIDDLE_LEFT:
            case MIDDLE_RIGHT:
                return "ew-resize";
            case ROTATION:
                return "crosshair";
            default:
                return "default";
        }
    }
}
