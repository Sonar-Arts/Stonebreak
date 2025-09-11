package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.config.Settings;
import com.stonebreak.audio.SoundSystem;
import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.core.Main;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;

/**
 * Settings menu UI component that provides configuration options for display,
 * audio, and crosshair settings. Supports both keyboard and mouse navigation.
 */
public class SettingsMenu {
    
    // ===== BUTTON SELECTION ENUM =====
    private enum ButtonSelection {
        RESOLUTION(0),
        VOLUME(1),
        ARM_MODEL(2),
        CROSSHAIR_STYLE(3),
        CROSSHAIR_SIZE(4),
        APPLY(5),
        BACK(6);
        
        private final int index;
        
        ButtonSelection(int index) {
            this.index = index;
        }
        
        public int getIndex() {
            return index;
        }
        
        public static ButtonSelection fromIndex(int index) {
            for (ButtonSelection button : values()) {
                if (button.index == index) {
                    return button;
                }
            }
            return null;
        }
    }
    
    // ===== ARM MODEL CONFIGURATION =====
    private static final String[] ARM_MODEL_TYPES = {
        "REGULAR", "SLIM"
    };
    private static final String[] ARM_MODEL_NAMES = {
        "Regular (4px wide)", "Slim (3px wide)"
    };
    
    // ===== CROSSHAIR CONFIGURATION =====
    private static final String[] CROSSHAIR_STYLES = {
        "SIMPLE_CROSS", "DOT", "CIRCLE", "SQUARE", "T_SHAPE", "PLUS_DOT"
    };
    private static final String[] CROSSHAIR_STYLE_NAMES = {
        "Simple Cross", "Dot", "Circle", "Square", "T-Shape", "Plus + Dot"
    };
    private static final float MIN_CROSSHAIR_SIZE = 4.0f;
    private static final float MAX_CROSSHAIR_SIZE = 64.0f;
    private static final float CROSSHAIR_SIZE_STEP = 2.0f;
    
    // ===== UI DIMENSIONS =====
    private static final float BUTTON_WIDTH = 400;
    private static final float BUTTON_HEIGHT = 40;
    private static final float SLIDER_WIDTH = 300;
    private static final float SLIDER_HEIGHT = 20;
    private static final float DROPDOWN_ITEM_HEIGHT = 30;
    
    // ===== AUDIO CONFIGURATION =====
    private static final float VOLUME_STEP = 0.1f;
    private static final float MIN_VOLUME = 0.0f;
    private static final float MAX_VOLUME = 1.0f;
    
    // ===== CORE DEPENDENCIES =====
    private final UIRenderer uiRenderer;
    private final Settings settings;
    
    // ===== UI COMPONENTS =====
    private Button applyButton;
    private Button backButton;
    private DropdownButton resolutionButton;
    private DropdownButton armModelButton;
    private DropdownButton crosshairStyleButton;
    private Slider volumeSlider;
    private Slider crosshairSizeSlider;
    
    // ===== UI STATE =====
    private int selectedButton = ButtonSelection.RESOLUTION.getIndex();
    private GameState previousState = GameState.MAIN_MENU;
    
    // ===== DROPDOWN STATE =====
    private int selectedResolutionIndex = 0;
    private int selectedArmModelIndex = 0;
    private int selectedCrosshairStyleIndex = 0;
    private boolean escapeKeyPressed = false;
    
    // ===== CONSTRUCTOR =====
    
    /**
     * Creates a new settings menu with the specified UI renderer.
     * Initializes all settings to their current values.
     */
    public SettingsMenu(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
        this.settings = Settings.getInstance();
        initializeSettingsState();
        initializeButtons();
    }
    
    /**
     * Initializes the UI state based on current settings values.
     */
    private void initializeSettingsState() {
        this.selectedResolutionIndex = settings.getCurrentResolutionIndex();
        this.selectedArmModelIndex = findArmModelIndex(settings.getArmModelType());
        this.selectedCrosshairStyleIndex = findCrosshairStyleIndex(settings.getCrosshairStyle());
    }
    
