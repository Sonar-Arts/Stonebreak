package com.stonebreak.ui.settingsMenu.config;

/**
 * Enumeration for category-based settings menu navigation.
 * Defines the main categories and the settings available within each category.
 */
public enum CategoryState {
    // ===== CATEGORIES =====
    GENERAL(0, new SettingType[]{SettingType.RESOLUTION}),
    QUALITY(1, new SettingType[]{SettingType.LEAF_TRANSPARENCY}),
    PERFORMANCE(2, new SettingType[]{}), // Extensible for future performance settings
    ADVANCED(3, new SettingType[]{SettingType.ARM_MODEL}),
    EXTRAS(4, new SettingType[]{SettingType.CROSSHAIR_STYLE, SettingType.CROSSHAIR_SIZE}),
    AUDIO(5, new SettingType[]{SettingType.VOLUME});
    
    private final int index;
    private final SettingType[] settings;
    
    CategoryState(int index, SettingType[] settings) {
        this.index = index;
        this.settings = settings;
    }
    
    public int getIndex() {
        return index;
    }
    
    public SettingType[] getSettings() {
        return settings;
    }
    
    public String getDisplayName() {
        return name().substring(0, 1).toUpperCase() + name().substring(1).toLowerCase();
    }
    
    /**
     * Gets a CategoryState by its index value.
     * @param index the index to look up
     * @return the corresponding CategoryState, or null if not found
     */
    public static CategoryState fromIndex(int index) {
        for (CategoryState category : values()) {
            if (category.index == index) {
                return category;
            }
        }
        return null;
    }
    
    /**
     * Gets the maximum valid index for category selection.
     * @return the highest category index
     */
    public static int getMaxIndex() {
        return AUDIO.getIndex();
    }
    
    /**
     * Gets the minimum valid index for category selection.
     * @return the lowest category index (always 0)
     */
    public static int getMinIndex() {
        return GENERAL.getIndex();
    }
    
    /**
     * Enumeration for individual setting types within categories.
     */
    public enum SettingType {
        RESOLUTION(0),
        VOLUME(1),
        ARM_MODEL(2),
        CROSSHAIR_STYLE(3),
        CROSSHAIR_SIZE(4),
        LEAF_TRANSPARENCY(5),
        APPLY(6),
        BACK(7);
        
        private final int index;
        
        SettingType(int index) {
            this.index = index;
        }
        
        public int getIndex() {
            return index;
        }
        
        /**
         * Gets a SettingType by its index value.
         * @param index the index to look up
         * @return the corresponding SettingType, or null if not found
         */
        public static SettingType fromIndex(int index) {
            for (SettingType setting : values()) {
                if (setting.index == index) {
                    return setting;
                }
            }
            return null;
        }
    }
}