package com.stonebreak.ui;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.settingsMenu.SettingsMenu;

/**
 * What each pause-menu button does, in one place: the legacy click handler
 * ({@code UiMouseRouter}) and the UI host's {@code stonebreak:screen.pause.*} and
 * {@code stonebreak:network.resync} actions (#289) both call these, so a migrated pause screen
 * runs exactly the rules the old one did.
 */
public final class PauseMenuActions {

    private PauseMenuActions() {
    }

    public static void resume(Game game) {
        game.togglePauseMenu();
    }

    public static void openStatistics(Game game) {
        game.openStatisticsScreen();
    }

    public static void openGlossary(Game game) {
        game.openGlossaryScreen();
    }

    /** Settings, remembering that we came from the game. */
    public static void openSettings(Game game) {
        SettingsMenu settingsMenu = game.getSettingsMenu();
        if (settingsMenu != null) {
            settingsMenu.setPreviousState(GameState.PLAYING);
        }
        game.setState(GameState.SETTINGS);
        game.getPauseMenu().setVisible(false);
    }

    /**
     * Asks the server to re-stream the world, reports it in chat, and resumes so the re-stream is
     * visible.
     *
     * @return chunks audited, or -1 when not connected to a server
     */
    public static int resync(Game game) {
        int audited = MultiplayerSession.requestFullResync();
        ChatSystem chat = game.getChatSystem();
        if (chat != null) {
            chat.addMessage(audited >= 0
                ? "Resyncing with server (" + audited + " chunks audited)..."
                : "Resync failed: not connected to a server.");
        }
        game.togglePauseMenu();
        return audited;
    }

    /** Cleans up world state and returns to the main menu. */
    public static void quitToMenu(Game game) {
        game.resetWorld();
        game.setState(GameState.MAIN_MENU);
        game.getPauseMenu().setVisible(false);
    }
}