    /**
     * Initializes the button components with their properties and actions.
     */
    private void initializeButtons() {
        // Initialize Apply and Back buttons (positions will be set during render)
        applyButton = new Button("Apply Settings", 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, this::applySettings);
        backButton = new Button("Back", 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, this::goBack);
        
        // Initialize dropdown buttons
        int[][] resolutions = Settings.getAvailableResolutions();
        String[] resolutionStrings = createResolutionStrings(resolutions);
        resolutionButton = new DropdownButton("Resolution", 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, 
                                            resolutionStrings, DROPDOWN_ITEM_HEIGHT, this::onResolutionChange);
        resolutionButton.setSelectedItemIndex(selectedResolutionIndex);
        
        armModelButton = new DropdownButton("Arm Model", 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, 
                                          ARM_MODEL_NAMES, DROPDOWN_ITEM_HEIGHT, this::onArmModelChange);
        armModelButton.setSelectedItemIndex(selectedArmModelIndex);
        
        crosshairStyleButton = new DropdownButton("Crosshair", 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, 
                                                 CROSSHAIR_STYLE_NAMES, DROPDOWN_ITEM_HEIGHT, this::onCrosshairStyleChange);
        crosshairStyleButton.setSelectedItemIndex(selectedCrosshairStyleIndex);
        
        // Initialize sliders
        volumeSlider = new Slider("Master Volume", 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, 
                                MIN_VOLUME, MAX_VOLUME, settings.getMasterVolume(), this::onVolumeChange);
        
        crosshairSizeSlider = new Slider("Crosshair Size", 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, 
                                       MIN_CROSSHAIR_SIZE, MAX_CROSSHAIR_SIZE, settings.getCrosshairSize(), this::onCrosshairSizeChange);
    }
    
    /**
     * Finds the index of the specified arm model type in the available types array.
     */
    private int findArmModelIndex(String currentArmModel) {
        for (int i = 0; i < ARM_MODEL_TYPES.length; i++) {
            if (ARM_MODEL_TYPES[i].equals(currentArmModel)) {
                return i;
            }
        }
        return 0; // Default to first type if not found
    }
    
    /**
     * Finds the index of the specified crosshair style in the available styles array.
     */
    private int findCrosshairStyleIndex(String currentStyle) {
        for (int i = 0; i < CROSSHAIR_STYLES.length; i++) {
            if (CROSSHAIR_STYLES[i].equals(currentStyle)) {
                return i;
            }
        }
        return 0; // Default to first style if not found
    }
    
    // ===== INPUT HANDLING =====
    
    /**
     * Handles all keyboard input for the settings menu.
     */
    public void handleInput(long window) {
        handleNavigationKeys(window);
        handleValueAdjustmentKeys(window);
        handleActionKeys(window);
        handleEscapeKey(window);
    }
    
    /**
     * Handles up/down navigation keys for button selection.
     */
    private void handleNavigationKeys(long window) {
        if (isKeyPressed(window, GLFW_KEY_UP, GLFW_KEY_W)) {
            selectedButton = Math.max(0, selectedButton - 1);
        }
        if (isKeyPressed(window, GLFW_KEY_DOWN, GLFW_KEY_S)) {
            selectedButton = Math.min(ButtonSelection.BACK.getIndex(), selectedButton + 1);
        }
    }
    
    /**
     * Handles left/right keys for adjusting setting values.
     */
    private void handleValueAdjustmentKeys(long window) {
        if (isKeyPressed(window, GLFW_KEY_LEFT, GLFW_KEY_A)) {
            adjustSelectedSettingValue(-1);
        }
        if (isKeyPressed(window, GLFW_KEY_RIGHT, GLFW_KEY_D)) {
            adjustSelectedSettingValue(1);
        }
    }
    
