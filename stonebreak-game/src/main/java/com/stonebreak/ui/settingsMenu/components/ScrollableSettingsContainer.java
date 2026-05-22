package com.stonebreak.ui.settingsMenu.components;

import com.stonebreak.rendering.UI.masonryUI.MScrollContainer;
import com.stonebreak.rendering.UI.masonryUI.MScrollMath;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Settings-menu-specific wrapper around {@link MScrollContainer}. Knows the
 * category layout (items stacked vertically with fixed spacing) and routes
 * mouse-wheel events to the active category's scroll math.
 *
 * The actual per-widget positioning + render happens in
 * {@link com.stonebreak.ui.settingsMenu.renderers.SkijaSettingsRenderer};
 * this class only owns the clip viewport and scroll math coupling.
 */
public final class ScrollableSettingsContainer {

    private final StateManager stateManager;
    private MScrollContainer container;
    private float itemSpacing = SettingsConfig.SCROLL_ITEM_SPACING;
    private float contentHeight;

    public ScrollableSettingsContainer(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    public void updateBounds(float centerX, float centerY, float panelHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        itemSpacing = SettingsConfig.getScaledScrollItemSpacing();

        float viewportW = SettingsConfig.getScaledSettingsPanelWidth();
        float viewportH = panelHeight - SettingsConfig.getScaledScrollTopMargin()
                - SettingsConfig.getScaledScrollBottomMargin();
        float viewportX = centerX + SettingsConfig.getScaledSettingsPanelXOffset() - viewportW / 2f;
        float viewportY = centerY - panelHeight / 2f + SettingsConfig.getScaledScrollTopMargin();

        CategoryState category = stateManager.getSelectedCategory();
        int count = category.getSettings().length;
        float topPadding = Math.max(SettingsConfig.getScaledScrollContentPadding(), 40f * s);
        contentHeight = Math.max(0f,
                topPadding + Math.max(0, count - 1) * itemSpacing
                        + SettingsConfig.getScaledButtonHeight() + SettingsConfig.getScaledScrollContentPadding());

        MScrollMath math = stateManager.getCurrentScrollMath();
        if (math != null) {
            math.updateBounds(viewportH, contentHeight);
        }

        if (container == null) {
            container = new MScrollContainer(math)
                    .scrollbarWidth(SettingsConfig.SCROLLBAR_WIDTH);
        } else {
            // Rebind container to the active category's scroll math.
            container = new MScrollContainer(math)
                    .scrollbarWidth(SettingsConfig.SCROLLBAR_WIDTH);
        }
        container.bounds(viewportX, viewportY, viewportW, viewportH);
    }

    public void render(MasonryUI ui, Runnable contentPainter) {
        if (container == null) return;
        container.render(ui, contentPainter);
    }

    public boolean handleMouseWheel(float mouseX, float mouseY, float scrollDelta) {
        return container != null && container.handleWheel(mouseX, mouseY, scrollDelta);
    }

    public float getContainerX()       { return container != null ? container.x() : 0f; }
    public float getContainerY()       { return container != null ? container.y() : 0f; }
    public float getContainerWidth()   { return container != null ? container.width() : 0f; }
    public float getContainerHeight()  { return container != null ? container.height() : 0f; }
    public float getContainerBottom()  { return container != null ? container.bottom() : 0f; }
    public float getContainerCenterX() { return container != null ? container.centerX() : 0f; }
    public float getScrollOffset()     {
        MScrollMath math = stateManager.getCurrentScrollMath();
        return math != null ? math.offset() : 0f;
    }
    public float getItemSpacing() { return itemSpacing; }
}
