package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.PauseMenuActions;
import com.stonebreak.ui.settingsMenu.SettingsMenu;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * {@code ui.navigate} targets that are game-state changes rather than documents. A target that has
 * a shipped document opens that document instead; these cover the rest (the screens that follow a
 * game state, the pause menu's lifecycle). Each runs the same rules as the matching buttons
 * ({@link PauseMenuActions}).
 *
 * <ul>
 *   <li>{@code resume} - close the pause menu and return to the game</li>
 *   <li>{@code pause} - open the pause menu while playing</li>
 *   <li>{@code settings}, {@code statistics}, {@code glossary} - the pause-menu destinations</li>
 *   <li>{@code main_menu} - quit to the main menu (cleans up a running world)</li>
 *   <li>{@code world_select}, {@code multiplayer} - main-menu destinations</li>
 * </ul>
 */
public final class StateNavigation {

    private static final Map<String, BiConsumer<Game, UiValue.Obj>> TARGETS = Map.of(
        "resume", (g, a) -> {
            PauseMenu pause = g.getPauseMenu();
            if (pause != null && pause.isVisible()) {
                PauseMenuActions.resume(g);
            }
        },
        "pause", (g, a) -> {
            PauseMenu pause = g.getPauseMenu();
            if (g.getState() == GameState.PLAYING && (pause == null || !pause.isVisible())) {
                g.togglePauseMenu();
            }
        },
        "settings", (g, a) -> openSettings(g),
        "statistics", (g, a) -> PauseMenuActions.openStatistics(g),
        "glossary", (g, a) -> PauseMenuActions.openGlossary(g),
        "main_menu", (g, a) -> {
            if (Game.getWorld() != null) {
                PauseMenuActions.quitToMenu(g);
            } else {
                g.setState(GameState.MAIN_MENU);
            }
        },
        "world_select", (g, a) -> g.setState(GameState.WORLD_SELECT),
        "multiplayer", (g, a) -> g.setState(GameState.MULTIPLAYER_MENU));

    private StateNavigation() {
    }

    public static Set<String> targets() {
        return TARGETS.keySet();
    }

    public static boolean has(String target) {
        return TARGETS.containsKey(target);
    }

    /** Runs {@code target}; false when it is not one of these targets. Main thread, outside any dispatch. */
    public static boolean go(String target, UiValue.Obj args) {
        BiConsumer<Game, UiValue.Obj> t = TARGETS.get(target);
        Game game = Game.getInstance();
        if (t == null || game == null) {
            return false;
        }
        t.accept(game, args);
        return true;
    }

    private static void openSettings(Game game) {
        if (Game.getWorld() != null) {
            PauseMenuActions.openSettings(game);
            return;
        }
        SettingsMenu settings = game.getSettingsMenu();
        if (settings != null) {
            settings.setPreviousState(game.getState());
        }
        game.setState(GameState.SETTINGS);
    }
}
