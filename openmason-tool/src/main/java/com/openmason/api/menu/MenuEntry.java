package com.openmason.api.menu;

/**
 * Something inside a {@link TopMenu}: a {@link MenuItem} (a command) or a {@link MenuSection}
 * (immediate-mode content drawn by its owner). Entries sort by {@link #order()} within their menu;
 * ties keep registration order.
 */
public sealed interface MenuEntry permits MenuItem, MenuSection {

    /** Unique id across all menus. */
    String id();

    /** The {@link TopMenu#id()} this entry belongs to. */
    String menuId();

    int order();
}
