package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.audio.SoundSystem;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.Main;

/**
 * Manages the application of settings to various game subsystems.
 * Handles audio, display, and crosshair setting application.
 */
public class SettingsManager {
    
    private final Settings settings;
    
    public SettingsManager(Settings settings) {
        this.settings = settings;
    }
    
    /**
     * Applies audio-related settings to the sound system.
     */
    public void applyAudioSettings() {
        SoundSystem soundSystem = SoundSystem.getInstance();
        if (soundSystem != null) {
            soundSystem.setMasterVolume(settings.getMasterVolume());
            System.out.println("Applied master volume: " + settings.getMasterVolume());
        }
    }
    
    /**
     * Applies display-related settings like window resolution.
     */
    public void applyDisplaySettings() {
        applyWindowResize();
    }
    
    /**
     * Applies crosshair-related settings to the crosshair renderer.
     */
    public void applyCrosshairSettings() {
        Game game = Game.getInstance();
        if (game != null && game.getRenderer() != null && game.getRenderer().getUIRenderer() != null) {
            var crosshairRenderer = game.getRenderer().getUIRenderer().getCrosshairRenderer();
            if (crosshairRenderer != null) {
                applyCrosshairStyle(crosshairRenderer);
                applyCrosshairProperties(crosshairRenderer);
                
                System.out.println("Applied crosshair settings: style=" + settings.getCrosshairStyle() + 
                                 ", size=" + settings.getCrosshairSize());
            }
        }
    }
    
    /**
     * Applies the crosshair style to the renderer.
     */
    private void applyCrosshairStyle(com.stonebreak.rendering.UI.components.CrosshairRenderer crosshairRenderer) {
        try {
            var styleEnum = com.stonebreak.rendering.UI.components.CrosshairRenderer.CrosshairStyle
                .valueOf(settings.getCrosshairStyle());
            crosshairRenderer.setStyle(styleEnum);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid crosshair style: " + settings.getCrosshairStyle());
        }
    }
    
    /**
     * Applies crosshair visual properties to the renderer.
     */
    private void applyCrosshairProperties(com.stonebreak.rendering.UI.components.CrosshairRenderer crosshairRenderer) {
        crosshairRenderer.setSize(settings.getCrosshairSize());
        crosshairRenderer.setThickness(settings.getCrosshairThickness());
        crosshairRenderer.setGap(settings.getCrosshairGap());
        crosshairRenderer.setOpacity(settings.getCrosshairOpacity());
        crosshairRenderer.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
        crosshairRenderer.setOutline(settings.getCrosshairOutline());
    }
    
    /**
     * Applies window resolution changes to the actual window.
     */
    private void applyWindowResize() {
        long windowHandle = Main.getWindowHandle();
        if (windowHandle == 0) {
            System.err.println("Warning: Could not apply window resolution - window handle is null");
            return;
        }
        
        int newWidth = settings.getWindowWidth();
        int newHeight = settings.getWindowHeight();
        
        // Apply window size change
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(windowHandle, newWidth, newHeight);
        
        // Update game's stored dimensions
        Game.getInstance().setWindowDimensions(newWidth, newHeight);
        
        System.out.println("Applied window resolution: " + newWidth + "x" + newHeight);
    }
}