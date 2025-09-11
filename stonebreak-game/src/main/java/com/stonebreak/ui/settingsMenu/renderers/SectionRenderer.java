package com.stonebreak.ui.settingsMenu.renderers;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.handlers.MouseHandler;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles rendering of individual settings sections.
 * Manages display, audio, crosshair, and action button sections.
 */
public class SectionRenderer {
    
    private final UIRenderer uiRenderer;
    private final StateManager stateManager;
    private final Settings settings;
    
    public SectionRenderer(UIRenderer uiRenderer, StateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.settings = Settings.getInstance();
    }
    
    /**
     * Renders all settings sections (display, audio, crosshair).
     */
    public void renderSettingSections(float centerX, float centerY) {
        // Update button positions and selection states
        updateButtonPositions(centerX, centerY);
        stateManager.updateButtonSelectionStates();
        
        renderDisplaySettings(centerX, centerY);
        renderAudioSettings(centerX, centerY);
        renderArmModelSettings(centerX, centerY);
        renderCrosshairSettings(centerX, centerY);
    }
    
    /**
     * Updates button and slider positions based on current center coordinates.
     * This is shared logic used by both rendering and mouse handling.
     */
    private void updateButtonPositions(float centerX, float centerY) {
        stateManager.getResolutionButton().setPosition(
            centerX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.RESOLUTION_BUTTON_Y_OFFSET
        );
        
        stateManager.getArmModelButton().setPosition(
            centerX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.ARM_MODEL_BUTTON_Y_OFFSET
        );
        
        stateManager.getCrosshairStyleButton().setPosition(
            centerX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.CROSSHAIR_STYLE_BUTTON_Y_OFFSET
        );
        
        stateManager.getApplyButton().setPosition(
            centerX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.APPLY_BUTTON_Y_OFFSET
        );
        
        stateManager.getBackButton().setPosition(
            centerX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.BACK_BUTTON_Y_OFFSET
        );
        
        // Update slider positions
        stateManager.getVolumeSlider().setPosition(
            centerX, 
            centerY + SettingsConfig.VOLUME_SLIDER_Y_OFFSET
        );
        
        stateManager.getCrosshairSizeSlider().setPosition(
            centerX, 
            centerY + SettingsConfig.CROSSHAIR_SIZE_SLIDER_Y_OFFSET
        );
    }
    
    /**
     * Renders display-related settings section.
     */
    private void renderDisplaySettings(float centerX, float centerY) {
        // Update button text to show current resolution
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        stateManager.getResolutionButton().setText(resolutionText);
        
        // Render the dropdown button component
        stateManager.getResolutionButton().render(uiRenderer);
        
        uiRenderer.drawSeparator(
            centerX, 
            centerY + SettingsConfig.DISPLAY_SEPARATOR_Y_OFFSET, 
            SettingsConfig.BUTTON_WIDTH * SettingsConfig.SEPARATOR_WIDTH_FACTOR
        );
    }
    
    /**
     * Renders audio-related settings section.
     */
    private void renderAudioSettings(float centerX, float centerY) {
        // Render the volume slider component
        stateManager.getVolumeSlider().render(uiRenderer);
        
        uiRenderer.drawSeparator(
            centerX, 
            centerY + SettingsConfig.AUDIO_SEPARATOR_Y_OFFSET, 
            SettingsConfig.BUTTON_WIDTH * SettingsConfig.SEPARATOR_WIDTH_FACTOR
        );
    }
    
    /**
     * Renders arm model settings section.
     */
    private void renderArmModelSettings(float centerX, float centerY) {
        // Update button text to show current arm model
        String currentArmModelName = SettingsConfig.ARM_MODEL_NAMES[stateManager.getSelectedArmModelIndex()];
        String armModelText = "Arm Model: " + currentArmModelName;
        stateManager.getArmModelButton().setText(armModelText);
        
        // Render the dropdown button component
        stateManager.getArmModelButton().render(uiRenderer);
        
        uiRenderer.drawSeparator(
            centerX, 
            centerY + SettingsConfig.ARM_MODEL_SEPARATOR_Y_OFFSET, 
            SettingsConfig.BUTTON_WIDTH * SettingsConfig.SEPARATOR_WIDTH_FACTOR
        );
    }
    
    /**
     * Renders crosshair-related settings section.
     */
    private void renderCrosshairSettings(float centerX, float centerY) {
        renderCrosshairStyleSetting(centerX, centerY);
        renderCrosshairSizeSetting(centerX, centerY);
        
        uiRenderer.drawSeparator(
            centerX, 
            centerY + SettingsConfig.CROSSHAIR_SEPARATOR_Y_OFFSET, 
            SettingsConfig.BUTTON_WIDTH * SettingsConfig.SEPARATOR_WIDTH_FACTOR
        );
    }
    
    /**
     * Renders crosshair style dropdown setting.
     */
    private void renderCrosshairStyleSetting(float centerX, float centerY) {
        // Update button text to show current crosshair style
        String currentStyleName = SettingsConfig.CROSSHAIR_STYLE_NAMES[stateManager.getSelectedCrosshairStyleIndex()];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        stateManager.getCrosshairStyleButton().setText(crosshairStyleText);
        
        // Render the dropdown button component
        stateManager.getCrosshairStyleButton().render(uiRenderer);
    }
    
    /**
     * Renders crosshair size slider setting.
     */
    private void renderCrosshairSizeSetting(float centerX, float centerY) {
        // Render the crosshair size slider component
        stateManager.getCrosshairSizeSlider().render(uiRenderer);
    }
    
    /**
     * Renders action buttons (Apply, Back) using button components.
     */
    public void renderActionButtons(float centerX, float centerY) {
        stateManager.getApplyButton().render(uiRenderer);
        stateManager.getBackButton().render(uiRenderer);
    }
}