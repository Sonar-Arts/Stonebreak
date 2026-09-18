package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;

/** Presentation rules more than one HUD layer must agree on. Pure functions of the view. */
public final class BattleHudRules {
    private BattleHudRules() {}

    /** How long after a victory the cinematic framing (letterbox, bottom HUD slid out) is held. */
    public static final float VICTORY_CINEMATIC_SECONDS = 4.0f;

    /**
     * True while the command window is what the player's input talks to: the battle is running and
     * undecided, the monk may choose, and no timed prompt is open. A prompt always outranks the menu
     * (confirm then means "parry" or "hit the ring"), and the model keeps the window open during an
     * Archon action, so a guarding monk with a full gauge gets BOTH at once: the window must then look
     * as static as it behaves. Drawing, the target cursor and input routing all use this one rule.
     */
    public static boolean menuLive(BattleView view) {
        return view != null && view.phase() != BattlePhase.INTRO
                && view.outcome() == com.stonebreak.battle.api.BattleOutcome.NONE
                && view.prompt() == null && view.commandWindowOpen();
    }

    /**
     * Whether a command's row should look usable. ONE rule for the root window and every submenu: a
     * row short of a resource is dimmed whether the menu is live or resting; a row that is merely
     * waiting for the turn is never dimmed on top of the resting veil.
     */
    public static boolean rowUsable(BattleView view, com.stonebreak.battle.api.BattleCommand command) {
        if (view == null || command == null) return true;
        com.stonebreak.battle.api.CommandAvailability availability = view.availability(command);
        if (availability == null || availability.available()) return true;
        return !menuLive(view) && availability.onlyWaitingForTurn();
    }

    /**
     * True while the camera owns the moment and the battle has not begun: the intro. Letterbox bars
     * are in and the bottom HUD windows are slid out. It is deliberately NOT true for any action
     * animation (not even the Focus Combo): the HUD going away every time something animates read as
     * jarring in playtests, so during the fight the windows stay put and the command window merely
     * rests in its static state. The victory hold is time-based and handled by the caller with
     * {@link #VICTORY_CINEMATIC_SECONDS}, since the view carries no time-since-end.
     */
    public static boolean cinematic(BattleView view) {
        return view != null && view.phase() == BattlePhase.INTRO;
    }
}
