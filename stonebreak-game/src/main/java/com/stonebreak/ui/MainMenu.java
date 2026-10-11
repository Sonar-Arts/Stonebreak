package com.stonebreak.ui;

import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.mainMenu.MainMenuStage;
import com.stonebreak.ui.mainMenu.SplashTextManager;
import io.github.humbleui.types.Rect;

/**
 * The main menu, shown as the shipped UI document {@value #DOCUMENT_ID} (a state screen,
 * {@code runtime.screens.StateScreens}, #299): the stage, the splash and every action stay here; the
 * UI host publishes the stage's motion and runs the actions through {@link #choose} and
 * {@link #clickTitle}.
 */
public class MainMenu {

    /** The shipped document's screen id ({@code ui/documents/main_menu.sbui}). */
    public static final String DOCUMENT_ID = "main_menu";

    // Logo intrinsic viewBox is 1641 x 419 (aspect ~3.917).
    private static final float BASE_LOGO_HEIGHT = 140f;
    private static final float LOGO_ASPECT = 1641f / 419f;

    private final MainMenuStage stage = new MainMenuStage();
    private final SplashTextManager splashTextManager;
    private String currentSplashText;

    public MainMenu() {
        this.splashTextManager = SplashTextManager.getInstance();
        this.currentSplashText = splashTextManager.getRandomSplashText();
    }
    
    /** Runs button {@code index} (0 Singleplayer, 1 Multiplayer, 2 Settings, 3 Quit) as a click does. */
    public void choose(int index) {
        switch (index) {
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
    
    /** One frame of the title animation (the document's presentation calls this before each paint). */
    public void advance(int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        stage.update(Game.getDeltaTime(), windowWidth, windowHeight, scale);
    }

    /** Pins the splash line (fixtures; the game picks one at random on entering the menu). */
    public void setSplashText(String text) {
        this.currentSplashText = text;
    }

    /** A click on the title (the easter egg), as at its centre in a window of this size. */
    public void clickTitle(int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        Rect logo = computeLogoRect(windowWidth, windowHeight, s);
        stage.onTitleClick(logo.getLeft() + logo.getWidth() / 2f, logo.getTop() + logo.getHeight() / 2f);
    }

    /** Screen-space rectangle the title logo occupies (device px): where title clicks land and the splash pivots. */
    private static Rect computeLogoRect(int windowWidth, int windowHeight, float scale) {
        float centerX = windowWidth / 2f;
        float centerY = windowHeight / 2f;
        float logoHeight = BASE_LOGO_HEIGHT * scale;
        float logoWidth = logoHeight * LOGO_ASPECT;
        float logoX = centerX - logoWidth / 2f;
        float logoY = centerY - 120f * scale - logoHeight / 2f;
        return Rect.makeXYWH(logoX, logoY, logoWidth, logoHeight);
    }

    /** Where the splash line pivots: 10 px in from the logo's right edge, 95 % of the way down it. */
    public static float[] splashAnchor(int windowWidth, int windowHeight, float scale) {
        Rect logoRect = computeLogoRect(windowWidth, windowHeight, scale);
        return new float[]{logoRect.getRight() - 10f * scale, logoRect.getTop() + logoRect.getHeight() * 0.95f};
    }

    /** The splash line's beat: a ±5 % scale pulse every half second of {@code millis}. */
    public static float splashPulse(long millis) {
        float t = (millis % 500L) / 500.0f;
        return 1.0f + (float) (Math.sin(t * Math.PI * 2.0) * 0.05);
    }

    public MainMenuStage getStage() {
        return stage;
    }

    /** Returns the title interaction to its dirt-background idle state. */
    public void resetTitleAnimation() {
        stage.reset();
    }

    public String getCurrentSplashText() {
        return currentSplashText;
    }

    public void refreshSplashText() {
        this.currentSplashText = splashTextManager.getRandomSplashText();
    }
}