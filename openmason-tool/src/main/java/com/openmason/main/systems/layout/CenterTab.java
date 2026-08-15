package com.openmason.main.systems.layout;

/**
 * Which window sits in front in the centre dock node.
 *
 * <p>Recorded per project so reopening one restores what the user was doing, rather than
 * the app guessing.
 */
public enum CenterTab {

    SCENE_VIEWER,
    MODEL_EDITOR;

    /**
     * Resolve the tab to focus.
     *
     * <p>Precedence is deliberate: an explicit value stored in the project always wins,
     * including over a forced layout rebuild. A rebuild's job is to make the new tab
     * <em>exist</em>, not to yank an upgrading user away from the editor they were last
     * using. Only a project that has never recorded a choice falls through to the
     * default, which is why a brand-new project opens on the Scene Viewer and an existing
     * one does not move.
     *
     * @param storedValue    value from the project file, or null when absent
     * @param defaultForNew  what a project with no recorded choice should use
     */
    public static CenterTab resolve(String storedValue, CenterTab defaultForNew) {
        if (storedValue != null && !storedValue.isBlank()) {
            try {
                return CenterTab.valueOf(storedValue.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Unrecognised value (hand-edited or from a newer build): fall through
                // rather than fail the project load.
                return MODEL_EDITOR;
            }
        }
        return defaultForNew != null ? defaultForNew : MODEL_EDITOR;
    }

    /** ImGui window title for this tab. */
    public String windowTitle() {
        return this == SCENE_VIEWER
                ? com.openmason.main.systems.scene.views.SceneViewerMainView.WINDOW_TITLE
                : com.openmason.main.systems.viewport.views.ViewportMainView.WINDOW_TITLE;
    }
}
