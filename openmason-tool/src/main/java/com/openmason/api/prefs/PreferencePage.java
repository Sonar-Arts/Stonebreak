package com.openmason.api.prefs;

/**
 * A page of the Preferences window. Pages sort by {@link #order()} in the sidebar (built-ins:
 * Model Editor 100, Texture Editor 200, Folders 300, Keybinds 400, Assistant 500, Common 600,
 * Plugins 700).
 */
public interface PreferencePage {

    /** Unique id. */
    String id();

    /** Sidebar label and page heading. */
    String title();

    int order();

    /** Draws the page body (ImGui), every frame while it is the selected page. */
    void render();

    /** The window opened: load the current values into the page's edit state. */
    default void opened() {
    }

    /** "Apply"/"OK": commit the page's edit state. */
    default void apply() {
    }

    /** "Cancel": drop uncommitted edits. */
    default void cancel() {
    }

    /** "Reset to Defaults" for this page. */
    default void resetToDefaults() {
    }
}
