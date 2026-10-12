package com.openmason.main.systems.layout;

/**
 * The top-level workspaces of the editor shell. Each has its own dockspace and panels; the
 * main window's workspace tabs switch between them and the project file records which one was
 * in front. The stored names stay {@code MODELING}/{@code UI} so older projects resolve.
 *
 * <p>A closable workspace (the Texture Editor) only has a tab while it is open; the others are
 * always there.
 */
public enum Workspace {
    MODELING("Scene", "Models, textures and scenes", false, 720f, 480f),
    UI("UI", "Game UI screens and components", false, 720f, 480f),
    // Minimum size keeps the fixed left chrome (Color 320 + Tools 48) plus a workable canvas and Layers column.
    TEXTURE("Texture", "Texture Editor: paint .omt textures and per-face model textures", true, 1100f, 700f);

    private final String label;
    private final String description;
    private final boolean closable;
    private final float minWidth;
    private final float minHeight;

    Workspace(String label, String description, boolean closable, float minWidth, float minHeight) {
        this.label = label;
        this.description = description;
        this.closable = closable;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    /** The tab's caption. */
    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** True when the tab opens and closes on demand (it has a close button) instead of always being there. */
    public boolean closable() {
        return closable;
    }

    /** Smallest size of the workspace's own window when popped out. */
    public float minWidth() {
        return minWidth;
    }

    public float minHeight() {
        return minHeight;
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
