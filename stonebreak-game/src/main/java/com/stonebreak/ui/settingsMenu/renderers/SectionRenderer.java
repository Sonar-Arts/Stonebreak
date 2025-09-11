package com.stonebreak.ui.settingsMenu.renderers;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.handlers.MouseHandler;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles rendering of the two-panel settings layout.
 * Manages category buttons on the left and settings on the right.
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
     * Renders the complete two-panel layout (categories left, settings right).
     */
    public void renderSettingSections(float centerX, float centerY) {
        // Update all button positions and selection states
        updateButtonPositions(centerX, centerY);
        stateManager.updateButtonSelectionStates();
        
        // Render left panel (categories)
        renderCategoryPanel(centerX, centerY);
        
        // Render panel separator
        renderPanelSeparator(centerX, centerY);
        
        // Render right panel (settings for selected category)
        renderSettingsPanel(centerX, centerY);
    }
    
    /**
     * Updates button and slider positions based on current center coordinates.
     * This is shared logic used by both rendering and mouse handling.
     */
    private void updateButtonPositions(float centerX, float centerY) {
        // Position category buttons (left panel)
        float categoryX = centerX + SettingsConfig.CATEGORY_PANEL_X_OFFSET;
        float categoryY = centerY + SettingsConfig.CATEGORY_BUTTONS_START_Y_OFFSET;
        
        for (int i = 0; i < stateManager.getCategoryButtons().size(); i++) {
            stateManager.getCategoryButtons().get(i).setPosition(
                categoryX,
                categoryY + (i * SettingsConfig.CATEGORY_BUTTON_SPACING)
            );
        }
        
        // Position settings components (right panel)
        float settingsX = centerX + SettingsConfig.SETTINGS_PANEL_X_OFFSET;
        
        stateManager.getResolutionButton().setPosition(
            settingsX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.RESOLUTION_BUTTON_Y_OFFSET
        );
        
        stateManager.getArmModelButton().setPosition(
            settingsX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.ARM_MODEL_BUTTON_Y_OFFSET
        );
        
        stateManager.getCrosshairStyleButton().setPosition(
            settingsX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.CROSSHAIR_STYLE_BUTTON_Y_OFFSET
        );
        
        stateManager.getApplyButton().setPosition(
            settingsX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.APPLY_BUTTON_Y_OFFSET
        );
        
        stateManager.getBackButton().setPosition(
            settingsX - SettingsConfig.BUTTON_WIDTH/2, 
            centerY + SettingsConfig.BACK_BUTTON_Y_OFFSET
        );
        
        // Update slider positions
        stateManager.getVolumeSlider().setPosition(
            settingsX, 
            centerY + SettingsConfig.VOLUME_SLIDER_Y_OFFSET
        );
        
        stateManager.getCrosshairSizeSlider().setPosition(
            settingsX, 
            centerY + SettingsConfig.CROSSHAIR_SIZE_SLIDER_Y_OFFSET
        );
    }
    
    /**
     * Renders the left panel with category buttons.
     */
    private void renderCategoryPanel(float centerX, float centerY) {
        // Render all category buttons
        for (com.stonebreak.ui.components.buttons.CategoryButton button : stateManager.getCategoryButtons()) {
            button.render(uiRenderer);
        }
    }
    
    /**
     * Renders the separator between left and right panels.
     */
    private void renderPanelSeparator(float centerX, float centerY) {
        // Use regular separator rotated vertically or draw a simple line
        // If drawVerticalSeparator doesn't exist, we'll fallback to a horizontal separator
        try {
            uiRenderer.drawSeparator(
                centerX + SettingsConfig.PANEL_SEPARATOR_X_OFFSET,
                centerY,
                2.0f // Thin vertical line
            );
        } catch (Exception e) {
            // Fallback - don't render separator if method doesn't exist
        }
    }
    
    /**
     * Renders the right panel with settings for the currently selected category.
     */
    private void renderSettingsPanel(float centerX, float centerY) {
        com.stonebreak.ui.settingsMenu.config.CategoryState selectedCategory = stateManager.getSelectedCategory();
        
        switch (selectedCategory) {
            case GENERAL:
                renderGeneralSettings();
                break;
            case QUALITY:
                renderQualitySettings();
                break;
            case PERFORMANCE:
                renderPerformanceSettings();
                break;
            case ADVANCED:
                renderAdvancedSettings();
                break;
            case EXTRAS:
                renderExtrasSettings();
                break;
            case AUDIO:
                renderAudioSettings();
                break;
        }
    }
    
    /**
     * Renders General category settings (Resolution).
     */
    private void renderGeneralSettings() {
        // Update button text to show current resolution
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        stateManager.getResolutionButton().setText(resolutionText);
        stateManager.getResolutionButton().render(uiRenderer);
    }
    
    /**
     * Renders Quality category settings (extensible for future).
     */
    private void renderQualitySettings() {
        // Currently empty - ready for future quality settings
    }
    
    /**
     * Renders Performance category settings (extensible for future).
     */
    private void renderPerformanceSettings() {
        // Currently empty - ready for future performance settings
    }
    
    /**
     * Renders Advanced category settings (Arm Model).
     */
    private void renderAdvancedSettings() {
        // Update button text to show current arm model
        String currentArmModelName = SettingsConfig.ARM_MODEL_NAMES[stateManager.getSelectedArmModelIndex()];
        String armModelText = "Arm Model: " + currentArmModelName;
        stateManager.getArmModelButton().setText(armModelText);
        stateManager.getArmModelButton().render(uiRenderer);
    }
    
    /**
     * Renders Extras category settings (Crosshair).
     */
    private void renderExtrasSettings() {
        // Update button text to show current crosshair style
        String currentStyleName = SettingsConfig.CROSSHAIR_STYLE_NAMES[stateManager.getSelectedCrosshairStyleIndex()];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        stateManager.getCrosshairStyleButton().setText(crosshairStyleText);
        stateManager.getCrosshairStyleButton().render(uiRenderer);
        
        // Render crosshair size slider
        stateManager.getCrosshairSizeSlider().render(uiRenderer);
    }
    
    /**
     * Renders Audio category settings (Master Volume).
     */
    private void renderAudioSettings() {
        stateManager.getVolumeSlider().render(uiRenderer);
    }
    
    /**
     * Renders action buttons (Apply, Back) using button components.
     */
    public void renderActionButtons(float centerX, float centerY) {
        stateManager.getApplyButton().render(uiRenderer);
        stateManager.getBackButton().render(uiRenderer);
    }
}