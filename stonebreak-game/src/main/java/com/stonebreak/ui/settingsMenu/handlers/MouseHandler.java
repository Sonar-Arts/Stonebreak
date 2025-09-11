package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.CategoryButton;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
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
     * Updates hover states for all button and slider components.
     */
    private void updateButtonHoverStates(float mouseX, float mouseY) {
        // Update category button hover states
        for (CategoryButton categoryButton : stateManager.getCategoryButtons()) {
            categoryButton.setHovered(categoryButton.contains(mouseX, mouseY));
        }
        
        // Update settings component hover states
        stateManager.getResolutionButton().updateHover(mouseX, mouseY);
        stateManager.getArmModelButton().updateHover(mouseX, mouseY);
        stateManager.getCrosshairStyleButton().updateHover(mouseX, mouseY);
        stateManager.getApplyButton().updateHover(mouseX, mouseY);
        stateManager.getBackButton().updateHover(mouseX, mouseY);
        
        // Update slider hover states
        stateManager.getVolumeSlider().updateHover(mouseX, mouseY);
        stateManager.getCrosshairSizeSlider().updateHover(mouseX, mouseY);
        
        // Update selected state based on hover
        updateSelectedStateFromHover();
    }
    
    /**
     * Updates the selected setting based on which component is currently hovered.
     * Category buttons only show hover visual feedback but don't change selection on hover.
     */
    private void updateSelectedStateFromHover() {
        // Category buttons are handled separately - they only show hover visual feedback
        // Category selection only happens on actual clicks, not hover
        
        // Check for hovered setting components within current category
        CategoryState selectedCategory = stateManager.getSelectedCategory();
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        
        // Check settings in the current category
        for (int i = 0; i < settings.length; i++) {
            CategoryState.SettingType setting = settings[i];
            boolean isHovered = false;
            
            switch (setting) {
                case RESOLUTION:
                    isHovered = stateManager.getResolutionButton().isHovered();
                    break;
                case VOLUME:
                    isHovered = stateManager.getVolumeSlider().isHovered();
                    break;
                case ARM_MODEL:
                    isHovered = stateManager.getArmModelButton().isHovered();
                    break;
                case CROSSHAIR_STYLE:
                    isHovered = stateManager.getCrosshairStyleButton().isHovered();
                    break;
                case CROSSHAIR_SIZE:
                    isHovered = stateManager.getCrosshairSizeSlider().isHovered();
                    break;
            }
            
            if (isHovered) {
                stateManager.setSelectedSettingInCategory(i);
                return;
            }
        }
        
        // Check Apply/Back buttons
        if (stateManager.getApplyButton().isHovered()) {
            stateManager.setSelectedSettingInCategory(settings.length); // Apply button
        } else if (stateManager.getBackButton().isHovered()) {
            stateManager.setSelectedSettingInCategory(settings.length + 1); // Back button
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
        
        // Handle category button clicks first
        for (CategoryButton categoryButton : stateManager.getCategoryButtons()) {
            if (categoryButton.contains(mouseXf, mouseYf)) {
                categoryButton.onClick();
                return;
            }
        }
        
        // Handle settings component clicks for current category
        CategoryState selectedCategory = stateManager.getSelectedCategory();
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        
        // Check settings in the current category
        for (int i = 0; i < settings.length; i++) {
            CategoryState.SettingType setting = settings[i];
            boolean wasClicked = false;
            
            switch (setting) {
                case RESOLUTION:
                    wasClicked = stateManager.getResolutionButton().handleClick(mouseXf, mouseYf);
                    break;
                case VOLUME:
                    wasClicked = stateManager.getVolumeSlider().handleClick(mouseXf, mouseYf);
                    break;
                case ARM_MODEL:
                    wasClicked = stateManager.getArmModelButton().handleClick(mouseXf, mouseYf);
                    break;
                case CROSSHAIR_STYLE:
                    wasClicked = stateManager.getCrosshairStyleButton().handleClick(mouseXf, mouseYf);
                    break;
                case CROSSHAIR_SIZE:
                    wasClicked = stateManager.getCrosshairSizeSlider().handleClick(mouseXf, mouseYf);
                    break;
            }
            
            if (wasClicked) {
                stateManager.setSelectedSettingInCategory(i);
                return;
            }
        }
        
        // Handle Apply/Back button clicks
        if (stateManager.getApplyButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedSettingInCategory(settings.length); // Apply button
            return;
        }
        
        if (stateManager.getBackButton().handleClick(mouseXf, mouseYf)) {
            stateManager.setSelectedSettingInCategory(settings.length + 1); // Back button
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