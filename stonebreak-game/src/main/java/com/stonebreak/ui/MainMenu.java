package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

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

/**
 * The main menu. Since #299 it may be shown as the shipped UI document {@value #DOCUMENT_ID} (a
 * state screen, {@code runtime.screens.StateScreens}): the stage, the splash and every action stay
 * here; the UI host publishes the stage's motion and runs the actions through {@link #choose} and
 * {@link #clickTitle}.
 */
public class MainMenu {
    private static final Logger logger = LoggerFactory.getLogger(MainMenu.class);

    /** The shipped document's screen id ({@code ui/documents/main_menu.sbui}). */
    public static final String DOCUMENT_ID = "main_menu";

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
        if (com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_UP) || com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_W)) {
            if (selectedButton == -1) {
                selectedButton = 0; // Start with first button
            } else {
                selectedButton = Math.max(0, selectedButton - 1);
            }
        }
        if (com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_DOWN) || com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_S)) {
            if (selectedButton == -1) {
                selectedButton = 0; // Start with first button
            } else {
                selectedButton = Math.min(BUTTON_COUNT - 1, selectedButton + 1);
            }
        }
        
        // Handle enter key - only if a button is selected
        if (com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_ENTER) && selectedButton >= 0) {
            executeSelectedAction();
        }
    }
    
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        selectedButton = buttonIndexAt((float) mouseX, (float) mouseY, windowWidth, windowHeight);
    }

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();

        Rect logo = SkijaMainMenuRenderer.computeLogoRect(windowWidth, windowHeight, s);
        if (isMouseOverButton((float)mouseX, (float)mouseY, logo.getLeft(), logo.getTop(),
                logo.getWidth(), logo.getHeight())) {
            stage.onTitleClick(logo.getLeft() + logo.getWidth() / 2f,
                    logo.getTop() + logo.getHeight() / 2f);
            return;
        }

        int index = buttonIndexAt((float) mouseX, (float) mouseY, windowWidth, windowHeight);
        if (index >= 0) {
            selectedButton = index;
            executeSelectedAction();
        }
    }

    /**
     * Index of the menu button under the cursor, or -1 if none. Single source of the button
     * column's geometry — hover and click must never derive it independently.
     */
    static int buttonIndexAt(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = 400f * s;
        float bh = 40f  * s;
        float sp = 50f  * s;
        float x = windowWidth / 2.0f - bw / 2f;
        float top = windowHeight / 2.0f - 20f * s;
        for (int i = 0; i < BUTTON_COUNT; i++) {
            float y = top + sp * i;
            if (mouseX >= x && mouseX <= x + bw && mouseY >= y && mouseY <= y + bh) {
                return i;
            }
        }
        return -1;
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
        advance(windowWidth, windowHeight);
        skijaRenderer.render(this, windowWidth, windowHeight);
    }

    /** One frame of the title animation (the document's presentation calls this where render did). */
    public void advance(int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        stage.update(Game.getDeltaTime(), windowWidth, windowHeight, scale);
    }

    /** The legacy renderer (fixtures read its layout sink and pin its clock). */
    public SkijaMainMenuRenderer renderer() {
        return skijaRenderer;
    }

    /** Pins the splash line (fixtures; the game picks one at random on entering the menu). */
    public void setSplashText(String text) {
        this.currentSplashText = text;
    }

    /** Runs button {@code index} (0 Singleplayer, 1 Multiplayer, 2 Settings, 3 Quit) as a click does. */
    public void choose(int index) {
        selectedButton = index;
        executeSelectedAction();
    }

    /** A click on the title (the easter egg), as at its centre in a window of this size. */
    public void clickTitle(int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        Rect logo = SkijaMainMenuRenderer.computeLogoRect(windowWidth, windowHeight, s);
        stage.onTitleClick(logo.getLeft() + logo.getWidth() / 2f, logo.getTop() + logo.getHeight() / 2f);
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