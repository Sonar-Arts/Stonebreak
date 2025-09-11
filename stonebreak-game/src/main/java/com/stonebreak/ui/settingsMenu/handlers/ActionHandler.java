package com.stonebreak.ui.settingsMenu.handlers;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.SettingsManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles action execution and navigation for the settings menu.
 * Manages button callbacks, settings application, and navigation.
 */
public class ActionHandler {
    
    private final StateManager stateManager;
    private final SettingsManager settingsManager;
    private final Settings settings;
    
    public ActionHandler(StateManager stateManager, SettingsManager settingsManager, Settings settings) {
        this.stateManager = stateManager;
        this.settingsManager = settingsManager;
        this.settings = settings;
    }
    
    /**
     * Executes the action associated with the currently selected setting.
     */
    public void executeSelectedAction() {
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
        
        if (currentSetting == null) return;
        
        switch (currentSetting) {
            case RESOLUTION -> stateManager.getResolutionButton().toggleDropdown();
            case VOLUME -> {} // Volume handled by mouse/keyboard interaction
            case ARM_MODEL -> stateManager.getArmModelButton().toggleDropdown();
            case CROSSHAIR_STYLE -> stateManager.getCrosshairStyleButton().toggleDropdown();
            case CROSSHAIR_SIZE -> {} // Crosshair size handled by mouse/keyboard interaction
            case APPLY -> applySettings();
            case BACK -> goBack();
        }
    }
    
    /**
     * Applies all current settings and saves them to persistent storage.
     * Does not exit the settings menu, allowing users to continue making adjustments.
     */
    public void applySettings() {
        settings.saveSettings();
        
        settingsManager.applyAudioSettings();
        settingsManager.applyCrosshairSettings();
        settingsManager.applyDisplaySettings();
        
        System.out.println("Settings applied successfully!");
        // Note: Removed goBack() call - users should be able to continue adjusting settings
    }
    
    /**
     * Navigates back to the previous game state.
     */
    public void goBack() {
        if (stateManager.getPreviousState() == GameState.PLAYING) {
            returnToGameplay();
        } else {
            returnToMainMenu();
        }
    }
    
    /**
     * Returns to active gameplay, properly handling pause state.
     */
    private void returnToGameplay() {
        Game game = Game.getInstance();
        
        // Resume game directly for cleaner user experience
        game.setState(GameState.PLAYING);
        game.getPauseMenu().setVisible(false);
        
        // Ensure game is unpaused
        if (game.isPaused()) {
            game.togglePauseMenu();
        }
    }
    
    /**
     * Returns to the main menu.
     */
    private void returnToMainMenu() {
        Game.getInstance().setState(GameState.MAIN_MENU);
    }
    
    // ===== BUTTON CALLBACK METHODS =====
    
    /**
     * Callback for when resolution selection changes.
     */
    public void onResolutionChange() {
        int newIndex = stateManager.getResolutionButton().getSelectedItemIndex();
        settings.setResolutionByIndex(newIndex);
        stateManager.setSelectedResolutionIndex(newIndex);
    }
    
    /**
     * Callback for when arm model selection changes.
     */
    public void onArmModelChange() {
        int newIndex = stateManager.getArmModelButton().getSelectedItemIndex();
        settings.setArmModelType(com.stonebreak.ui.settingsMenu.config.SettingsConfig.ARM_MODEL_TYPES[newIndex]);
        stateManager.setSelectedArmModelIndex(newIndex);
    }
    
    /**
     * Callback for when crosshair style selection changes.
     */
    public void onCrosshairStyleChange() {
        int newIndex = stateManager.getCrosshairStyleButton().getSelectedItemIndex();
        settings.setCrosshairStyle(com.stonebreak.ui.settingsMenu.config.SettingsConfig.CROSSHAIR_STYLES[newIndex]);
        stateManager.setSelectedCrosshairStyleIndex(newIndex);
    }
    
    /**
     * Callback for when volume slider value changes.
     */
    public void onVolumeChange(Float newVolume) {
        settings.setMasterVolume(newVolume);
    }
    
    /**
     * Callback for when crosshair size slider value changes.
     */
    public void onCrosshairSizeChange(Float newSize) {
        settings.setCrosshairSize(newSize);
    }
}