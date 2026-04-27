package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.masonryUI.MDropdown;
import com.stonebreak.rendering.UI.masonryUI.MSlider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Keyboard dispatch for the settings menu. Category navigation, per-category
 * setting navigation, Shift-modified value nudges, Enter/Escape actions.
 */
public final class InputHandler {

    private final StateManager stateManager;
    private final Settings settings;
    private final ActionHandler actionHandler;

    private boolean escapePressed;
    private boolean enterPressed;

    public InputHandler(StateManager stateManager, Settings settings, ActionHandler actionHandler) {
        this.stateManager = stateManager;
        this.settings = settings;
        this.actionHandler = actionHandler;
    }

    public void handleInput(long window) {
        handleCategoryNavigation(window);
        handleSettingNavigation(window);
        handleValueAdjustment(window);
        handleEnter(window);
        handleEscape(window);
    }

    private void handleCategoryNavigation(long window) {
        boolean shift = isShiftHeld(window);
        if (shift) return; // Shift+arrows adjusts values, not category
        if (pressed(window, GLFW_KEY_LEFT) || pressed(window, GLFW_KEY_A)) {
            stateManager.navigateToPreviousCategory();
        }
        if (pressed(window, GLFW_KEY_RIGHT) || pressed(window, GLFW_KEY_D)) {
            stateManager.navigateToNextCategory();
        }
    }

    private void handleSettingNavigation(long window) {
        if (pressed(window, GLFW_KEY_UP) || pressed(window, GLFW_KEY_W)) {
            stateManager.navigateToPreviousSettingInCategory();
        }
        if (pressed(window, GLFW_KEY_DOWN) || pressed(window, GLFW_KEY_S)) {
            stateManager.navigateToNextSettingInCategory();
        }
    }

    private void handleValueAdjustment(long window) {
        if (!isShiftHeld(window)) return;
        if (pressed(window, GLFW_KEY_LEFT) || pressed(window, GLFW_KEY_A)) {
            adjustSelectedSettingValue(-1);
        }
        if (pressed(window, GLFW_KEY_RIGHT) || pressed(window, GLFW_KEY_D)) {
            adjustSelectedSettingValue(1);
        }
    }

    private void handleEnter(long window) {
        boolean down = pressed(window, GLFW_KEY_ENTER);
        if (down && !enterPressed) {
            CategoryState.SettingType[] settingsArr = stateManager.getSelectedCategory().getSettings();
            CategoryState.SettingType current;
            if (stateManager.getSelectedSettingInCategory() < settingsArr.length) {
                current = settingsArr[stateManager.getSelectedSettingInCategory()];
            } else {
                int idx = stateManager.getSelectedSettingInCategory() - settingsArr.length;
                current = (idx == 0) ? CategoryState.SettingType.APPLY : CategoryState.SettingType.BACK;
            }
            if (current == CategoryState.SettingType.RESOLUTION && stateManager.getResolutionButton().isOpen()) {
                confirmResolutionSelection();
            } else {
                actionHandler.executeSelectedAction();
            }
        }
        enterPressed = down;
    }

    private void handleEscape(long window) {
        boolean down = pressed(window, GLFW_KEY_ESCAPE);
        if (down && !escapePressed) actionHandler.goBack();
        escapePressed = down;
    }

    // ─────────────────────────────────────────────── Value adjust

    private void adjustSelectedSettingValue(int direction) {
        CategoryState.SettingType[] s = stateManager.getSelectedCategory().getSettings();
        if (stateManager.getSelectedSettingInCategory() >= s.length) return;
        CategoryState.SettingType current = s[stateManager.getSelectedSettingInCategory()];
        if (current == null) return;
        switch (current) {
            case RESOLUTION -> adjustResolution(direction);
            case VOLUME -> adjustSlider(stateManager.getVolumeSlider(), direction * SettingsConfig.VOLUME_STEP);
            case ARM_MODEL -> adjustArmModel(direction);
            case CROSSHAIR_STYLE -> adjustCrosshairStyle(direction);
            case CROSSHAIR_SIZE -> adjustSlider(stateManager.getCrosshairSizeSlider(), direction * SettingsConfig.CROSSHAIR_SIZE_STEP);
            case LEAF_TRANSPARENCY -> { if (direction != 0) actionHandler.toggleLeafTransparency(); }
            case RENDER_DISTANCE -> adjustSlider(stateManager.getRenderDistanceSlider(), direction);
            case LOD_DISTANCE -> adjustSlider(stateManager.getLodDistanceSlider(), direction);
            case LOD_ENABLED -> { if (direction != 0) actionHandler.toggleLodEnabled(); }
            case VSYNC -> { if (direction != 0) actionHandler.toggleVsync(); }
            default -> {}
        }
    }

    private void adjustResolution(int direction) {
        MDropdown dropdown = stateManager.getResolutionButton();
        if (dropdown.isOpen()) {
            dropdown.adjustSelection(direction);
            stateManager.setSelectedResolutionIndex(dropdown.selectedIndex());
        } else {
            int currentIndex = settings.getCurrentResolutionIndex();
            int max = Settings.getAvailableResolutions().length - 1;
            int next = Math.max(0, Math.min(max, currentIndex + direction));
            settings.setResolutionByIndex(next);
            stateManager.setSelectedResolutionIndex(next);
            dropdown.setSelectedIndex(next);
        }
    }

    private void adjustArmModel(int direction) {
        MDropdown dropdown = stateManager.getArmModelButton();
        if (dropdown.isOpen()) {
            dropdown.adjustSelection(direction);
            stateManager.setSelectedArmModelIndex(dropdown.selectedIndex());
        } else {
            int currentIndex = stateManager.getSelectedArmModelIndex();
            int max = SettingsConfig.ARM_MODEL_TYPES.length - 1;
            int next = Math.max(0, Math.min(max, currentIndex + direction));
            settings.setArmModelType(SettingsConfig.ARM_MODEL_TYPES[next]);
            stateManager.setSelectedArmModelIndex(next);
            dropdown.setSelectedIndex(next);
        }
    }

    private void adjustCrosshairStyle(int direction) {
        MDropdown dropdown = stateManager.getCrosshairStyleButton();
        if (dropdown.isOpen()) {
            dropdown.adjustSelection(direction);
            stateManager.setSelectedCrosshairStyleIndex(dropdown.selectedIndex());
        } else {
            int currentIndex = stateManager.getSelectedCrosshairStyleIndex();
            int max = SettingsConfig.CROSSHAIR_STYLES.length - 1;
            int next = Math.max(0, Math.min(max, currentIndex + direction));
            settings.setCrosshairStyle(SettingsConfig.CROSSHAIR_STYLES[next]);
            stateManager.setSelectedCrosshairStyleIndex(next);
            dropdown.setSelectedIndex(next);
        }
    }

    private void adjustSlider(MSlider slider, float step) { slider.adjustValue(step); }

    private void confirmResolutionSelection() {
        settings.setResolutionByIndex(stateManager.getSelectedResolutionIndex());
        stateManager.getResolutionButton().close();
    }

    // ─────────────────────────────────────────────── Helpers

    private static boolean pressed(long window, int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    private static boolean isShiftHeld(long window) {
        return pressed(window, GLFW_KEY_LEFT_SHIFT) || pressed(window, GLFW_KEY_RIGHT_SHIFT);
    }
}
