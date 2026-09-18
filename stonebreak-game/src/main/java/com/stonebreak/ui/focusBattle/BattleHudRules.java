package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;

/** Presentation rules more than one HUD layer must agree on. Pure functions of the view. */
public final class BattleHudRules {
    private BattleHudRules() {}

    /** How long after a victory the cinematic framing (letterbox, hidden command HUD) is held. */
    public static final float VICTORY_CINEMATIC_SECONDS = 4.0f;

    /**
     * True while the camera owns the moment: the intro and the Focus Combo. Letterbox bars are in and
     * the bottom HUD windows (command, party, help) are slid out. The victory hold is time-based and
     * handled by the caller with {@link #VICTORY_CINEMATIC_SECONDS}, since the view carries no
     * time-since-end.
     */
    public static boolean cinematic(BattleView view) {
        if (view == null) {
            return false;
        }
        if (view.phase() == BattlePhase.INTRO) {
            return true;
        }
        ActionView action = view.currentAction();
        return view.outcome() == BattleOutcome.NONE && action != null
                && action.command() == BattleCommand.FOCUS_COMBO;
    }
}
