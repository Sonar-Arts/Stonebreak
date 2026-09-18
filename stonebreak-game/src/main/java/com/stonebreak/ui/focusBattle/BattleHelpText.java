package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.battle.api.PromptView;

/**
 * What the help strip (E4) says right now. Pure: a function of the battle view and the cursor.
 *
 * <p>With the command window open it describes the highlighted row, or — when that row cannot be
 * used — says why, so a dead key press is never silent. With the window closed it prompts for the
 * input the battle is waiting on.
 */
public final class BattleHelpText {

    /** @param warning true when the line explains why the highlighted command is unavailable */
    public record Line(String text, boolean warning) {
        public static final Line EMPTY = new Line("", false);
        public boolean isEmpty() { return text.isEmpty(); }
    }

    public static final String INTRO_HINT = "Press Confirm to skip.";
    public static final String RING_HINT = "Press Confirm as the ring closes!";
    public static final String PARRY_HINT = "Press Confirm as the blow lands to parry!";
    public static final String COMBO_HINT = "Match each direction in time!";
    public static final String TARGET_HINT = "Choose a target. Confirm to attack, Back to return.";

    private BattleHelpText() {}

    public static Line lineFor(BattleView view, BattleMenuState menu) {
        if (view == null) return Line.EMPTY;
        if (view.phase() == BattlePhase.INTRO) return new Line(INTRO_HINT, false);
        if (view.phase() == BattlePhase.RESULT) return Line.EMPTY;

        PromptView prompt = view.prompt();
        if (prompt instanceof PromptView.Combo) return new Line(COMBO_HINT, false);
        if (prompt instanceof PromptView.Ring) return new Line(RING_HINT, false);
        if (prompt instanceof PromptView.Parry) return new Line(PARRY_HINT, false);

        if (view.commandWindowOpen() && menu != null) return menuLine(view, menu);

        ActionView action = view.currentAction();
        if (action != null && action.command() != null) {
            // Between the prompts of a timed action there is no open prompt for a beat; holding the
            // hint for the whole action stops the strip flipping back and forth on every hit.
            if (action.command() == BattleCommand.FLURRY) return new Line(RING_HINT, false);
            if (action.command() == BattleCommand.FOCUS_COMBO) return new Line(COMBO_HINT, false);
            return new Line(action.command().helpText(), false);
        }
        return Line.EMPTY;
    }

    private static Line menuLine(BattleView view, BattleMenuState menu) {
        if (menu.targeting()) return targetLine(view, menu.targetCommand());
        BattleCommand command = menu.highlightedCommand();
        if (command == null) return new Line(BattleMenu.QI_ARTS_HELP, false);
        CommandAvailability availability = view.availability(command);
        if (availability != null && !availability.available()
                && availability.reason() != null && !availability.reason().isEmpty()) {
            return new Line(availability.reason(), true);
        }
        String help = command.helpText();
        // No brackets: the UI font draws "(" and ")" as near-letters ("C1 QiD").
        if (command.qiCost() > 0) help = help + "  Cost: " + command.qiCost() + " Qi.";
        return new Line(help, false);
    }

    /** Target step: names the command being aimed, or why it can no longer be used. */
    private static Line targetLine(BattleView view, BattleCommand command) {
        CommandAvailability availability = view.availability(command);
        if (availability != null && !availability.available()
                && availability.reason() != null && !availability.reason().isEmpty()) {
            return new Line(availability.reason(), true);
        }
        return new Line(command.displayName() + ": " + TARGET_HINT, false);
    }
}
