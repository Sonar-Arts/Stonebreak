package com.openmason.api.menu;

import java.util.Objects;

/**
 * A top-level entry of the main menu bar ({@code File}, {@code Edit}, ...). Menus sort by
 * {@link #order()} (ties: registration order); built-ins use File 100, Edit 200, View 300,
 * Tools 400, About 900.
 *
 * @param id      stable id, referenced by {@link MenuEntry#menuId()}
 * @param label   the caption
 * @param order   position on the bar
 * @param onClick when set, the entry is a plain clickable item instead of a drop-down menu
 */
public record TopMenu(String id, String label, int order, Runnable onClick) {

    public TopMenu {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
    }

    /** A drop-down menu. */
    public static TopMenu of(String id, String label, int order) {
        return new TopMenu(id, label, order, null);
    }

    /** Ids of the built-in menus. */
    public static final String FILE = "file";
    public static final String EDIT = "edit";
    public static final String VIEW = "view";
    public static final String TOOLS = "tools";
    public static final String ABOUT = "about";
}
