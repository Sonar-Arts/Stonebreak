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
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.mainMenu.MainMenuStage;
import com.stonebreak.ui.mainMenu.SkijaMainMenuRenderer;
import com.stonebreak.ui.mainMenu.SplashTextManager;
import io.github.humbleui.types.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainMenu {
    private static final Logger logger = LoggerFactory.getLogger(MainMenu.class);

    private final SkijaMainMenuRenderer skijaRenderer;
    private final MainMenuStage stage = new MainMenuStage();
    // -1 = no selection, 0 = Singleplayer, 1 = Multiplayer, 2 = Settings, 3 = Quit Game
    private int selectedButton = -1;
    private static final int BUTTON_COUNT = 4;
    private final SplashTextManager splashTextManager;
    private String currentSplashText;

    public MainMenu(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaMainMenuRenderer(skijaBackend);
        this.splashTextManager = SplashTextManager.getInstance();
        this.currentSplashText = splashTextManager.getRandomSplashText();
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
                selectedButton = Math.min(BUTTON_COUNT - 1, selectedButton + 1);
            }
        }
        
        // Handle enter key - only if a button is selected
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS && selectedButton >= 0) {
            executeSelectedAction();
        }
    }
    
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = 400f * s;
        float bh = 40f  * s;
        float sp = 50f  * s;
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s, bw, bh)) {
            selectedButton = 0;
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp, bw, bh)) {
            selectedButton = 1;
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp * 2f, bw, bh)) {
            selectedButton = 2;
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp * 3f, bw, bh)) {
            selectedButton = 3;
        } else {
            selectedButton = -1;
        }
    }

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = 400f * s;
        float bh = 40f  * s;
        float sp = 50f  * s;
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        Rect logo = SkijaMainMenuRenderer.computeLogoRect(windowWidth, windowHeight, s);
        if (isMouseOverButton((float)mouseX, (float)mouseY, logo.getLeft(), logo.getTop(),
                logo.getWidth(), logo.getHeight())) {
            stage.onTitleClick(logo.getLeft() + logo.getWidth() / 2f,
                    logo.getTop() + logo.getHeight() / 2f);
            return;
        }

        if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s, bw, bh)) {
            selectedButton = 0; executeSelectedAction();
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp, bw, bh)) {
            selectedButton = 1; executeSelectedAction();
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp * 2f, bw, bh)) {
            selectedButton = 2; executeSelectedAction();
        } else if (isMouseOverButton((float)mouseX, (float)mouseY, centerX - bw / 2f, centerY - 20f * s + sp * 3f, bw, bh)) {
            selectedButton = 3; executeSelectedAction();
        }
    }
    
    private boolean isMouseOverButton(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    private void executeSelectedAction() {
        switch (selectedButton) {
            case 0 -> // Play - Go to world select screen
                Game.getInstance().setState(GameState.WORLD_SELECT);
            case 1 -> // Multiplayer
                Game.getInstance().setState(GameState.MULTIPLAYER_MENU);
            case 2 -> { // Settings
                SettingsMenu settingsMenu = Game.getInstance().getSettingsMenu();
                if (settingsMenu != null) {
                    settingsMenu.setPreviousState(GameState.MAIN_MENU);
                }
                Game.getInstance().setState(GameState.SETTINGS);
            }
            case 3 -> // Exit
                System.exit(0);
        }
    }
    
    public void render(int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        stage.update(Game.getDeltaTime(), windowWidth, windowHeight, scale);
        skijaRenderer.render(this, windowWidth, windowHeight);
    }

    public MainMenuStage getStage() {
        return stage;
    }

    /** Returns the title interaction to its dirt-background idle state. */
    public void resetTitleAnimation() {
        stage.reset();
    }

    public void dispose() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }
    
    public int getSelectedButton() {
        return selectedButton;
    }

    public String getCurrentSplashText() {
        return currentSplashText;
    }

    public void refreshSplashText() {
        this.currentSplashText = splashTextManager.getRandomSplashText();
    }
}