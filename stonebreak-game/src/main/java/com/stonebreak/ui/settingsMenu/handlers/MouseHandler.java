package com.stonebreak.ui.settingsMenu.handlers;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.CategoryButton;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Handles mouse input and interaction for the settings menu.
 * Manages mouse movement, clicks, and hover states.
 */
public class MouseHandler {
    
    private final StateManager stateManager;
    private ScrollableSettingsContainer scrollableContainer;
    
    public MouseHandler(StateManager stateManager) {
        this.stateManager = stateManager;
    }
    
    /**
     * Sets the scrollable container reference for scroll wheel handling.
     */
    public void setScrollableContainer(ScrollableSettingsContainer scrollableContainer) {
        this.scrollableContainer = scrollableContainer;
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
        stateManager.getLeafTransparencyButton().updateHover(mouseX, mouseY);
        stateManager.getApplyButton().updateHover(mouseX, mouseY);
        stateManager.getBackButton().updateHover(mouseX, mouseY);
        
        // Update slider hover states
        stateManager.getVolumeSlider().updateHover(mouseX, mouseY);
        stateManager.getCrosshairSizeSlider().updateHover(mouseX, mouseY);
        
        // Note: Hover states are now purely visual - they don't affect selection state
        // Only category buttons maintain selection state (handled in updateButtonSelectionStates)
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
                case LEAF_TRANSPARENCY:
                    wasClicked = stateManager.getLeafTransparencyButton().handleClick(mouseXf, mouseYf);
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
    
    /**
     * Handles mouse wheel scrolling for the scrollable settings container.
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     * @param scrollDelta Scroll wheel delta (positive for up, negative for down)
     * @return true if scroll was handled, false otherwise
     */
    public boolean handleMouseWheel(double mouseX, double mouseY, double scrollDelta) {
        if (scrollableContainer != null) {
            // Convert scroll delta to appropriate sensitivity
            float adjustedDelta = (float) scrollDelta;
            
            return scrollableContainer.handleMouseWheel((float)mouseX, (float)mouseY, adjustedDelta);
        }
        return false;
    }
}