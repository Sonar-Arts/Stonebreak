package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.config.Settings;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles keyboard input for the settings menu.
 * Manages navigation, value adjustments, and action execution.
 */
public class InputHandler {
    
    private final StateManager stateManager;
    private final Settings settings;
    private final ActionHandler actionHandler;
    
    private boolean escapeKeyPressed = false;
    
    public InputHandler(StateManager stateManager, Settings settings, ActionHandler actionHandler) {
        this.stateManager = stateManager;
        this.settings = settings;
        this.actionHandler = actionHandler;
    }
    
    /**
     * Handles all keyboard input for the settings menu.
     */
    public void handleInput(long window) {
        handleCategoryNavigationKeys(window);
        handleSettingNavigationKeys(window);
        handleValueAdjustmentKeys(window);
        handleActionKeys(window);
        handleEscapeKey(window);
    }
    
    /**
     * Handles left/right navigation keys for category selection.
     */
    private void handleCategoryNavigationKeys(long window) {
        if (isKeyPressed(window, GLFW_KEY_LEFT, GLFW_KEY_A)) {
            stateManager.navigateToPreviousCategory();
        }
        if (isKeyPressed(window, GLFW_KEY_RIGHT, GLFW_KEY_D)) {
            stateManager.navigateToNextCategory();
        }
    }
    
    /**
     * Handles up/down navigation keys for setting selection within a category.
     */
    private void handleSettingNavigationKeys(long window) {
        if (isKeyPressed(window, GLFW_KEY_UP, GLFW_KEY_W)) {
            stateManager.navigateToPreviousSettingInCategory();
        }
        if (isKeyPressed(window, GLFW_KEY_DOWN, GLFW_KEY_S)) {
            stateManager.navigateToNextSettingInCategory();
        }
    }
    
    /**
     * Handles keys for adjusting setting values within the settings panel.
     * Uses Shift+Left/Right to avoid conflict with category navigation.
     */
    private void handleValueAdjustmentKeys(long window) {
        boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
        
        if (shiftHeld) {
            if (isKeyPressed(window, GLFW_KEY_LEFT, GLFW_KEY_A)) {
                adjustSelectedSettingValue(-1);
            }
            if (isKeyPressed(window, GLFW_KEY_RIGHT, GLFW_KEY_D)) {
                adjustSelectedSettingValue(1);
            }
        }
    }
    
