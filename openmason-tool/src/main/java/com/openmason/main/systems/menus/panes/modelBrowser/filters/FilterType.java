package com.openmason.main.systems.menus.panes.modelBrowser.filters;

/**
 * Filter options for the Model Browser. The browser shows a flat collection of
 * .OMO models and .SBT textures discovered on disk; this filter narrows the
 * displayed type.
 */
public enum FilterType {
    ALL("All Assets"),
    OMO_ONLY(".OMO Models"),
    SBT_ONLY(".SBT Textures"),
    RECENT_FILES("Recent Files");

    private final String displayName;

    FilterType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean showOMO() {
        return this == ALL || this == OMO_ONLY;
    }

    public boolean showSBT() {
        return this == ALL || this == SBT_ONLY;
    }

    public boolean showRecentOnly() {
        return this == RECENT_FILES;
    }

    public static String[] getDisplayNames() {
        FilterType[] types = values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName;
        }
        return names;
    }
}
