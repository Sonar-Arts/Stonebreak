package com.stonebreak.ui.runtime.screens;

import com.stonebreak.core.GameState;

import java.util.EnumMap;
import java.util.Map;

/**
 * Presentations of the screens that ARE a game state (main menu, multiplayer, world select, ...):
 * the state controller tells this registry when the state changes ({@link #stateChanged}), so the
 * screen for the new state is shown and the old one hidden, and the frame renderer asks it to
 * {@link #paint} before drawing the legacy screen of that state (#299). A state without a
 * presentation, or one whose document is not showing, keeps its legacy screen. Main thread only.
 */
public final class StateScreens {

    private static final StateScreens INSTANCE = new StateScreens();

    private StateScreens() {
    }

    private final Map<GameState, ScreenPresentation> byState = new EnumMap<>(GameState.class);
    private GameState current;

    public static StateScreens get() {
        return INSTANCE;
    }

    /** A registry of its own (tests); the game uses {@link #get()}. */
    static StateScreens create() {
        return new StateScreens();
    }

    /** Registers (or, with null, removes) the presentation of {@code state}. */
    public void register(GameState state, ScreenPresentation presentation) {
        ScreenPresentation old = presentation == null ? byState.remove(state) : byState.put(state, presentation);
        if (state == current) {
            if (old != null) {
                old.hidden();
            }
            if (presentation != null) {
                presentation.shown();
            }
        }
    }

    /** The game entered {@code state}: the previous state's screen hides, this one shows. */
    public void stateChanged(GameState state) {
        if (state == current) {
            return;
        }
        ScreenPresentation old = current == null ? null : byState.get(current);
        current = state;
        if (old != null) {
            old.hidden();
        }
        ScreenPresentation now = state == null ? null : byState.get(state);
        if (now != null) {
            now.shown();
        }
    }

    /** @return true when {@code state}'s presentation painted (the legacy screen must not) */
    public boolean paint(GameState state, int width, int height) {
        ScreenPresentation p = state == current ? byState.get(state) : null;
        return p != null && p.paint(width, height);
    }

    /** True while {@code state}'s presentation, not its legacy screen, is showing. */
    public boolean showing(GameState state) {
        ScreenPresentation p = state == current ? byState.get(state) : null;
        return p != null && p.showing();
    }
}
