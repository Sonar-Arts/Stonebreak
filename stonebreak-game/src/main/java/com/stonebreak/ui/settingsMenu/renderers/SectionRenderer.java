package com.stonebreak.ui.settingsMenu.renderers;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.handlers.MouseHandler;
import com.stonebreak.ui.settingsMenu.managers.ScrollManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles rendering of the two-panel settings layout.
 * Manages category buttons on the left and settings on the right.
 */
public class SectionRenderer {
    
    private final UIRenderer uiRenderer;
    private final StateManager stateManager;
    private final Settings settings;
    private final ScrollableSettingsContainer scrollableContainer;
    
    public SectionRenderer(UIRenderer uiRenderer, StateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.settings = Settings.getInstance();
        this.scrollableContainer = new ScrollableSettingsContainer(stateManager, stateManager.getCurrentScrollManager());
    }
    
    /**
     * Renders the complete two-panel layout (categories left, settings right).
     */
    public void renderSettingSections(float centerX, float centerY) {
        // Calculate panel dimensions for container bounds
        float panelHeight = Math.min(550, 600 * 0.85f);
        
        // Update category button positions and selection states
        updateCategoryButtonPositions(centerX, centerY);
        stateManager.updateButtonSelectionStates();
        
        // Update scrollable container bounds and scroll manager
        ScrollManager currentScrollManager = stateManager.getCurrentScrollManager();
        if (currentScrollManager != null) {
            currentScrollManager.update(1.0f / 60.0f); // Assume 60 FPS for smooth scrolling
        }
        scrollableContainer.updateBounds(centerX, centerY, panelHeight);
        
        // Render left panel (categories)
        renderCategoryPanel(centerX, centerY);
        
        // Render panel separator
        renderPanelSeparator(centerX, centerY);
        
        // Render right panel with scrollable container
        renderScrollableSettingsPanel(centerX, centerY, panelHeight);
    }
    
    /**
     * Updates category button positions based on current center coordinates.
     */
    private void updateCategoryButtonPositions(float centerX, float centerY) {
        // Position category buttons (left panel) with counter padding to maintain their position
        float categoryX = centerX + SettingsConfig.CATEGORY_PANEL_X_OFFSET;
        float categoryY = centerY + SettingsConfig.CATEGORY_BUTTONS_START_Y_OFFSET - 20; // Counter padding to maintain position
        
        for (int i = 0; i < stateManager.getCategoryButtons().size(); i++) {
            stateManager.getCategoryButtons().get(i).setPosition(
                categoryX,
                categoryY + (i * SettingsConfig.CATEGORY_BUTTON_SPACING)
            );
        }
    }
    
    /**
     * Renders the right panel with scrollable settings container and fixed action buttons.
     */
    private void renderScrollableSettingsPanel(float centerX, float centerY, float panelHeight) {
        // Render scrollable settings container
        scrollableContainer.render(uiRenderer);
        
        // Position and render fixed action buttons below the container
        float containerBottom = scrollableContainer.getContainerBottom();
        float buttonSpacing = 15;
        float buttonMargin = 20;
        
        float applyY = containerBottom + buttonMargin;
        float backY = applyY + SettingsConfig.BUTTON_HEIGHT + buttonSpacing;
        float buttonCenterX = scrollableContainer.getContainerCenterX();
        
        // Position Apply and Back buttons
        stateManager.getApplyButton().setPosition(
            buttonCenterX - SettingsConfig.BUTTON_WIDTH/2, applyY);
        stateManager.getBackButton().setPosition(
            buttonCenterX - SettingsConfig.BUTTON_WIDTH/2, backY);
        
        // Render action buttons
        stateManager.getApplyButton().render(uiRenderer);
        stateManager.getBackButton().render(uiRenderer);
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
     * Updates button texts for display based on current settings.
     * Called by the scrollable container when rendering settings.
     */
    public void updateSettingTexts() {
        // Update button texts to show current values
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        stateManager.getResolutionButton().setText(resolutionText);
        
        String currentArmModelName = SettingsConfig.ARM_MODEL_NAMES[stateManager.getSelectedArmModelIndex()];
        String armModelText = "Arm Model: " + currentArmModelName;
        stateManager.getArmModelButton().setText(armModelText);
        
        String currentStyleName = SettingsConfig.CROSSHAIR_STYLE_NAMES[stateManager.getSelectedCrosshairStyleIndex()];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        stateManager.getCrosshairStyleButton().setText(crosshairStyleText);
    }
    
    /**
     * Provides access to the scrollable container for external mouse handling.
     */
    public ScrollableSettingsContainer getScrollableContainer() {
        return scrollableContainer;
    }
}