package com.stonebreak.ui;

/**
 * Represents the pause menu that appears when the user presses the escape key.
 */
public class PauseMenu {
    
    // Menu state
    private boolean visible = false;
    private boolean quitButtonHovered = false;
    private boolean settingsButtonHovered = false;
    
    /**
     * Creates a new pause menu.
     */
    public PauseMenu() {
        // No longer need to create OpenGL resources
    }
    
    
    
    /**
     * Renders the pause menu using UIRenderer.
     */
    public void render(UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        if (!visible) {
            return;
        }
        
        uiRenderer.renderPauseMenu(windowWidth, windowHeight, quitButtonHovered, settingsButtonHovered);
    }
    
    /**
     * Checks if the menu is currently visible.
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * Sets the visibility of the menu.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Toggles the visibility of the menu.
     */
    public void toggleVisibility() {
        this.visible = !this.visible;
    }
    
    /**
     * Checks if the resume button was clicked.
     */
    public boolean isResumeButtonClicked(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        return visible && uiRenderer.isPauseResumeClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Checks if the quit button was clicked.
     */
    public boolean isQuitButtonClicked(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        return visible && uiRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Checks if the settings button was clicked.
     */
    public boolean isSettingsButtonClicked(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        return visible && uiRenderer.isPauseSettingsClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Updates hover state for buttons.
     */
    public void updateHover(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        if (!visible) {
            quitButtonHovered = false;
            settingsButtonHovered = false;
            return;
        }
        
        quitButtonHovered = uiRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
        settingsButtonHovered = uiRenderer.isPauseSettingsClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        // No OpenGL resources to cleanup anymore
    }
}