    /**
     * Handles action keys like Enter.
     */
    private void handleActionKeys(long window) {
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
            // Get the current setting type based on category and selection
            CategoryState.SettingType[] settings = stateManager.getSelectedCategory().getSettings();
            CategoryState.SettingType currentSetting;
            
            if (stateManager.getSelectedSettingInCategory() < settings.length) {
                currentSetting = settings[stateManager.getSelectedSettingInCategory()];
            } else {
                // Handle Apply/Back buttons
                int buttonIndex = stateManager.getSelectedSettingInCategory() - settings.length;
                currentSetting = (buttonIndex == 0) ? CategoryState.SettingType.APPLY : CategoryState.SettingType.BACK;
            }
            
            // Handle dropdown confirmations
            if (currentSetting == CategoryState.SettingType.RESOLUTION && stateManager.getResolutionButton().isDropdownOpen()) {
                confirmResolutionSelection();
            } else {
                actionHandler.executeSelectedAction();
            }
        }
    }
    
    /**
     * Handles escape key with edge detection to prevent repeated triggers.
     */
    private void handleEscapeKey(long window) {
        boolean isEscapePressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        if (isEscapePressed && !escapeKeyPressed) {
            escapeKeyPressed = true;
            actionHandler.goBack();
        } else if (!isEscapePressed) {
            escapeKeyPressed = false;
        }
    }
    
    /**
     * Utility method to check if any of the specified keys are pressed.
     */
    private boolean isKeyPressed(long window, int... keys) {
        for (int key : keys) {
            if (glfwGetKey(window, key) == GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Adjusts the currently selected setting value by the specified direction.
     * @param direction -1 for decrease, 1 for increase
     */
    private void adjustSelectedSettingValue(int direction) {
        // Get the current setting type based on category and selection
        CategoryState.SettingType[] settings = stateManager.getSelectedCategory().getSettings();
        if (stateManager.getSelectedSettingInCategory() >= settings.length) return; // Can't adjust Apply/Back buttons
        
        CategoryState.SettingType currentSetting = settings[stateManager.getSelectedSettingInCategory()];
        if (currentSetting == null) return;
        
        switch (currentSetting) {
            case RESOLUTION -> adjustResolutionSetting(direction);
            case VOLUME -> adjustVolumeSetting(direction);
            case ARM_MODEL -> adjustArmModelSetting(direction);
            case CROSSHAIR_STYLE -> adjustCrosshairStyleSetting(direction);
            case CROSSHAIR_SIZE -> adjustCrosshairSizeSetting(direction);
            case LEAF_TRANSPARENCY -> adjustLeafTransparencySetting(direction);
        }
    }
    
    /**
     * Adjusts resolution setting or dropdown selection.
     */
    private void adjustResolutionSetting(int direction) {
        DropdownButton resolutionButton = stateManager.getResolutionButton();
        if (resolutionButton.isDropdownOpen()) {
            resolutionButton.adjustSelection(direction);
            stateManager.setSelectedResolutionIndex(resolutionButton.getSelectedItemIndex());
        } else {
            int currentIndex = settings.getCurrentResolutionIndex();
            int[][] resolutions = Settings.getAvailableResolutions();
            int newIndex = Math.max(0, Math.min(resolutions.length - 1, currentIndex + direction));
            settings.setResolutionByIndex(newIndex);
            stateManager.setSelectedResolutionIndex(newIndex);
            resolutionButton.setSelectedItemIndex(newIndex);
        }
    }
    
    /**
     * Adjusts volume setting.
     */
    private void adjustVolumeSetting(int direction) {
        Slider volumeSlider = stateManager.getVolumeSlider();
        volumeSlider.adjustValue(direction * SettingsConfig.VOLUME_STEP);
    }
    
    /**
     * Adjusts arm model setting or dropdown selection.
     */
    private void adjustArmModelSetting(int direction) {
        DropdownButton armModelButton = stateManager.getArmModelButton();
        if (armModelButton.isDropdownOpen()) {
            armModelButton.adjustSelection(direction);
            stateManager.setSelectedArmModelIndex(armModelButton.getSelectedItemIndex());
        } else {
            int currentIndex = stateManager.getSelectedArmModelIndex();
            int newIndex = Math.max(0, Math.min(SettingsConfig.ARM_MODEL_TYPES.length - 1, currentIndex + direction));
            settings.setArmModelType(SettingsConfig.ARM_MODEL_TYPES[newIndex]);
            stateManager.setSelectedArmModelIndex(newIndex);
            armModelButton.setSelectedItemIndex(newIndex);
        }
    }
    
    /**
     * Adjusts crosshair style setting or dropdown selection.
     */
    private void adjustCrosshairStyleSetting(int direction) {
        DropdownButton crosshairStyleButton = stateManager.getCrosshairStyleButton();
        if (crosshairStyleButton.isDropdownOpen()) {
            crosshairStyleButton.adjustSelection(direction);
            stateManager.setSelectedCrosshairStyleIndex(crosshairStyleButton.getSelectedItemIndex());
        } else {
            int currentIndex = stateManager.getSelectedCrosshairStyleIndex();
            int newIndex = Math.max(0, Math.min(SettingsConfig.CROSSHAIR_STYLES.length - 1, currentIndex + direction));
            settings.setCrosshairStyle(SettingsConfig.CROSSHAIR_STYLES[newIndex]);
            stateManager.setSelectedCrosshairStyleIndex(newIndex);
            crosshairStyleButton.setSelectedItemIndex(newIndex);
        }
    }
    
    /**
     * Adjusts crosshair size setting.
     */
    private void adjustCrosshairSizeSetting(int direction) {
        Slider crosshairSizeSlider = stateManager.getCrosshairSizeSlider();
        crosshairSizeSlider.adjustValue(direction * SettingsConfig.CROSSHAIR_SIZE_STEP);
    }

    /**
     * Toggles leaf transparency setting when direction indicates change.
     */
    private void adjustLeafTransparencySetting(int direction) {
        // For toggle buttons, any direction change toggles the setting
        if (direction != 0) {
            actionHandler.toggleLeafTransparency();
        }
    }

    /**
     * Confirms resolution selection from dropdown and closes it.
     */
    private void confirmResolutionSelection() {
        int selectedIndex = stateManager.getSelectedResolutionIndex();
        settings.setResolutionByIndex(selectedIndex);
        stateManager.getResolutionButton().closeDropdown();
    }
}