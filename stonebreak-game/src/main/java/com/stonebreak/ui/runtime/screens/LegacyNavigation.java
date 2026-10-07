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
 * {@code ui.navigate} targets that are still legacy screens or game-state changes. A target that
 * has a shipped (and not rolled back) document opens that document instead; these cover
 * everything else, so a migrated screen can lead to one that has not migrated yet. Each runs the
 * same rules the legacy buttons do ({@link PauseMenuActions}).
 *
 * <ul>
 *   <li>{@code resume} - close the pause menu and return to the game</li>
 *   <li>{@code pause} - open the pause menu while playing</li>
 *   <li>{@code settings}, {@code statistics}, {@code glossary} - the pause-menu destinations</li>
 *   <li>{@code main_menu} - quit to the main menu (cleans up a running world)</li>
 *   <li>{@code world_select}, {@code multiplayer} - main-menu destinations</li>
 * </ul>
 */
public final class LegacyNavigation {

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

    private LegacyNavigation() {
    }

    public static Set<String> targets() {
        return TARGETS.keySet();
    }

    public static boolean has(String target) {
        return TARGETS.containsKey(target);
    }

    /** Runs {@code target}; false when it is not a legacy target. Main thread, outside any dispatch. */
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
