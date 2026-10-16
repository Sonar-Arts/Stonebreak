package com.openmason.api.menu;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A menu command.
 *
 * @param id               unique id
 * @param menuId           the menu it sits in ({@link TopMenu#TOOLS}, a plugin's own menu...)
 * @param order            position in the menu
 * @param label            the caption
 * @param commandId        a {@code KeybindRegistry} action id: its shortcut is shown next to the
 *                         item, and clicking runs that action when {@code action} is null; may be null
 * @param action           what a click does; null runs the {@code commandId} action
 * @param enabled          whether the item can be clicked now (null = always)
 * @param checked          checkmark state (null = no checkmark)
 * @param separatorBefore  draw a separator above the item
 */
public record MenuItem(String id, String menuId, int order, String label, String commandId, Runnable action,
                       BooleanSupplier enabled, BooleanSupplier checked, boolean separatorBefore)
        implements MenuEntry {

    public MenuItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(menuId, "menuId");
        Objects.requireNonNull(label, "label");
        if (action == null && commandId == null) {
            throw new IllegalArgumentException("Menu item " + id + " needs an action or a command id");
        }
    }

    /** A plain item that runs {@code action}. */
    public static MenuItem of(String id, String menuId, int order, String label, Runnable action) {
        return new MenuItem(id, menuId, order, label, null, action, null, null, false);
    }

    /** An item that runs the registered keybind command {@code commandId} (and shows its shortcut). */
    public static MenuItem command(String id, String menuId, int order, String label, String commandId) {
        return new MenuItem(id, menuId, order, label, commandId, null, null, null, false);
    }

    public MenuItem withEnabled(BooleanSupplier enabled) {
        return new MenuItem(id, menuId, order, label, commandId, action, enabled, checked, separatorBefore);
    }

    public MenuItem withChecked(BooleanSupplier checked) {
        return new MenuItem(id, menuId, order, label, commandId, action, enabled, checked, separatorBefore);
    }

    public MenuItem withSeparatorBefore() {
        return new MenuItem(id, menuId, order, label, commandId, action, enabled, checked, true);
    }
}
