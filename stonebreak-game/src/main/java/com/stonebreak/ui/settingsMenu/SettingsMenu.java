package com.stonebreak.ui.settingsMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.handlers.ActionHandler;
import com.stonebreak.ui.settingsMenu.handlers.InputHandler;
import com.stonebreak.ui.settingsMenu.handlers.MouseHandler;
import com.stonebreak.ui.settingsMenu.managers.SettingsManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import com.stonebreak.ui.settingsMenu.renderers.SettingsRenderer;

/**
 * Settings menu UI component that provides configuration options for display,
 * audio, and crosshair settings. Supports both keyboard and mouse navigation.
 * 
 * This class serves as a facade/coordinator for the modular settings menu system.
 */
public class SettingsMenu {
    
    // ===== MODULAR COMPONENTS =====
    private final StateManager stateManager;
    private final SettingsManager settingsManager;
    private final ActionHandler actionHandler;
    private final InputHandler inputHandler;
    private final MouseHandler mouseHandler;
    private final SettingsRenderer settingsRenderer;
    
    // ===== CONSTRUCTOR =====
    
    /**
     * Creates a new settings menu with the specified UI renderer.
     * Initializes all modular components and their dependencies.
     */
    public SettingsMenu(UIRenderer uiRenderer) {
        Settings settings = Settings.getInstance();
        
        // Initialize managers
        this.stateManager = new StateManager(settings, uiRenderer);
        this.settingsManager = new SettingsManager(settings);
        
        // Initialize handlers
        this.actionHandler = new ActionHandler(stateManager, settingsManager, settings);
        this.inputHandler = new InputHandler(stateManager, settings, actionHandler);
        this.mouseHandler = new MouseHandler(stateManager);
        
        // Initialize renderer
        this.settingsRenderer = new SettingsRenderer(uiRenderer, stateManager);
        
        // Set up component callbacks
        initializeCallbacks();
    }
    
    /**
     * Initializes the callback connections between components.
     */
    private void initializeCallbacks() {
        stateManager.setCallbacks(
            actionHandler::applySettings,
            actionHandler::goBack,
            actionHandler::onResolutionChange,
            actionHandler::onArmModelChange,
            actionHandler::onCrosshairStyleChange,
            actionHandler::onVolumeChange,
            actionHandler::onCrosshairSizeChange,
            actionHandler::toggleLeafTransparency,
            actionHandler::toggleWaterShader
        );
        
        // Connect MouseHandler with the scrollable container
        mouseHandler.setScrollableContainer(settingsRenderer.getSectionRenderer().getScrollableContainer());
    }
    
    // ===== INPUT HANDLING =====
    
    /**
     * Handles all keyboard input for the settings menu.
     */
    public void handleInput(long window) {
        inputHandler.handleInput(window);
    }
    
    // ===== MOUSE HANDLING =====
    
    /**
     * Handles mouse movement for hover effects and dragging interactions.
     */
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        mouseHandler.handleMouseMove(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    
    /**
     * Handles mouse click events for button activation and slider interaction.
     */
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        mouseHandler.handleMouseClick(mouseX, mouseY, windowWidth, windowHeight, button, action);
    }
    
    /**
     * Handles mouse wheel scrolling for the scrollable settings container.
     */
    public boolean handleMouseWheel(double mouseX, double mouseY, double scrollDelta) {
        return mouseHandler.handleMouseWheel(mouseX, mouseY, scrollDelta);
    }
    
    // ===== PUBLIC API =====
    
    /**
     * Sets the previous game state to return to when going back.
     * @param state the state to return to
     */
    public void setPreviousState(GameState state) {
        stateManager.setPreviousState(state);
    }
    
    /**
     * Gets the currently selected button index.
     * @return selected button index
     */
    public int getSelectedButton() {
        return stateManager.getSelectedButton();
    }
    
    
    // ===== RENDERING =====
    
    /**
     * Renders the complete settings menu UI using modular rendering system.
     */
    public void render(int windowWidth, int windowHeight) {
        settingsRenderer.render(windowWidth, windowHeight);
    }
    
}