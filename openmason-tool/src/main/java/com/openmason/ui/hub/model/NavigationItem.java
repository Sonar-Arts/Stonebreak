package com.openmason.ui.hub.model;

import java.util.Objects;

/**
 * Navigation sidebar item definition.
 * Represents a single navigation menu item with icon, label, and action.
 */
public class NavigationItem {

    private final String id;
    private final String label;
    private final String iconText;
    private final ViewType viewType;
    private final Runnable onSelectAction;

    /**
     * View types for different hub sections.
     */
    public enum ViewType {
        TEMPLATES,
        RECENT_PROJECTS,
        LEARN,
        SETTINGS
    }

    public NavigationItem(String id, String label, String iconText, ViewType viewType, Runnable onSelectAction) {
        this.id = Objects.requireNonNull(id, "Navigation ID cannot be null");
        this.label = Objects.requireNonNull(label, "Navigation label cannot be null");
        this.iconText = iconText != null ? iconText : "";
        this.viewType = Objects.requireNonNull(viewType, "View type cannot be null");
        this.onSelectAction = onSelectAction;
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getIconText() {
        return iconText;
    }

    public ViewType getViewType() {
        return viewType;
    }

    /**
     * Execute the selection action.
     */
    public void select() {
        if (onSelectAction != null) {
            onSelectAction.run();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NavigationItem that = (NavigationItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NavigationItem{" +
                "id='" + id + '\'' +
                ", label='" + label + '\'' +
                ", viewType=" + viewType +
                '}';
    }
}
