package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.config.ButtonSelection;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles mouse input and interaction for the settings menu.
 * Manages mouse movement, clicks, and hover states.
 */
public class MouseHandler {
    
    private final StateManager stateManager;
    
    public MouseHandler(StateManager stateManager) {
        this.stateManager = stateManager;
    }
    
    /**
     * Handles mouse movement for hover effects and dragging interactions.
     */
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Update button positions based on current window size
        updateButtonPositions(centerX, centerY);
        
        // Update button hover states
        updateButtonHoverStates((float)mouseX, (float)mouseY);
        
        // Handle slider dragging
        stateManager.getVolumeSlider().handleDragging((float)mouseX);
        stateManager.getCrosshairSizeSlider().handleDragging((float)mouseX);
    }
    
    /**
     * Handles mouse click events for button activation and slider interaction.
     */
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        if (action == GLFW_PRESS) {
            handleMousePress(mouseX, mouseY, centerX, centerY);
        } else if (action == GLFW_RELEASE) {
            handleMouseRelease();
        }
    }
    
    /**
     * Updates button and slider positions based on current center coordinates.
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
     * Updates hover states for all button and slider components.
     */
    private void updateButtonHoverStates(float mouseX, float mouseY) {
        stateManager.getResolutionButton().updateHover(mouseX, mouseY);
        stateManager.getArmModelButton().updateHover(mouseX, mouseY);
        stateManager.getCrosshairStyleButton().updateHover(mouseX, mouseY);
        stateManager.getApplyButton().updateHover(mouseX, mouseY);
        stateManager.getBackButton().updateHover(mouseX, mouseY);
        
        // Update slider hover states
        stateManager.getVolumeSlider().updateHover(mouseX, mouseY);
        stateManager.getCrosshairSizeSlider().updateHover(mouseX, mouseY);
        
        // Update selected button index based on hover
        updateSelectedButtonFromHover();
    }
    
    /**
     * Updates the selected button index based on which component is currently hovered.
     */
    private void updateSelectedButtonFromHover() {
        if (stateManager.getResolutionButton().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.RESOLUTION.getIndex());
        } else if (stateManager.getVolumeSlider().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.VOLUME.getIndex());
        } else if (stateManager.getArmModelButton().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.ARM_MODEL.getIndex());
        } else if (stateManager.getCrosshairStyleButton().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.CROSSHAIR_STYLE.getIndex());
        } else if (stateManager.getCrosshairSizeSlider().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.CROSSHAIR_SIZE.getIndex());
        } else if (stateManager.getApplyButton().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.APPLY.getIndex());
        } else if (stateManager.getBackButton().isHovered()) {
            stateManager.setSelectedButton(ButtonSelection.BACK.getIndex());
        }
    }
    
    /**
     * Handles mouse press events.
     */
    private void handleMousePress(double mouseX, double mouseY, float centerX, float centerY) {
        // Handle dropdown interactions first
        if (handleDropdownClick(mouseX, mouseY, centerX, centerY)) {
            return;
        }
        
        // Handle main button clicks
        handleMainButtonClicks(mouseX, mouseY, centerX, centerY);
    }
    
    /**
     * Handles clicks on dropdown menus.
     * @return true if a dropdown interaction was handled
     */
    private boolean handleDropdownClick(double mouseX, double mouseY, float centerX, float centerY) {
        // Let dropdown button components handle their own click detection and state management
        return false;
    }
    
    /**
     * Handles clicks on main UI buttons using the button components.
     */
    private void handleMainButtonClicks(double mouseX, double mouseY, float centerX, float centerY) {
        float mouseXf = (float)mouseX;
        float mouseYf = (float)mouseY;
        
        // Update button positions first
        updateButtonPositions(centerX, centerY);
        
        // Handle button clicks
        if (stateManager.getResolutionButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.RESOLUTION.getIndex());
            return;
        }
        
        if (stateManager.getArmModelButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.ARM_MODEL.getIndex());
            return;
        }
        
        if (stateManager.getCrosshairStyleButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.CROSSHAIR_STYLE.getIndex());
            return;
        }
        
        if (stateManager.getApplyButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.APPLY.getIndex());
            return;
        }
        
        if (stateManager.getBackButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.BACK.getIndex());
            return;
        }
        
        // Handle volume slider
        if (stateManager.getVolumeSlider().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.VOLUME.getIndex());
            return;
        }
        
        // Handle crosshair size slider
        if (stateManager.getCrosshairSizeSlider().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedButton(ButtonSelection.CROSSHAIR_SIZE.getIndex());
            return;
        }
    }
    
    /**
     * Handles mouse release events.
     */
    private void handleMouseRelease() {
        // Stop slider dragging
        stateManager.getVolumeSlider().stopDragging();
        stateManager.getCrosshairSizeSlider().stopDragging();
    }
}