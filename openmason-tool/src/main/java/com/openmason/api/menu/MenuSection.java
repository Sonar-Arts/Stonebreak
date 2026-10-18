package com.openmason.api.menu;

import java.util.Objects;

/**
 * Immediate-mode menu content drawn by its owner inside an open menu (ImGui {@code menuItem}s,
 * submenus, separators): for groups of items whose labels, states or structure change at runtime.
 *
 * @param render draws the section; runs only while the menu is open
 */
public record MenuSection(String id, String menuId, int order, Runnable render) implements MenuEntry {

    public MenuSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(menuId, "menuId");
        Objects.requireNonNull(render, "render");
    }
}
