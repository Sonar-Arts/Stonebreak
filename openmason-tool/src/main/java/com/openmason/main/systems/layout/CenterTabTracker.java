package com.openmason.main.systems.layout;

/**
 * Remembers which centre-dock tab (Scene Viewer or Model Editor) the user last had in
 * front, so a project save can record it.
 *
 * <p>ImGui has no direct "which tab is selected" query at the app layer, but it tells
 * each window whether it was drawn: a docked window whose {@code begin()} returned false
 * is a background tab. Both centre views publish that per-frame visibility (and their
 * focus) into their UI state, and the app feeds it here once per frame.
 *
 * <p>Focus beats visibility because it is the stronger signal; when neither window is
 * focused and both or neither are visible (a split dock, a floating copy, the hub in
 * front), the last known answer stands rather than guessing.
 */
public final class CenterTabTracker {

    /** A fresh layout focuses the Scene Viewer, so that is the before-first-frame answer. */
    private CenterTab lastActive = CenterTab.SCENE_VIEWER;

    /** Call once per frame with what the two centre views reported. */
    public void noteFrame(boolean sceneVisible, boolean sceneFocused,
                          boolean modelVisible, boolean modelFocused) {
        if (sceneFocused) {
            lastActive = CenterTab.SCENE_VIEWER;
        } else if (modelFocused) {
            lastActive = CenterTab.MODEL_EDITOR;
        } else if (sceneVisible && !modelVisible) {
            lastActive = CenterTab.SCENE_VIEWER;
        } else if (modelVisible && !sceneVisible) {
            lastActive = CenterTab.MODEL_EDITOR;
        }
    }

    public CenterTab activeTab() {
        return lastActive;
    }
}
