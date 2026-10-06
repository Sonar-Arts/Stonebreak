package com.openmason.main.systems.layout;

/**
 * The top-level workspaces of the editor shell. Each has its own dockspace and panels; the
 * menu bar tabs switch between them and the project file records which one was in front.
 */
public enum Workspace {
    MODELING("Modeling"),
    UI("UI Editor");

    private final String label;

    Workspace(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Stored value to workspace; unknown or absent values are Modeling, so old projects never move. */
    public static Workspace resolve(String stored) {
        if (stored != null) {
            for (Workspace w : values()) {
                if (w.name().equalsIgnoreCase(stored.trim())) {
                    return w;
                }
            }
        }
        return MODELING;
    }
}
