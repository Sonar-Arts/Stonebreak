package com.stonebreak;

import static org.lwjgl.glfw.GLFW.*;

public class SettingsMenu {
    private final UIRenderer uiRenderer;
    private final Settings settings;
    private int selectedButton = 0; // 0 = Resolution, 1 = Volume, 2 = Apply, 3 = Back
    private boolean isDraggingVolume = false;
    private boolean isResolutionDropdownOpen = false;
    private int selectedResolutionIndex = 0;
    private GameState previousState = GameState.MAIN_MENU; // Remember where we came from
    private boolean escapeKeyPressed = false; // For edge detection
    
    // UI element positions and sizes
    private static final float BUTTON_WIDTH = 400;
    private static final float BUTTON_HEIGHT = 40;
    private static final float SLIDER_WIDTH = 300;
    private static final float SLIDER_HEIGHT = 20;
    
    public SettingsMenu(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
        this.settings = Settings.getInstance();
        this.selectedResolutionIndex = settings.getCurrentResolutionIndex();
    }
    
    public void handleInput(long window) {
        // Handle keyboard navigation
        if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            selectedButton = Math.max(0, selectedButton - 1);
        }
        if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            selectedButton = Math.min(3, selectedButton + 1);
        }
        
        // Handle left/right arrows for changing values
        if (glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            if (selectedButton == 0 && isResolutionDropdownOpen) { // Resolution dropdown navigation
                selectedResolutionIndex = Math.max(0, selectedResolutionIndex - 1);
            } else if (selectedButton == 0) { // Resolution
                int currentIndex = settings.getCurrentResolutionIndex();
                int newIndex = Math.max(0, currentIndex - 1);
                settings.setResolutionByIndex(newIndex);
                selectedResolutionIndex = newIndex;
            } else if (selectedButton == 1) { // Volume
                float newVolume = Math.max(0.0f, settings.getMasterVolume() - 0.1f);
                settings.setMasterVolume(newVolume);
            }
        }
        if (glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            if (selectedButton == 0 && isResolutionDropdownOpen) { // Resolution dropdown navigation
                int[][] resolutions = Settings.getAvailableResolutions();
                selectedResolutionIndex = Math.min(resolutions.length - 1, selectedResolutionIndex + 1);
            } else if (selectedButton == 0) { // Resolution
                int currentIndex = settings.getCurrentResolutionIndex();
                int[][] resolutions = Settings.getAvailableResolutions();
                int newIndex = Math.min(resolutions.length - 1, currentIndex + 1);
                settings.setResolutionByIndex(newIndex);
                selectedResolutionIndex = newIndex;
            } else if (selectedButton == 1) { // Volume
                float newVolume = Math.min(1.0f, settings.getMasterVolume() + 0.1f);
                settings.setMasterVolume(newVolume);
            }
        }
        
        // Handle enter key
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
            if (selectedButton == 0 && isResolutionDropdownOpen) {
                // Select resolution from dropdown
                settings.setResolutionByIndex(selectedResolutionIndex);
                isResolutionDropdownOpen = false;
            } else {
                executeSelectedAction();
            }
        }
        
        // Handle escape key to go back (with edge detection)
        boolean isEscapePressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        if (isEscapePressed && !escapeKeyPressed) {
            escapeKeyPressed = true;
            goBack();
        } else if (!isEscapePressed) {
            escapeKeyPressed = false;
        }
    }
    
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Check if mouse is over dropdown menu
        if (isResolutionDropdownOpen) {
            int dropdownItem = getDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
            if (dropdownItem >= 0) {
                selectedResolutionIndex = dropdownItem;
                return; // Don't check other buttons when dropdown is open
            }
        }
        
        // Check which button mouse is over
        if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY - 100, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = 0; // Resolution
        } else if (isMouseOverVolumeArea((float)mouseX, (float)mouseY, centerX, centerY - 30)) {
            selectedButton = 1; // Volume
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY + 40, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = 2; // Apply
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY + 90, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            selectedButton = 3; // Back
        } else {
            selectedButton = -1; // No button hovered
        }
        
        // Handle volume slider dragging
        if (isDraggingVolume) {
            float sliderX = centerX - SLIDER_WIDTH/2;
            float relativeX = (float)mouseX - sliderX;
            float newVolume = Math.max(0.0f, Math.min(1.0f, relativeX / SLIDER_WIDTH));
            settings.setMasterVolume(newVolume);
        }
    }
    
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        if (action == GLFW_PRESS) {
            // Check if clicking on dropdown menu
            if (isResolutionDropdownOpen) {
                int dropdownItem = getDropdownItemUnderMouse((float)mouseX, (float)mouseY, centerX, centerY);
                if (dropdownItem >= 0) {
                    settings.setResolutionByIndex(dropdownItem);
                    selectedResolutionIndex = dropdownItem;
                    isResolutionDropdownOpen = false;
                    return;
                }
                // If clicking outside dropdown, close it
                isResolutionDropdownOpen = false;
                return;
            }
            
            // Check resolution button
            if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY - 100, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                selectedButton = 0;
                isResolutionDropdownOpen = !isResolutionDropdownOpen; // Toggle dropdown
                selectedResolutionIndex = settings.getCurrentResolutionIndex();
            }
            // Check volume slider
            else if (isMouseOverVolumeArea((float)mouseX, (float)mouseY, centerX, centerY - 30)) {
                selectedButton = 1;
                isDraggingVolume = true;
                // Set volume based on click position
                float sliderX = centerX - SLIDER_WIDTH/2;
                float relativeX = (float)mouseX - sliderX;
                float newVolume = Math.max(0.0f, Math.min(1.0f, relativeX / SLIDER_WIDTH));
                settings.setMasterVolume(newVolume);
            }
            // Check apply button
            else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY + 40, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                selectedButton = 2;
                executeSelectedAction();
            }
            // Check back button
            else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - BUTTON_WIDTH/2, centerY + 90, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                selectedButton = 3;
                executeSelectedAction();
            }
        } else if (action == GLFW_RELEASE) {
            isDraggingVolume = false;
        }
    }
    
    private boolean isMouseOverButton(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    private boolean isMouseOverVolumeArea(float mouseX, float mouseY, float centerX, float centerY) {
        // Volume button dimensions (matching UIRenderer.drawVolumeSlider button style)
        float buttonWidth = BUTTON_WIDTH; // Same as other buttons
        float buttonHeight = 60; // Taller button for volume
        float buttonX = centerX - buttonWidth / 2;
        float buttonY = centerY - buttonHeight / 2;
        
        return mouseX >= buttonX && mouseX <= buttonX + buttonWidth && 
               mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
    }
    
    private int getDropdownItemUnderMouse(float mouseX, float mouseY, float centerX, float centerY) {
        if (!isResolutionDropdownOpen) return -1;
        
        float dropdownX = centerX - BUTTON_WIDTH/2;
        float dropdownY = centerY - 100 + BUTTON_HEIGHT; // Right below the resolution button
        float itemHeight = 30;
        int[][] resolutions = Settings.getAvailableResolutions();
        
        for (int i = 0; i < resolutions.length; i++) {
            float itemY = dropdownY + i * itemHeight;
            if (mouseX >= dropdownX && mouseX <= dropdownX + BUTTON_WIDTH &&
                mouseY >= itemY && mouseY <= itemY + itemHeight) {
                return i;
            }
        }
        return -1;
    }
    
    private void executeSelectedAction() {
        switch (selectedButton) {
            case 0 -> { // Resolution - toggle dropdown
                isResolutionDropdownOpen = !isResolutionDropdownOpen;
                selectedResolutionIndex = settings.getCurrentResolutionIndex();
            }
            case 1 -> {} // Volume - handled by mouse/keyboard
            case 2 -> applySettings(); // Apply
            case 3 -> goBack(); // Back
        }
    }
    
    private void applySettings() {
        settings.saveSettings();
        
        // Apply sound volume to sound system
        SoundSystem soundSystem = SoundSystem.getInstance();
        if (soundSystem != null) {
            soundSystem.setMasterVolume(settings.getMasterVolume());
            System.out.println("Applied master volume: " + settings.getMasterVolume());
        }
        
        // Apply window resolution changes
        applyWindowResize();
        
        System.out.println("Settings applied successfully!");
        goBack();
    }
    
    private void applyWindowResize() {
        // Get the window handle from Main class
        long windowHandle = Main.getWindowHandle();
        if (windowHandle != 0) {
            int newWidth = settings.getWindowWidth();
            int newHeight = settings.getWindowHeight();
            
            // Set the new window size
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(windowHandle, newWidth, newHeight);
            
            // Update the Game's stored window dimensions
            Game.getInstance().setWindowDimensions(newWidth, newHeight);
            
            System.out.println("Applied window resolution: " + newWidth + "x" + newHeight);
        } else {
            System.err.println("Warning: Could not apply window resolution - window handle is null");
        }
    }
    
    private void goBack() {
        if (previousState == GameState.PLAYING) {
            // Check if we should return to pause menu or directly to game
            // If the user pressed escape in settings, go back to pause menu
            // If the user clicked back/apply, resume the game directly
            Game game = Game.getInstance();
            
            // Always clear mouse button states first
            InputHandler inputHandler = Main.getInputHandler();
            if (inputHandler != null) {
                inputHandler.clearMouseButtonStates();
            }
            
            // For now, always resume the game directly when going back from settings
            // This provides a cleaner user experience
            game.setState(GameState.PLAYING);
            game.getPauseMenu().setVisible(false);
            
            // Unpause the game completely
            if (game.isPaused()) {
                game.togglePauseMenu(); // This will set paused = false
            }
            
            // Restore game cursor state (hidden and captured)
            long windowHandle = Main.getWindowHandle();
            if (windowHandle != 0) {
                org.lwjgl.glfw.GLFW.glfwSetInputMode(windowHandle, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED);
                
                // Reset mouse position to prevent camera jump
                if (inputHandler != null) {
                    inputHandler.resetMousePosition();
                }
            }
        } else {
            // Return to main menu
            Game.getInstance().setState(GameState.MAIN_MENU);
        }
    }
    
    /**
     * Sets the previous state to return to when going back.
     */
    public void setPreviousState(GameState state) {
        this.previousState = state;
    }
    
    public void render(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Use UIRenderer's settings menu rendering
        uiRenderer.renderSettingsMenu(windowWidth, windowHeight);
        
        // Draw resolution setting with dropdown button (but not the dropdown menu yet)
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        uiRenderer.drawDropdownButton(resolutionText, centerX - BUTTON_WIDTH/2, centerY - 100, BUTTON_WIDTH, BUTTON_HEIGHT, selectedButton == 0, isResolutionDropdownOpen);
        
        // Draw volume setting with slider (more spacing)
        uiRenderer.drawVolumeSlider("Master Volume", centerX, centerY - 30, SLIDER_WIDTH, SLIDER_HEIGHT, settings.getMasterVolume(), selectedButton == 1);
        
        // Draw apply button
        uiRenderer.drawButton("Apply Settings", centerX - BUTTON_WIDTH/2, centerY + 40, BUTTON_WIDTH, BUTTON_HEIGHT, selectedButton == 2);
        
        // Draw back button
        uiRenderer.drawButton("Back", centerX - BUTTON_WIDTH/2, centerY + 90, BUTTON_WIDTH, BUTTON_HEIGHT, selectedButton == 3);
        
        // Draw dropdown menu LAST so it renders on top of everything else
        if (isResolutionDropdownOpen) {
            int[][] resolutions = Settings.getAvailableResolutions();
            String[] resolutionStrings = new String[resolutions.length];
            for (int i = 0; i < resolutions.length; i++) {
                resolutionStrings[i] = resolutions[i][0] + "x" + resolutions[i][1];
            }
            uiRenderer.drawDropdownMenu(resolutionStrings, selectedResolutionIndex, 
                centerX - BUTTON_WIDTH/2, centerY - 100 + BUTTON_HEIGHT, BUTTON_WIDTH, 30);
        }
    }
    
    
    public int getSelectedButton() {
        return selectedButton;
    }
}