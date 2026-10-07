package com.openmason.main.systems.layout;

/**
 * The top-level workspaces of the editor shell. Each has its own dockspace and panels; the
 * main window's workspace tabs switch between them and the project file records which one was
 * in front. The stored names stay {@code MODELING}/{@code UI} so older projects resolve.
 */
public enum Workspace {
    MODELING("Scene", "Models, textures and scenes"),
    UI("UI", "Game UI screens and components");

    private final String label;
    private final String description;

    Workspace(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** The tab's caption. */
    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The workspace with this stored name (case-insensitive), or null when there is none. */
    public static Workspace find(String stored) {
        if (stored != null) {
            for (Workspace w : values()) {
                if (w.name().equalsIgnoreCase(stored.trim())) {
                    return w;
                }
            }
        }
        return null;
    }

    /** Stored value to workspace; unknown or absent values are Modeling, so old projects never move. */
    public static Workspace resolve(String stored) {
        Workspace w = find(stored);
        return w != null ? w : MODELING;
    }
}
