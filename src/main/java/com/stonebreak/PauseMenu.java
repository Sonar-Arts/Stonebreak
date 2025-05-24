package com.stonebreak;

// Removed direct OpenGL imports, will use UIRenderer
// import static org.lwjgl.opengl.GL11.*;
// import static org.lwjgl.opengl.GL20.*;
// import static org.lwjgl.opengl.GL30.*;

// Removed nio Buffers and BufferUtils as UIRenderer handles this with NanoVG
// import java.nio.FloatBuffer;
// import java.nio.IntBuffer;
// import org.lwjgl.BufferUtils;

// Removed JOMF for direct GL rendering, UIRenderer will use NanoVG's transforms
// import org.joml.Matrix4f;
// import org.joml.Vector4f;

import static org.lwjgl.nanovg.NanoVG.*; // For alignment constants


/**
 * Represents the pause menu that appears when the user presses the escape key.
 */
public class PauseMenu {
    
    private boolean visible = false;
    private boolean quitButtonHovered = false;
    
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
    private final UIRenderer uiRenderer; // Added UIRenderer

    // Button dimensions - using screen pixels now
    private final float buttonWidth = 200;
    private final float buttonHeight = 50;
    private final float quitButtonYOffset = 0; // Centered for now
    // Add other buttons if needed (e.g., Resume, Settings)
    // private final float resumeButtonYOffset = -70;
    // private final float settingsButtonYOffset = -10;


    public PauseMenu(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
        // VAO/VBO creation is removed, UIRenderer handles primitive drawing
    }
    
    // createMenuPanel and createQuitButton are removed as UIRenderer draws them directly
    
    /**
     * Renders the pause menu.
     * screenWidth and screenHeight are needed to position elements.
     */
    public void render(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }
        
        uiRenderer.renderPauseMenu(windowWidth, windowHeight, quitButtonHovered);

        // UIRenderer.beginFrame and endFrame are handled by the main game loop
        // or the caller if this is part of a larger UI rendering pass.
        // This method now assumes UIRenderer is already in a valid frame.

        // Panel background (semi-transparent)
        float panelWidth = 300;
        float panelHeight = 250;
        float panelX = (screenWidth - panelWidth) / 2.0f;
        float panelY = (screenHeight - panelHeight) / 2.0f;
        uiRenderer.renderQuad(panelX, panelY, panelWidth, panelHeight, 0.2f, 0.2f, 0.2f, 0.8f);

        // "Paused" Title
        uiRenderer.drawString("Paused", screenWidth / 2.0f, panelY + 40, UIRenderer.FONT_MINECRAFT, 36, 255, 255, 255, 255, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        
        // Quit Button
        float quitButtonX = (screenWidth - buttonWidth) / 2.0f;
        float quitButtonY = screenHeight / 2.0f - buttonHeight / 2.0f + quitButtonYOffset + 50; // Adjusted Y for title
        
        // Simple check if mouse is over this button for highlighting (passed from InputHandler or Game state)
        // For now, assume no highlighting logic in render, it's purely drawing.
        // Highlighting state could be a parameter or managed internally if PauseMenu handles its own input.
        // Let's use UIRenderer's Minecraft-style button for consistency
        Game gameInstance = Game.getInstance();
        boolean quitButtonHighlighted = false;
        if (gameInstance != null && gameInstance.getInputHandler() != null) {
            // This requires a way for PauseMenu to know if this specific button is hovered.
            // This is a simplification. A more robust way is for InputHandler to tell PauseMenu which button is active.
            // Or, PauseMenu handles its own input directly.
             float mouseX = gameInstance.getInputHandler().getMousePosition().x;
             float mouseY = gameInstance.getInputHandler().getMousePosition().y;
             quitButtonHighlighted = uiRenderer.isButtonClicked(mouseX, mouseY, quitButtonX, quitButtonY, buttonWidth, buttonHeight);
        }
        uiRenderer.drawMinecraftButton("Quit Game", quitButtonX, quitButtonY, buttonWidth, buttonHeight, quitButtonHighlighted);
        
        // Add Resume Button (Example)
        // float resumeButtonX = (screenWidth - buttonWidth) / 2.0f;
        // float resumeButtonY = screenHeight / 2.0f - buttonHeight / 2.0f + resumeButtonYOffset - 20;
        // boolean resumeButtonHighlighted = false; // Check hover for resume button
        // uiRenderer.drawMinecraftButton("Resume", resumeButtonX, resumeButtonY, buttonWidth, buttonHeight, resumeButtonHighlighted);
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
     * Checks if the given mouse coordinates are within the quit button.
     * @param mouseX The current X position of the mouse in screen coordinates (pixels).
     * @param mouseY The current Y position of the mouse in screen coordinates (pixels).
     * @param screenWidth The width of the game screen in pixels.
     * @param screenHeight The height of the game screen in pixels.
     * @return true if the quit button is clicked, false otherwise.
     */
    public boolean isQuitButtonClicked(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        return visible && uiRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Updates hover state for buttons.
     */
    public void updateHover(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        if (!visible) {
            quitButtonHovered = false;
            return;
        }
        
        quitButtonHovered = uiRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        // No OpenGL resources to cleanup anymore
        // No specific GL resources to clean up here as UIRenderer handles its own.
        // VAOs/VBOs were removed.
    }
}
