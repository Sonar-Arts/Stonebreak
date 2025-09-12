package com.stonebreak.ui.settingsMenu.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Main rendering coordinator for the settings menu.
 * Manages the complete rendering process using two-phase rendering.
 */
public class SettingsRenderer {
    
    private final UIRenderer uiRenderer;
    private final StateManager stateManager;
    private final SectionRenderer sectionRenderer;
    
    public SettingsRenderer(UIRenderer uiRenderer, StateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.sectionRenderer = new SectionRenderer(uiRenderer, stateManager);
    }
    
    /**
     * Renders the complete settings menu UI using two-phase rendering.
     * Phase 1: Render all buttons and UI elements
     * Phase 2: Render dropdowns on top
     */
    public void render(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Phase 1: Render background and all UI components
        renderMenuBackground(windowWidth, windowHeight, centerX, centerY);
        sectionRenderer.renderSettingSections(centerX, centerY);
        
        // Phase 2: Render all dropdown menus on top
        renderDropdowns();
    }
    
    /**
     * Renders the background and base menu structure.
     */
    private void renderMenuBackground(int windowWidth, int windowHeight, float centerX, float centerY) {
        // Render the original full-screen background
        uiRenderer.renderSettingsMenu(windowWidth, windowHeight);
        
        // Render the SETTINGS title at the top of the screen
        renderSettingsTitle(centerX, centerY);
    }
    
    /**
     * Renders the SETTINGS title at the top of the menu.
     */
    private void renderSettingsTitle(float centerX, float centerY) {
        // Position title at the top of the menu area
        float titleY = centerY - 200; // Position above the main menu content
        
        try {
            // Render the SETTINGS title prominently
            uiRenderer.drawText("SETTINGS", centerX, titleY, "default", 28.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception e) {
            // Fallback if text rendering fails
            System.out.println("Failed to render SETTINGS title");
        }
    }
    
    
    /**
     * Renders all dropdown menus on top of other UI elements.
     */
    private void renderDropdowns() {
        // Render dropdowns in the order they should appear (top to bottom)
        stateManager.getResolutionButton().renderDropdown(uiRenderer);
        stateManager.getArmModelButton().renderDropdown(uiRenderer);
        stateManager.getCrosshairStyleButton().renderDropdown(uiRenderer);
    }
    
    /**
     * Provides access to the section renderer for external components.
     */
    public SectionRenderer getSectionRenderer() {
        return sectionRenderer;
    }
}