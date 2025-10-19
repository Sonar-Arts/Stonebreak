package com.openmason.ui;

/**
 * Represents a tool option displayed in the welcome screen.
 * Immutable data class following SOLID principles.
 */
public class ToolCard {

    private final String name;
    private final String description;
    private final String iconPath;
    private final boolean enabled;
    private final Runnable onSelectAction;

    /**
     * Create a tool card.
     *
     * @param name The display name of the tool
     * @param description A brief description of what the tool does
     * @param iconPath Path to the tool's icon (can be null for no icon)
     * @param enabled Whether the tool is currently available
     * @param onSelectAction Action to execute when the tool is selected
     */
    public ToolCard(String name, String description, String iconPath, boolean enabled, Runnable onSelectAction) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        if (description == null) {
            throw new IllegalArgumentException("Tool description cannot be null");
        }

        this.name = name;
        this.description = description;
        this.iconPath = iconPath;
        this.enabled = enabled;
        this.onSelectAction = onSelectAction;
    }

    /**
     * Create an enabled tool card.
     */
    public ToolCard(String name, String description, Runnable onSelectAction) {
        this(name, description, null, true, onSelectAction);
    }

    /**
     * Create a disabled "Coming Soon" tool card.
     */
    public static ToolCard comingSoon(String name, String description) {
        return new ToolCard(name, description, null, false, () -> {});
    }

    // Getters

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIconPath() {
        return iconPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean hasIcon() {
        return iconPath != null && !iconPath.trim().isEmpty();
    }

    /**
     * Execute the tool's selection action.
     * Only executes if the tool is enabled.
     */
    public void select() {
        if (enabled && onSelectAction != null) {
            onSelectAction.run();
        }
    }
}
