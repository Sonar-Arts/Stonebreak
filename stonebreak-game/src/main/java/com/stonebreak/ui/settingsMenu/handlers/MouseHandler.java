package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Mouse dispatch for the settings menu. Pure event routing — every widget
 * owns its own hit testing and state mutation.
 */
public final class MouseHandler {

    private final StateManager stateManager;
    private ScrollableSettingsContainer scrollableContainer;

    public MouseHandler(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    public void setScrollableContainer(ScrollableSettingsContainer container) {
        this.scrollableContainer = container;
    }

    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        updateHoverStates((float) mouseX, (float) mouseY);
        stateManager.getVolumeSlider().handleDrag((float) mouseX);
        stateManager.getCrosshairSizeSlider().handleDrag((float) mouseX);
    }

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        if (action == GLFW_PRESS) {
            handlePress((float) mouseX, (float) mouseY);
        } else if (action == GLFW_RELEASE) {
            stateManager.getVolumeSlider().stopDragging();
            stateManager.getCrosshairSizeSlider().stopDragging();
        }
    }

    public boolean handleMouseWheel(double mouseX, double mouseY, double scrollDelta) {
        if (scrollableContainer == null) return false;
        return scrollableContainer.handleMouseWheel((float) mouseX, (float) mouseY, (float) scrollDelta);
    }

    // ─────────────────────────────────────────────── Internals

    private void updateHoverStates(float mouseX, float mouseY) {
        for (MCategoryButton<CategoryState> button : stateManager.getCategoryButtons()) {
            button.setHovered(button.contains(mouseX, mouseY));
        }
        stateManager.getResolutionButton().updateHover(mouseX, mouseY);
        stateManager.getArmModelButton().updateHover(mouseX, mouseY);
        stateManager.getCrosshairStyleButton().updateHover(mouseX, mouseY);
        stateManager.getLeafTransparencyButton().updateHover(mouseX, mouseY);
        stateManager.getWaterShaderButton().updateHover(mouseX, mouseY);
        stateManager.getApplyButton().updateHover(mouseX, mouseY);
        stateManager.getBackButton().updateHover(mouseX, mouseY);
        stateManager.getVolumeSlider().updateHover(mouseX, mouseY);
        stateManager.getCrosshairSizeSlider().updateHover(mouseX, mouseY);
    }

    private void handlePress(float mouseX, float mouseY) {
        // Open dropdowns consume clicks first (they own an overlay hit area).
        if (routeClickToOpenDropdown(mouseX, mouseY)) return;

        for (MCategoryButton<CategoryState> button : stateManager.getCategoryButtons()) {
            if (button.handleClick(mouseX, mouseY)) return;
        }

        CategoryState category = stateManager.getSelectedCategory();
        CategoryState.SettingType[] settings = category.getSettings();
        for (int i = 0; i < settings.length; i++) {
            if (dispatchSettingClick(settings[i], mouseX, mouseY)) {
                stateManager.setSelectedSettingInCategory(i);
                return;
            }
        }

        if (stateManager.getApplyButton().handleClick(mouseX, mouseY)) {
            stateManager.setSelectedSettingInCategory(settings.length);
            return;
        }
        if (stateManager.getBackButton().handleClick(mouseX, mouseY)) {
            stateManager.setSelectedSettingInCategory(settings.length + 1);
        }
    }

    private boolean routeClickToOpenDropdown(float mouseX, float mouseY) {
        // An open dropdown grows its hit area downward; route the click there first
        // so clicking a list item doesn't fall through to the widget behind it.
        if (stateManager.getResolutionButton().isOpen()) {
            return stateManager.getResolutionButton().handleClick(mouseX, mouseY);
        }
        if (stateManager.getArmModelButton().isOpen()) {
            return stateManager.getArmModelButton().handleClick(mouseX, mouseY);
        }
        if (stateManager.getCrosshairStyleButton().isOpen()) {
            return stateManager.getCrosshairStyleButton().handleClick(mouseX, mouseY);
        }
        return false;
    }

    private boolean dispatchSettingClick(CategoryState.SettingType type, float mouseX, float mouseY) {
        return switch (type) {
            case RESOLUTION        -> stateManager.getResolutionButton().handleClick(mouseX, mouseY);
            case VOLUME            -> stateManager.getVolumeSlider().handleClick(mouseX, mouseY);
            case ARM_MODEL         -> stateManager.getArmModelButton().handleClick(mouseX, mouseY);
            case CROSSHAIR_STYLE   -> stateManager.getCrosshairStyleButton().handleClick(mouseX, mouseY);
            case CROSSHAIR_SIZE    -> stateManager.getCrosshairSizeSlider().handleClick(mouseX, mouseY);
            case LEAF_TRANSPARENCY -> stateManager.getLeafTransparencyButton().handleClick(mouseX, mouseY);
            case WATER_SHADER      -> stateManager.getWaterShaderButton().handleClick(mouseX, mouseY);
            default -> false;
        };
    }
}
