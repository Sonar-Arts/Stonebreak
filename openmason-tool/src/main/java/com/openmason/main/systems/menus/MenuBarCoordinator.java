package com.openmason.main.systems.menus;

import com.openmason.api.Contributions;
import com.openmason.api.menu.MenuEntry;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.MenuSection;
import com.openmason.api.menu.TopMenu;
import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.icons.MenuBarIconManager;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * The main menu bar, drawn from the menu contributions (#379): every registered {@link TopMenu},
 * by order, with its {@link MenuEntry entries} by order (ties keep registration order). Built-in
 * File/Edit/View menus are {@link MenuSection}s over their handlers; plugins add menus or items.
 * Right-aligned: a toolbar-restore button and the Home button.
 */
public class MenuBarCoordinator extends BaseMenuBarRenderer {

    private static final Logger logger = LoggerFactory.getLogger(MenuBarCoordinator.class);

    private static final float HOME_ICON_DISPLAY_SIZE = 16.0f;

    private final UIVisibilityState uiState;
    private final Contributions<TopMenu> menus;
    private final Contributions<MenuEntry> entries;
    private Runnable homeAction;

    public MenuBarCoordinator(UIVisibilityState uiState, Contributions<TopMenu> menus,
                              Contributions<MenuEntry> entries) {
        this.uiState = uiState;
        this.menus = menus;
        this.entries = entries;
    }

    /** What the Home button does. */
    public void setHomeAction(Runnable homeAction) {
        this.homeAction = homeAction;
    }

    @Override
    protected void renderMenuBarContent() {
        for (TopMenu menu : sorted(menus.all(), Comparator.comparingInt(TopMenu::order))) {
            if (menu.onClick() != null) {
                if (ImGui.menuItem(menu.label())) {
                    run(menu.id(), menu.onClick());
                }
                continue;
            }
            List<MenuEntry> inMenu = entriesOf(menu.id());
            if (inMenu.isEmpty()) {
                continue;
            }
            if (ImGui.beginMenu(menu.label())) {
                for (MenuEntry entry : inMenu) {
                    renderEntry(entry);
                }
                ImGui.endMenu();
            }
        }

        // Spacer + right-aligned items
        renderRightAlignedItems();
    }

    /** {@code menuId}'s entries in display order. */
    public List<MenuEntry> entriesOf(String menuId) {
        return sorted(entries.all().stream().filter(e -> e.menuId().equals(menuId)).toList(),
                Comparator.comparingInt(MenuEntry::order));
    }

    private void renderEntry(MenuEntry entry) {
        try {
            switch (entry) {
                case MenuSection section -> section.render().run();
                case MenuItem item -> renderItem(item);
            }
        } catch (RuntimeException e) {
            logger.error("Menu entry {} failed to render", entry.id(), e);
        }
    }

    private void renderItem(MenuItem item) {
        if (item.separatorBefore()) {
            ImGui.separator();
        }
        String shortcut = item.commandId() != null
                ? KeybindRegistry.getInstance().getShortcutDisplayName(item.commandId()) : "";
        boolean enabled = item.enabled() == null || item.enabled().getAsBoolean();
        boolean checked = item.checked() != null && item.checked().getAsBoolean();
        if (ImGui.menuItem(item.label(), shortcut, checked, enabled)) {
            if (item.action() != null) {
                run(item.id(), item.action());
            } else {
                KeybindAction command = KeybindRegistry.getInstance().getAction(item.commandId());
                if (command != null) {
                    run(item.id(), command::execute);
                } else {
                    logger.warn("Menu item {} runs unknown command {}", item.id(), item.commandId());
                }
            }
        }
    }

    private static void run(String id, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.error("Menu command {} failed", id, e);
        }
    }

    /** Stable sort: equal orders keep registration order. */
    private static <T> List<T> sorted(List<T> list, Comparator<T> by) {
        return list.stream().sorted(by).toList();
    }

    /**
     * Render right-aligned items: Home button and toolbar restore.
     */
    private void renderRightAlignedItems() {
        float availWidth = ImGui.getContentRegionAvailX();

        // Calculate total width of right-aligned items
        // imageButton adds FramePadding on each side
        float framePadX = ImGui.getStyle().getFramePaddingX();
        float homeButtonWidth = HOME_ICON_DISPLAY_SIZE + framePadX * 2;
        float toolbarButtonWidth = 0.0f;
        boolean showToolbarRestore = !uiState.getShowToolbar().get();
        if (showToolbarRestore) {
            toolbarButtonWidth = ImGui.calcTextSize("Show Toolbar").x + framePadX * 2 + 8.0f;
        }

        float totalRightWidth = homeButtonWidth + toolbarButtonWidth;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - totalRightWidth);

        // Toolbar restore button
        if (showToolbarRestore) {
            if (ImGui.button("Show Toolbar")) {
                uiState.getShowToolbar().set(true);
            }
            ImGui.sameLine(0, 8.0f);
        }

        // Home button with SVG icon
        renderHomeButton();
    }

    /**
     * Render the Home Screen button on the menu bar using the SVG icon.
     * Falls back to text if the icon failed to load.
     */
    private void renderHomeButton() {
        int textureId = MenuBarIconManager.getInstance().getHomeIconTexture();

        pushTransparentButtonStyle();

        boolean clicked;
        if (textureId != -1) {
            // The icon is a white mask; tint it with the theme text color so it
            // stays visible on light themes.
            ImVec4 tint = ImGui.getStyle().getColor(ImGuiCol.Text);
            clicked = ImGui.imageButton("##homeScreen", textureId,
                    HOME_ICON_DISPLAY_SIZE, HOME_ICON_DISPLAY_SIZE,
                    0f, 0f, 1f, 1f,
                    0f, 0f, 0f, 0f,
                    tint.x, tint.y, tint.z, tint.w);
        } else {
            // Fallback to text if SVG failed to load
            clicked = ImGui.button("Home");
        }

        popTransparentButtonStyle();

        if (clicked && homeAction != null) {
            homeAction.run();
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Home Screen");
        }
    }
}
