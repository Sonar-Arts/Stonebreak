package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.*;

import com.stonebreak.config.Settings;
import com.stonebreak.audio.SoundSystem;
import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.core.Main;
import com.stonebreak.rendering.UI.UIRenderer;

/**
 * Settings menu UI component that provides configuration options for display,
 * audio, and crosshair settings. Supports both keyboard and mouse navigation.
 */
public class SettingsMenu {
    
    // ===== BUTTON SELECTION ENUM =====
    private enum ButtonSelection {
        RESOLUTION(0),
        VOLUME(1),
        CROSSHAIR_STYLE(2),
        CROSSHAIR_SIZE(3),
        APPLY(4),
        BACK(5);
        
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
    private static final float VOLUME_BUTTON_HEIGHT = 60;
    
    // ===== AUDIO CONFIGURATION =====
    private static final float VOLUME_STEP = 0.1f;
    private static final float MIN_VOLUME = 0.0f;
    private static final float MAX_VOLUME = 1.0f;
    
    // ===== CORE DEPENDENCIES =====
    private final UIRenderer uiRenderer;
    private final Settings settings;
    
    // ===== UI STATE =====
    private int selectedButton = ButtonSelection.RESOLUTION.getIndex();
    private GameState previousState = GameState.MAIN_MENU;
    
    // ===== DROPDOWN STATE =====
    private boolean isResolutionDropdownOpen = false;
    private boolean isCrosshairStyleDropdownOpen = false;
    private int selectedResolutionIndex = 0;
    private int selectedCrosshairStyleIndex = 0;
    
    // ===== INTERACTION STATE =====
    private boolean isDraggingVolume = false;
    private boolean isDraggingCrosshairSize = false;
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
    }
    
    /**
     * Initializes the UI state based on current settings values.
     */
    private void initializeSettingsState() {
        this.selectedResolutionIndex = settings.getCurrentResolutionIndex();
        this.selectedCrosshairStyleIndex = findCrosshairStyleIndex(settings.getCrosshairStyle());
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
            if (selectedButton == ButtonSelection.RESOLUTION.getIndex() && isResolutionDropdownOpen) {
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
            case CROSSHAIR_STYLE -> adjustCrosshairStyleSetting(direction);
            case CROSSHAIR_SIZE -> adjustCrosshairSizeSetting(direction);
        }
    }
    
    /**
     * Adjusts resolution setting or dropdown selection.
     */
    private void adjustResolutionSetting(int direction) {
        if (isResolutionDropdownOpen) {
            int[][] resolutions = Settings.getAvailableResolutions();
            selectedResolutionIndex = Math.max(0, Math.min(resolutions.length - 1, selectedResolutionIndex + direction));
        } else {
            int currentIndex = settings.getCurrentResolutionIndex();
            int[][] resolutions = Settings.getAvailableResolutions();
            int newIndex = Math.max(0, Math.min(resolutions.length - 1, currentIndex + direction));
            settings.setResolutionByIndex(newIndex);
            selectedResolutionIndex = newIndex;
        }
    }
    
