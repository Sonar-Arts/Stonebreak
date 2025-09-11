package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.SettingsMenu;

public class MainMenu {
    private final UIRenderer uiRenderer;
    private int selectedButton = -1; // -1 = no selection, 0 = Singleplayer, 1 = Settings, 2 = Quit Game
    
    public MainMenu(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }
    
    public void handleInput(long window) {
        // Handle keyboard navigation - only if selectedButton is not -1 (mouse not hovering)
        if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            if (selectedButton == -1) {
                selectedButton = 0; // Start with first button
            } else {
                selectedButton = Math.max(0, selectedButton - 1);
            }
        }
        if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            if (selectedButton == -1) {
                selectedButton = 0; // Start with first button
            } else {
                selectedButton = Math.min(2, selectedButton + 1);
            }
        }
        
        // Handle enter key - only if a button is selected
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS && selectedButton >= 0) {
            executeSelectedAction();
        }
    }
    
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Check which button mouse is over (updated for new layout)
        if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY - 20, 400, 40)) {
            selectedButton = 0; // Singleplayer
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY + 30, 400, 40)) {
            selectedButton = 1; // Settings
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY + 80, 400, 40)) {
            selectedButton = 2; // Quit Game
        } else {
            selectedButton = -1; // No button hovered
        }
        
    }
    
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Only execute action if mouse is actually over a button (updated for new layout)
        if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY - 20, 400, 40)) {
            selectedButton = 0; // Singleplayer
            executeSelectedAction();
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY + 30, 400, 40)) {
            selectedButton = 1; // Settings
            executeSelectedAction();
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - 200, centerY + 80, 400, 40)) {
            selectedButton = 2; // Quit Game
            executeSelectedAction();
        }
        // If mouse is not over any button, do nothing
    }
    
    private boolean isMouseOverButton(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    private void executeSelectedAction() {
        switch (selectedButton) {
            case 0 -> // Play - Start world generation with loading screen
                Game.getInstance().startWorldGeneration();
            case 1 -> { // Settings
                SettingsMenu settingsMenu = Game.getInstance().getSettingsMenu();
                if (settingsMenu != null) {
                    settingsMenu.setPreviousState(GameState.MAIN_MENU);
                }
                Game.getInstance().setState(GameState.SETTINGS);
            }
            case 2 -> // Exit
                System.exit(0);
        }
    }
    
    public void render(int windowWidth, int windowHeight) {
        uiRenderer.renderMainMenu(windowWidth, windowHeight);
    }
    
    public int getSelectedButton() {
        return selectedButton;
    }
}