    /**
     * Handles action keys like Enter.
     */
    private void handleActionKeys(long window) {
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
            if (selectedButton == ButtonSelection.RESOLUTION.getIndex() && resolutionButton.isDropdownOpen()) {
                confirmResolutionSelection();
            } else {
                executeSelectedAction();
            }
        }
    }
    
    /**
     * Handles escape key with edge detection to prevent repeated triggers.
     */
    private void handleEscapeKey(long window) {
        boolean isEscapePressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        if (isEscapePressed && !escapeKeyPressed) {
            escapeKeyPressed = true;
            goBack();
        } else if (!isEscapePressed) {
            escapeKeyPressed = false;
        }
    }
    
    /**
     * Utility method to check if any of the specified keys are pressed.
     */
    private boolean isKeyPressed(long window, int... keys) {
        for (int key : keys) {
            if (glfwGetKey(window, key) == GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Adjusts the currently selected setting value by the specified direction.
     * @param direction -1 for decrease, 1 for increase
     */
    private void adjustSelectedSettingValue(int direction) {
        ButtonSelection button = ButtonSelection.fromIndex(selectedButton);
        if (button == null) return;
        
        switch (button) {
            case RESOLUTION -> adjustResolutionSetting(direction);
            case VOLUME -> adjustVolumeSetting(direction);
            case ARM_MODEL -> adjustArmModelSetting(direction);
            case CROSSHAIR_STYLE -> adjustCrosshairStyleSetting(direction);
            case CROSSHAIR_SIZE -> adjustCrosshairSizeSetting(direction);
        }
    }
    
    /**
     * Adjusts resolution setting or dropdown selection.
     */
    private void adjustResolutionSetting(int direction) {
        if (resolutionButton.isDropdownOpen()) {
            resolutionButton.adjustSelection(direction);
            selectedResolutionIndex = resolutionButton.getSelectedItemIndex();
        } else {
            int currentIndex = settings.getCurrentResolutionIndex();
            int[][] resolutions = Settings.getAvailableResolutions();
            int newIndex = Math.max(0, Math.min(resolutions.length - 1, currentIndex + direction));
            settings.setResolutionByIndex(newIndex);
            selectedResolutionIndex = newIndex;
            resolutionButton.setSelectedItemIndex(newIndex);
        }
    }
    
    /**
     * Adjusts volume setting.
     */
    private void adjustVolumeSetting(int direction) {
        volumeSlider.adjustValue(direction * VOLUME_STEP);
    }
    
    /**
     * Adjusts arm model setting or dropdown selection.
     */
    private void adjustArmModelSetting(int direction) {
        if (armModelButton.isDropdownOpen()) {
            armModelButton.adjustSelection(direction);
            selectedArmModelIndex = armModelButton.getSelectedItemIndex();
        } else {
            selectedArmModelIndex = Math.max(0, Math.min(ARM_MODEL_TYPES.length - 1, selectedArmModelIndex + direction));
            settings.setArmModelType(ARM_MODEL_TYPES[selectedArmModelIndex]);
            armModelButton.setSelectedItemIndex(selectedArmModelIndex);
        }
    }
    
    /**
     * Adjusts crosshair style setting or dropdown selection.
     */
    private void adjustCrosshairStyleSetting(int direction) {
        if (crosshairStyleButton.isDropdownOpen()) {
            crosshairStyleButton.adjustSelection(direction);
            selectedCrosshairStyleIndex = crosshairStyleButton.getSelectedItemIndex();
        } else {
            selectedCrosshairStyleIndex = Math.max(0, Math.min(CROSSHAIR_STYLES.length - 1, selectedCrosshairStyleIndex + direction));
            settings.setCrosshairStyle(CROSSHAIR_STYLES[selectedCrosshairStyleIndex]);
            crosshairStyleButton.setSelectedItemIndex(selectedCrosshairStyleIndex);
        }
    }
    
    /**
     * Adjusts crosshair size setting.
     */
    private void adjustCrosshairSizeSetting(int direction) {
        crosshairSizeSlider.adjustValue(direction * CROSSHAIR_SIZE_STEP);
    }
    
    /**
     * Confirms resolution selection from dropdown and closes it.
     */
    private void confirmResolutionSelection() {
        settings.setResolutionByIndex(selectedResolutionIndex);
        resolutionButton.closeDropdown();
    }
    
    // ===== MOUSE HANDLING =====
    
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
        volumeSlider.handleDragging((float)mouseX);
        crosshairSizeSlider.handleDragging((float)mouseX);
    }
    
    
    /**
     * Updates button and slider positions based on current center coordinates.
     */
    private void updateButtonPositions(float centerX, float centerY) {
        resolutionButton.setPosition(centerX - BUTTON_WIDTH/2, centerY - 170);
        armModelButton.setPosition(centerX - BUTTON_WIDTH/2, centerY - 50);
        crosshairStyleButton.setPosition(centerX - BUTTON_WIDTH/2, centerY + 10);
        applyButton.setPosition(centerX - BUTTON_WIDTH/2, centerY + 130);
        backButton.setPosition(centerX - BUTTON_WIDTH/2, centerY + 190);
        
        // Update slider positions
        volumeSlider.setPosition(centerX, centerY - 110);
        crosshairSizeSlider.setPosition(centerX, centerY + 70);
    }
    
    /**
     * Updates hover states for all button and slider components.
     */
    private void updateButtonHoverStates(float mouseX, float mouseY) {
        resolutionButton.updateHover(mouseX, mouseY);
        armModelButton.updateHover(mouseX, mouseY);
        crosshairStyleButton.updateHover(mouseX, mouseY);
        applyButton.updateHover(mouseX, mouseY);
        backButton.updateHover(mouseX, mouseY);
        
        // Update slider hover states
        volumeSlider.updateHover(mouseX, mouseY);
        crosshairSizeSlider.updateHover(mouseX, mouseY);
        
        // Update selected button index based on hover
        updateSelectedButtonFromHover();
    }
    
    /**
     * Updates the selected button index based on which component is currently hovered.
     */
    private void updateSelectedButtonFromHover() {
        if (resolutionButton.isHovered()) {
            selectedButton = ButtonSelection.RESOLUTION.getIndex();
        } else if (volumeSlider.isHovered()) {
            selectedButton = ButtonSelection.VOLUME.getIndex();
        } else if (armModelButton.isHovered()) {
            selectedButton = ButtonSelection.ARM_MODEL.getIndex();
        } else if (crosshairStyleButton.isHovered()) {
            selectedButton = ButtonSelection.CROSSHAIR_STYLE.getIndex();
        } else if (crosshairSizeSlider.isHovered()) {
            selectedButton = ButtonSelection.CROSSHAIR_SIZE.getIndex();
        } else if (applyButton.isHovered()) {
            selectedButton = ButtonSelection.APPLY.getIndex();
        } else if (backButton.isHovered()) {
            selectedButton = ButtonSelection.BACK.getIndex();
        }
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
        if (resolutionButton.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.RESOLUTION.getIndex();
            return;
        }
        
        if (armModelButton.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.ARM_MODEL.getIndex();
            return;
        }
        
        if (crosshairStyleButton.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.CROSSHAIR_STYLE.getIndex();
            return;
        }
        
        if (applyButton.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.APPLY.getIndex();
            return;
        }
        
        if (backButton.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.BACK.getIndex();
            return;
        }
        
        // Handle volume slider
        if (volumeSlider.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.VOLUME.getIndex();
            return;
        }
        
        // Handle crosshair size slider
        if (crosshairSizeSlider.handleClick(mouseXf, mouseYf)) {
            selectedButton = ButtonSelection.CROSSHAIR_SIZE.getIndex();
            return;
        }
    }
    
    /**
     * Handles mouse release events.
     */
    private void handleMouseRelease() {
        // Stop slider dragging
        volumeSlider.stopDragging();
        crosshairSizeSlider.stopDragging();
    }
    
    
    
    
    
    
    // ===== ACTION EXECUTION =====
    
    /**
     * Executes the action associated with the currently selected button.
     */
    private void executeSelectedAction() {
        ButtonSelection button = ButtonSelection.fromIndex(selectedButton);
        if (button == null) return;
        
        switch (button) {
            case RESOLUTION -> resolutionButton.toggleDropdown();
            case VOLUME -> {} // Volume handled by mouse/keyboard interaction
            case ARM_MODEL -> armModelButton.toggleDropdown();
            case CROSSHAIR_STYLE -> crosshairStyleButton.toggleDropdown();
            case CROSSHAIR_SIZE -> {} // Crosshair size handled by mouse/keyboard interaction
            case APPLY -> applyButton.onClick();
            case BACK -> backButton.onClick();
        }
    }
    
    
    // ===== SETTINGS APPLICATION =====
    
    /**
     * Applies all current settings and saves them to persistent storage.
     */
    private void applySettings() {
        settings.saveSettings();
        
        applyAudioSettings();
        applyCrosshairSettings();
        applyDisplaySettings();
        
        System.out.println("Settings applied successfully!");
        goBack();
    }
    
    /**
     * Applies audio-related settings to the sound system.
     */
    private void applyAudioSettings() {
        SoundSystem soundSystem = SoundSystem.getInstance();
        if (soundSystem != null) {
            soundSystem.setMasterVolume(settings.getMasterVolume());
            System.out.println("Applied master volume: " + settings.getMasterVolume());
        }
    }
    
    /**
     * Applies display-related settings like window resolution.
     */
    private void applyDisplaySettings() {
        applyWindowResize();
    }
    
    /**
     * Applies crosshair-related settings to the crosshair renderer.
     */
    private void applyCrosshairSettings() {
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
    
    // ===== NAVIGATION =====
    
    /**
     * Navigates back to the previous game state.
     */
    private void goBack() {
        if (previousState == GameState.PLAYING) {
            returnToGameplay();
        } else {
            returnToMainMenu();
        }
    }
    
    /**
     * Returns to active gameplay, properly handling pause state.
     */
    private void returnToGameplay() {
        Game game = Game.getInstance();
        
        // Resume game directly for cleaner user experience
        game.setState(GameState.PLAYING);
        game.getPauseMenu().setVisible(false);
        
        // Ensure game is unpaused
        if (game.isPaused()) {
            game.togglePauseMenu();
        }
    }
    
    /**
     * Returns to the main menu.
     */
    private void returnToMainMenu() {
        Game.getInstance().setState(GameState.MAIN_MENU);
    }
    
    // ===== PUBLIC API =====
    
    /**
     * Sets the previous game state to return to when going back.
     * @param state the state to return to
     */
    public void setPreviousState(GameState state) {
        this.previousState = state;
    }
    
    /**
     * Gets the currently selected button index.
     * @return selected button index
     */
    public int getSelectedButton() {
        return selectedButton;
    }
    
    
    // ===== RENDERING =====
    
    /**
     * Renders the complete settings menu UI using two-phase rendering.
     * Phase 1: Render all buttons and UI elements
     * Phase 2: Render dropdowns on top
     */
    public void render(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Phase 1: Render background and all UI components
        renderMenuBackground(windowWidth, windowHeight);
        renderSettingSections(centerX, centerY);
        renderActionButtons(centerX, centerY);
        
        // Phase 2: Render all dropdown menus on top
        renderDropdowns();
    }
    
    /**
     * Renders the background and base menu structure.
     */
    private void renderMenuBackground(int windowWidth, int windowHeight) {
        uiRenderer.renderSettingsMenu(windowWidth, windowHeight);
    }
    
    /**
     * Renders all settings sections (display, audio, crosshair).
     */
    private void renderSettingSections(float centerX, float centerY) {
        // Update button positions and selection states
        updateButtonPositions(centerX, centerY);
        updateButtonSelectionStates();
        
        renderDisplaySettings(centerX, centerY);
        renderAudioSettings(centerX, centerY);
        renderArmModelSettings(centerX, centerY);
        renderCrosshairSettings(centerX, centerY);
    }
    
    /**
     * Renders all dropdown menus on top of other UI elements.
     */
    private void renderDropdowns() {
        // Render dropdowns in the order they should appear (top to bottom)
        resolutionButton.renderDropdown(uiRenderer);
        armModelButton.renderDropdown(uiRenderer);
        crosshairStyleButton.renderDropdown(uiRenderer);
    }
    
    /**
     * Renders display-related settings section.
     */
    private void renderDisplaySettings(float centerX, float centerY) {
        // Update button text to show current resolution
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        resolutionButton.setText(resolutionText);
        
        // Render the dropdown button component
        resolutionButton.render(uiRenderer);
        
        uiRenderer.drawSeparator(centerX, centerY - 140, BUTTON_WIDTH * 0.8f);
    }
    
    /**
     * Renders audio-related settings section.
     */
    private void renderAudioSettings(float centerX, float centerY) {
        // Render the volume slider component
        volumeSlider.render(uiRenderer);
        
        uiRenderer.drawSeparator(centerX, centerY - 80, BUTTON_WIDTH * 0.8f);
    }
    
    /**
     * Renders arm model settings section.
     */
    private void renderArmModelSettings(float centerX, float centerY) {
        // Update button text to show current arm model
        String currentArmModelName = ARM_MODEL_NAMES[selectedArmModelIndex];
        String armModelText = "Arm Model: " + currentArmModelName;
        armModelButton.setText(armModelText);
        
        // Render the dropdown button component
        armModelButton.render(uiRenderer);
        
        uiRenderer.drawSeparator(centerX, centerY - 20, BUTTON_WIDTH * 0.8f);
    }
    
    /**
     * Renders crosshair-related settings section.
     */
    private void renderCrosshairSettings(float centerX, float centerY) {
        renderCrosshairStyleSetting(centerX, centerY);
        renderCrosshairSizeSetting(centerX, centerY);
        
        uiRenderer.drawSeparator(centerX, centerY + 70, BUTTON_WIDTH * 0.8f);
    }
    
    /**
     * Renders crosshair style dropdown setting.
     */
    private void renderCrosshairStyleSetting(float centerX, float centerY) {
        // Update button text to show current crosshair style
        String currentStyleName = CROSSHAIR_STYLE_NAMES[selectedCrosshairStyleIndex];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        crosshairStyleButton.setText(crosshairStyleText);
        
        // Render the dropdown button component
        crosshairStyleButton.render(uiRenderer);
    }
    
    /**
     * Renders crosshair size slider setting.
     */
    private void renderCrosshairSizeSetting(float centerX, float centerY) {
        // Render the crosshair size slider component
        crosshairSizeSlider.render(uiRenderer);
    }
    
    /**
     * Renders action buttons (Apply, Back) using button components.
     */
    private void renderActionButtons(float centerX, float centerY) {
        applyButton.render(uiRenderer);
        backButton.render(uiRenderer);
    }
    
    
    /**
     * Creates display strings for resolution options.
     */
    private String[] createResolutionStrings(int[][] resolutions) {
        String[] resolutionStrings = new String[resolutions.length];
        for (int i = 0; i < resolutions.length; i++) {
            resolutionStrings[i] = resolutions[i][0] + "x" + resolutions[i][1];
        }
        return resolutionStrings;
    }
    
    /**
     * Updates the selection state of all UI components based on the current selected button.
     */
    private void updateButtonSelectionStates() {
        resolutionButton.setSelected(selectedButton == ButtonSelection.RESOLUTION.getIndex());
        volumeSlider.setSelected(selectedButton == ButtonSelection.VOLUME.getIndex());
        armModelButton.setSelected(selectedButton == ButtonSelection.ARM_MODEL.getIndex());
        crosshairStyleButton.setSelected(selectedButton == ButtonSelection.CROSSHAIR_STYLE.getIndex());
        crosshairSizeSlider.setSelected(selectedButton == ButtonSelection.CROSSHAIR_SIZE.getIndex());
        applyButton.setSelected(selectedButton == ButtonSelection.APPLY.getIndex());
        backButton.setSelected(selectedButton == ButtonSelection.BACK.getIndex());
    }
    
    // ===== BUTTON CALLBACK METHODS =====
    
    /**
     * Callback for when resolution selection changes.
     */
    private void onResolutionChange() {
        int newIndex = resolutionButton.getSelectedItemIndex();
        settings.setResolutionByIndex(newIndex);
        selectedResolutionIndex = newIndex;
    }
    
    /**
     * Callback for when arm model selection changes.
     */
    private void onArmModelChange() {
        int newIndex = armModelButton.getSelectedItemIndex();
        settings.setArmModelType(ARM_MODEL_TYPES[newIndex]);
        selectedArmModelIndex = newIndex;
    }
    
    /**
     * Callback for when crosshair style selection changes.
     */
    private void onCrosshairStyleChange() {
        int newIndex = crosshairStyleButton.getSelectedItemIndex();
        settings.setCrosshairStyle(CROSSHAIR_STYLES[newIndex]);
        selectedCrosshairStyleIndex = newIndex;
    }
    
    /**
     * Callback for when volume slider value changes.
     */
    private void onVolumeChange(Float newVolume) {
        settings.setMasterVolume(newVolume);
    }
    
    /**
     * Callback for when crosshair size slider value changes.
     */
    private void onCrosshairSizeChange(Float newSize) {
        settings.setCrosshairSize(newSize);
    }
    
}