    /**
     * Adjusts volume setting.
     */
    private void adjustVolumeSetting(int direction) {
        float currentVolume = settings.getMasterVolume();
        float newVolume = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, currentVolume + (direction * VOLUME_STEP)));
        settings.setMasterVolume(newVolume);
    }
    
    /**
     * Adjusts crosshair style setting or dropdown selection.
     */
    private void adjustCrosshairStyleSetting(int direction) {
        if (isCrosshairStyleDropdownOpen) {
            selectedCrosshairStyleIndex = Math.max(0, Math.min(CROSSHAIR_STYLES.length - 1, selectedCrosshairStyleIndex + direction));
        } else {
            selectedCrosshairStyleIndex = Math.max(0, Math.min(CROSSHAIR_STYLES.length - 1, selectedCrosshairStyleIndex + direction));
            settings.setCrosshairStyle(CROSSHAIR_STYLES[selectedCrosshairStyleIndex]);
        }
    }
    
    /**
     * Adjusts crosshair size setting.
     */
    private void adjustCrosshairSizeSetting(int direction) {
        float currentSize = settings.getCrosshairSize();
        float newSize = Math.max(MIN_CROSSHAIR_SIZE, Math.min(MAX_CROSSHAIR_SIZE, currentSize + (direction * CROSSHAIR_SIZE_STEP)));
        settings.setCrosshairSize(newSize);
    }
    
    /**
     * Confirms resolution selection from dropdown and closes it.
     */
    private void confirmResolutionSelection() {
        settings.setResolutionByIndex(selectedResolutionIndex);
        isResolutionDropdownOpen = false;
    }
    
    // ===== MOUSE HANDLING =====
    
    /**
     * Handles mouse movement for hover effects and dragging interactions.
     */
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        handleDropdownHover(mouseX, mouseY, centerX, centerY);
        updateButtonHover(mouseX, mouseY, centerX, centerY);
        handleVolumeDragging(mouseX, centerX);
        handleCrosshairSizeDragging(mouseX, centerX);
    }
    
    /**
     * Handles mouse hover over dropdown menus.
     */
    private void handleDropdownHover(double mouseX, double mouseY, float centerX, float centerY) {
        if (isResolutionDropdownOpen) {
            int dropdownItem = getResolutionDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
            if (dropdownItem >= 0) {
                selectedResolutionIndex = dropdownItem;
            }
        }
        
        if (isCrosshairStyleDropdownOpen) {
            int dropdownItem = getCrosshairStyleDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
            if (dropdownItem >= 0) {
                selectedCrosshairStyleIndex = dropdownItem;
            }
        }
    }
    
    /**
     * Updates which button is currently hovered by the mouse.
     */
    private void updateButtonHover(double mouseX, double mouseY, float centerX, float centerY) {
        // Skip button hover when any dropdown is open
        if (isResolutionDropdownOpen || isCrosshairStyleDropdownOpen) {
            return;
        }
        
        selectedButton = determineHoveredButton((float)mouseX, (float)mouseY, centerX, centerY);
    }
    
    /**
     * Determines which button the mouse is currently hovering over.
     */
    private int determineHoveredButton(float mouseX, float mouseY, float centerX, float centerY) {
        // Check resolution button
        if (isMouseOverButton(mouseX, mouseY, centerX - BUTTON_WIDTH/2, centerY - 140, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return ButtonSelection.RESOLUTION.getIndex();
        }
        // Check volume area
        if (isMouseOverVolumeArea(mouseX, mouseY, centerX, centerY - 80)) {
            return ButtonSelection.VOLUME.getIndex();
        }
        // Check crosshair style button
        if (isMouseOverButton(mouseX, mouseY, centerX - BUTTON_WIDTH/2, centerY - 20, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return ButtonSelection.CROSSHAIR_STYLE.getIndex();
        }
        // Check crosshair size area
        if (isMouseOverVolumeArea(mouseX, mouseY, centerX, centerY + 40)) {
            return ButtonSelection.CROSSHAIR_SIZE.getIndex();
        }
        // Check apply button
        if (isMouseOverButton(mouseX, mouseY, centerX - BUTTON_WIDTH/2, centerY + 100, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return ButtonSelection.APPLY.getIndex();
        }
        // Check back button
        if (isMouseOverButton(mouseX, mouseY, centerX - BUTTON_WIDTH/2, centerY + 160, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return ButtonSelection.BACK.getIndex();
        }
        
        return -1; // No button hovered
    }
    
    /**
     * Handles volume slider dragging when mouse is being dragged.
     */
    private void handleVolumeDragging(double mouseX, float centerX) {
        if (isDraggingVolume) {
            updateVolumeFromMousePosition((float)mouseX, centerX);
        }
    }
    
    /**
     * Handles crosshair size slider dragging when mouse is being dragged.
     */
    private void handleCrosshairSizeDragging(double mouseX, float centerX) {
        if (isDraggingCrosshairSize) {
            updateCrosshairSizeFromMousePosition((float)mouseX, centerX);
        }
    }
    
    /**
     * Updates volume setting based on mouse position along the slider.
     */
    private void updateVolumeFromMousePosition(float mouseX, float centerX) {
        float sliderX = centerX - SLIDER_WIDTH/2;
        float relativeX = mouseX - sliderX;
        float newVolume = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, relativeX / SLIDER_WIDTH));
        settings.setMasterVolume(newVolume);
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
        if (isResolutionDropdownOpen) {
            int dropdownItem = getResolutionDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
            if (dropdownItem >= 0) {
                selectResolutionFromDropdown(dropdownItem);
            } else {
                isResolutionDropdownOpen = false; // Close dropdown when clicking outside
            }
            return true;
        }
        
        if (isCrosshairStyleDropdownOpen) {
            int dropdownItem = getCrosshairStyleDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
            if (dropdownItem >= 0) {
                selectCrosshairStyleFromDropdown(dropdownItem);
            } else {
                isCrosshairStyleDropdownOpen = false; // Close dropdown when clicking outside
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Handles clicks on main UI buttons.
     */
    private void handleMainButtonClicks(double mouseX, double mouseY, float centerX, float centerY) {
        float mouseXf = (float)mouseX;
        float mouseYf = (float)mouseY;
        
        // Resolution button
        if (isMouseOverButton(mouseXf, mouseYf, centerX - BUTTON_WIDTH/2, centerY - 140, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = ButtonSelection.RESOLUTION.getIndex();
            toggleResolutionDropdown();
        }
        // Volume slider
        else if (isMouseOverVolumeArea(mouseXf, mouseYf, centerX, centerY - 80)) {
            selectedButton = ButtonSelection.VOLUME.getIndex();
            startVolumeDragging(mouseXf, centerX);
        }
        // Crosshair style button
        else if (isMouseOverButton(mouseXf, mouseYf, centerX - BUTTON_WIDTH/2, centerY - 20, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = ButtonSelection.CROSSHAIR_STYLE.getIndex();
            executeSelectedAction();
        }
        // Crosshair size slider
        else if (isMouseOverVolumeArea(mouseXf, mouseYf, centerX, centerY + 40)) {
            selectedButton = ButtonSelection.CROSSHAIR_SIZE.getIndex();
            startCrosshairSizeDragging(mouseXf, centerX);
        }
        // Apply button
        else if (isMouseOverButton(mouseXf, mouseYf, centerX - BUTTON_WIDTH/2, centerY + 100, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = ButtonSelection.APPLY.getIndex();
            executeSelectedAction();
        }
        // Back button
        else if (isMouseOverButton(mouseXf, mouseYf, centerX - BUTTON_WIDTH/2, centerY + 160, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = ButtonSelection.BACK.getIndex();
            executeSelectedAction();
        }
    }
    
    /**
     * Handles mouse release events.
     */
    private void handleMouseRelease() {
        isDraggingVolume = false;
        isDraggingCrosshairSize = false;
    }
    
    /**
     * Selects a resolution from the dropdown menu.
     */
    private void selectResolutionFromDropdown(int dropdownItem) {
        settings.setResolutionByIndex(dropdownItem);
        selectedResolutionIndex = dropdownItem;
        isResolutionDropdownOpen = false;
    }
    
    /**
     * Toggles the resolution dropdown menu.
     */
    private void toggleResolutionDropdown() {
        isResolutionDropdownOpen = !isResolutionDropdownOpen;
        selectedResolutionIndex = settings.getCurrentResolutionIndex();
    }
    
    /**
     * Starts volume dragging and sets initial volume based on click position.
     */
    private void startVolumeDragging(float mouseX, float centerX) {
        isDraggingVolume = true;
        updateVolumeFromMousePosition(mouseX, centerX);
    }
    
    /**
     * Starts crosshair size dragging and sets initial size based on click position.
     */
    private void startCrosshairSizeDragging(float mouseX, float centerX) {
        isDraggingCrosshairSize = true;
        updateCrosshairSizeFromMousePosition(mouseX, centerX);
    }
    
    /**
     * Updates crosshair size setting based on mouse position along the slider.
     */
    private void updateCrosshairSizeFromMousePosition(float mouseX, float centerX) {
        float sliderX = centerX - SLIDER_WIDTH/2;
        float relativeX = mouseX - sliderX;
        float normalizedValue = Math.max(0.0f, Math.min(1.0f, relativeX / SLIDER_WIDTH));
        float newSize = MIN_CROSSHAIR_SIZE + (normalizedValue * (MAX_CROSSHAIR_SIZE - MIN_CROSSHAIR_SIZE));
        settings.setCrosshairSize(newSize);
    }
    
    // ===== MOUSE UTILITY METHODS =====
    
    /**
     * Checks if the mouse cursor is over a rectangular button area.
     */
    private boolean isMouseOverButton(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    /**
     * Checks if the mouse cursor is over a volume/slider area with increased height.
     */
    private boolean isMouseOverVolumeArea(float mouseX, float mouseY, float centerX, float centerY) {
        float buttonX = centerX - BUTTON_WIDTH / 2;
        float buttonY = centerY - VOLUME_BUTTON_HEIGHT / 2;
        
        return mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && 
               mouseY >= buttonY && mouseY <= buttonY + VOLUME_BUTTON_HEIGHT;
    }
    
    /**
     * Determines which resolution dropdown menu item the mouse is currently over.
     * @return dropdown item index, or -1 if none
     */
    private int getResolutionDropdownItemUnderMouse(float mouseX, float mouseY, float centerX, float centerY) {
        if (!isResolutionDropdownOpen) {
            return -1;
        }
        
        float dropdownX = centerX - BUTTON_WIDTH/2;
        float dropdownY = centerY - 140 + BUTTON_HEIGHT; // Below resolution button
        int[][] resolutions = Settings.getAvailableResolutions();
        
        for (int i = 0; i < resolutions.length; i++) {
            float itemY = dropdownY + i * DROPDOWN_ITEM_HEIGHT;
            if (isMouseOverButton(mouseX, mouseY, dropdownX, itemY, BUTTON_WIDTH, DROPDOWN_ITEM_HEIGHT)) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Determines which crosshair style dropdown menu item the mouse is currently over.
     * @return dropdown item index, or -1 if none
     */
    private int getCrosshairStyleDropdownItemUnderMouse(float mouseX, float mouseY, float centerX, float centerY) {
        if (!isCrosshairStyleDropdownOpen) {
            return -1;
        }
        
        float dropdownX = centerX - BUTTON_WIDTH/2;
        float dropdownY = centerY - 20 + BUTTON_HEIGHT; // Below crosshair style button
        
        for (int i = 0; i < CROSSHAIR_STYLE_NAMES.length; i++) {
            float itemY = dropdownY + i * DROPDOWN_ITEM_HEIGHT;
            if (isMouseOverButton(mouseX, mouseY, dropdownX, itemY, BUTTON_WIDTH, DROPDOWN_ITEM_HEIGHT)) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Selects a crosshair style from the dropdown menu.
     */
    private void selectCrosshairStyleFromDropdown(int dropdownItem) {
        settings.setCrosshairStyle(CROSSHAIR_STYLES[dropdownItem]);
        selectedCrosshairStyleIndex = dropdownItem;
        isCrosshairStyleDropdownOpen = false;
    }
    
    // ===== ACTION EXECUTION =====
    
    /**
     * Executes the action associated with the currently selected button.
     */
    private void executeSelectedAction() {
        ButtonSelection button = ButtonSelection.fromIndex(selectedButton);
        if (button == null) return;
        
        switch (button) {
            case RESOLUTION -> toggleResolutionDropdown();
            case VOLUME -> {} // Volume handled by mouse/keyboard interaction
            case CROSSHAIR_STYLE -> toggleCrosshairStyleDropdown();
            case CROSSHAIR_SIZE -> {} // Crosshair size handled by mouse/keyboard interaction
            case APPLY -> applySettings();
            case BACK -> goBack();
        }
    }
    
    /**
     * Toggles the crosshair style dropdown menu.
     */
    private void toggleCrosshairStyleDropdown() {
        isCrosshairStyleDropdownOpen = !isCrosshairStyleDropdownOpen;
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
     * Renders the complete settings menu UI.
     */
    public void render(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        renderMenuBackground(windowWidth, windowHeight);
        renderSettingSections(centerX, centerY);
        renderActionButtons(centerX, centerY);
        renderDropdownMenus(centerX, centerY);
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
        renderDisplaySettings(centerX, centerY);
        renderAudioSettings(centerX, centerY);
        renderCrosshairSettings(centerX, centerY);
    }
    
    /**
     * Renders display-related settings section.
     */
    private void renderDisplaySettings(float centerX, float centerY) {
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        boolean isResolutionSelected = (selectedButton == ButtonSelection.RESOLUTION.getIndex());
        
        uiRenderer.drawDropdownButton(resolutionText, centerX - BUTTON_WIDTH/2, centerY - 140, 
            BUTTON_WIDTH, BUTTON_HEIGHT, isResolutionSelected, isResolutionDropdownOpen);
        
        uiRenderer.drawSeparator(centerX, centerY - 110, BUTTON_WIDTH * 0.8f);
    }
    
    /**
     * Renders audio-related settings section.
     */
    private void renderAudioSettings(float centerX, float centerY) {
        boolean isVolumeSelected = (selectedButton == ButtonSelection.VOLUME.getIndex());
        
        uiRenderer.drawVolumeSlider("Master Volume", centerX, centerY - 80, 
            SLIDER_WIDTH, SLIDER_HEIGHT, settings.getMasterVolume(), isVolumeSelected);
        
        uiRenderer.drawSeparator(centerX, centerY - 50, BUTTON_WIDTH * 0.8f);
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
        String currentStyleName = CROSSHAIR_STYLE_NAMES[selectedCrosshairStyleIndex];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        boolean isCrosshairStyleSelected = (selectedButton == ButtonSelection.CROSSHAIR_STYLE.getIndex());
        
        uiRenderer.drawDropdownButton(crosshairStyleText, centerX - BUTTON_WIDTH/2, centerY - 20, 
            BUTTON_WIDTH, BUTTON_HEIGHT, isCrosshairStyleSelected, isCrosshairStyleDropdownOpen);
    }
    
    /**
     * Renders crosshair size slider setting.
     */
    private void renderCrosshairSizeSetting(float centerX, float centerY) {
        boolean isCrosshairSizeSelected = (selectedButton == ButtonSelection.CROSSHAIR_SIZE.getIndex());
        float normalizedSize = (settings.getCrosshairSize() - MIN_CROSSHAIR_SIZE) / (MAX_CROSSHAIR_SIZE - MIN_CROSSHAIR_SIZE);
        
        uiRenderer.drawVolumeSlider("Crosshair Size", centerX, centerY + 40, 
            SLIDER_WIDTH, SLIDER_HEIGHT, normalizedSize, isCrosshairSizeSelected);
    }
    
    /**
     * Renders action buttons (Apply, Back).
     */
    private void renderActionButtons(float centerX, float centerY) {
        boolean isApplySelected = (selectedButton == ButtonSelection.APPLY.getIndex());
        boolean isBackSelected = (selectedButton == ButtonSelection.BACK.getIndex());
        
        uiRenderer.drawButton("Apply Settings", centerX - BUTTON_WIDTH/2, centerY + 100, 
            BUTTON_WIDTH, BUTTON_HEIGHT, isApplySelected);
        
        uiRenderer.drawButton("Back", centerX - BUTTON_WIDTH/2, centerY + 160, 
            BUTTON_WIDTH, BUTTON_HEIGHT, isBackSelected);
    }
    
    /**
     * Renders dropdown menus on top of all other elements.
     */
    private void renderDropdownMenus(float centerX, float centerY) {
        if (isResolutionDropdownOpen) {
            renderResolutionDropdown(centerX, centerY);
        }
        
        if (isCrosshairStyleDropdownOpen) {
            renderCrosshairStyleDropdown(centerX, centerY);
        }
    }
    
    /**
     * Renders the resolution dropdown menu.
     */
    private void renderResolutionDropdown(float centerX, float centerY) {
        int[][] resolutions = Settings.getAvailableResolutions();
        String[] resolutionStrings = createResolutionStrings(resolutions);
        
        uiRenderer.drawDropdownMenu(resolutionStrings, selectedResolutionIndex, 
            centerX - BUTTON_WIDTH/2, centerY - 140 + BUTTON_HEIGHT, BUTTON_WIDTH, DROPDOWN_ITEM_HEIGHT);
    }
    
    /**
     * Renders the crosshair style dropdown menu.
     */
    private void renderCrosshairStyleDropdown(float centerX, float centerY) {
        uiRenderer.drawDropdownMenu(CROSSHAIR_STYLE_NAMES, selectedCrosshairStyleIndex, 
            centerX - BUTTON_WIDTH/2, centerY - 20 + BUTTON_HEIGHT, BUTTON_WIDTH, DROPDOWN_ITEM_HEIGHT);
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
    
}