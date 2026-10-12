package com.stonebreak.ui.runtime.screens;

import com.stonebreak.core.GameState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screens that are a game state (#299): the presentation of the state the game enters is shown and
 * the one it leaves hidden; only the current state's presentation paints.
 */
class StateScreensTest {

    private static final class Recorder implements ScreenPresentation {
        final String name;
        final List<String> log;

        Recorder(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override public void shown() { log.add(name + " shown"); }
        @Override public void hidden() { log.add(name + " hidden"); }
        @Override public boolean paint(int w, int h) {
            log.add(name + " paint");
            return true;
        }
    }

    @Test
    void statesShowAndHideTheirPresentations() {
        StateScreens screens = StateScreens.create();
        List<String> log = new ArrayList<>();
        screens.register(GameState.MULTIPLAYER_MENU, new Recorder("menu", log));
        screens.register(GameState.JOIN_WORLD_SCREEN, new Recorder("join", log));

        screens.stateChanged(GameState.MAIN_MENU);
        assertFalse(screens.paint(GameState.MAIN_MENU, 10, 10), "no presentation: the legacy screen draws");
        screens.stateChanged(GameState.MULTIPLAYER_MENU);
        screens.stateChanged(GameState.MULTIPLAYER_MENU);
        assertTrue(screens.paint(GameState.MULTIPLAYER_MENU, 10, 10));
        assertFalse(screens.paint(GameState.JOIN_WORLD_SCREEN, 10, 10), "not the current state");
        screens.stateChanged(GameState.JOIN_WORLD_SCREEN);
        assertEquals(List.of("menu shown", "menu paint", "menu hidden", "join shown"), log);
    }

    @Test
    void registeringForTheCurrentStateShowsAtOnce() {
        StateScreens screens = StateScreens.create();
        List<String> log = new ArrayList<>();
        screens.stateChanged(GameState.MULTIPLAYER_MENU);
        screens.register(GameState.MULTIPLAYER_MENU, new Recorder("a", log));
        screens.register(GameState.MULTIPLAYER_MENU, new Recorder("b", log));
        screens.register(GameState.MULTIPLAYER_MENU, null);
        assertEquals(List.of("a shown", "a hidden", "b shown", "b hidden"), log);
    }
}
