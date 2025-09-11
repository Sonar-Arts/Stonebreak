package com.stonebreak.ui.settingsMenu.config;

/**
 * Enumeration for button selection indices in the settings menu.
 * Provides navigation and selection management for UI components.
 */
public enum ButtonSelection {
    RESOLUTION(0),
    VOLUME(1),
    ARM_MODEL(2),
    CROSSHAIR_STYLE(3),
    CROSSHAIR_SIZE(4),
    APPLY(5),
    BACK(6);
    
    private final int index;
    
    ButtonSelection(int index) {
        this.index = index;
    }
    
    public int getIndex() {
        return index;
    }
    
    /**
     * Gets a ButtonSelection by its index value.
     * @param index the index to look up
     * @return the corresponding ButtonSelection, or null if not found
     */
    public static ButtonSelection fromIndex(int index) {
        for (ButtonSelection button : values()) {
            if (button.index == index) {
                return button;
            }
        }
        return null;
    }
    
    /**
     * Gets the maximum valid index for button selection.
     * @return the highest button index
     */
    public static int getMaxIndex() {
        return BACK.getIndex();
    }
    
    /**
     * Gets the minimum valid index for button selection.
     * @return the lowest button index (always 0)
     */
    public static int getMinIndex() {
        return RESOLUTION.getIndex();
    }
}