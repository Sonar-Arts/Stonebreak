package com.stonebreak.ui;

import com.stonebreak.core.GameState;
import com.stonebreak.core.Game;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.mainMenu.SplashTextManager;

/**
 * The main menu, shown as the shipped UI document {@value #DOCUMENT_ID} (a state screen,
 * {@code runtime.screens.StateScreens}, #299) over the live world backdrop
 * ({@code ui.mainMenu.showcase.MenuShowcase}). The splash line and every action stay here; the UI
 * host runs the actions through {@link #choose}.
 */
public class MainMenu {

    /** The shipped document's screen id ({@code ui/documents/main_menu.sbui}). */
    public static final String DOCUMENT_ID = "main_menu";

    private final SplashTextManager splashTextManager;
    private String currentSplashText;

    public MainMenu() {
        this.splashTextManager = SplashTextManager.getInstance();
        this.currentSplashText = splashTextManager.getRandomSplashText();
    }

    /**
     * Runs entry {@code index} (0 Singleplayer, 1 Multiplayer, 2 Settings, 3 Quit, 4 Resume) as a
     * click does. Ignored while Resume is sliding the menu off.
     */
    public void choose(int index) {
        com.stonebreak.ui.mainMenu.showcase.MenuShowcase showcase =
                com.stonebreak.ui.mainMenu.showcase.MenuShowcase.get();
        if (showcase.isResuming()) {
            return;
        }
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
            case 4 -> // Resume the last-played world
                showcase.resume();
        }
    }

    /** Pins the splash line (fixtures; the game picks one at random on entering the menu). */
    public void setSplashText(String text) {
        this.currentSplashText = text;
    }

    public String getCurrentSplashText() {
        return currentSplashText;
    }

    public void refreshSplashText() {
        this.currentSplashText = splashTextManager.getRandomSplashText();
    }
}
