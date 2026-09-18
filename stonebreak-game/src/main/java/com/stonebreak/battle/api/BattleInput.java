package com.stonebreak.battle.api;

/** The only way anything outside the model changes the battle. */
public interface BattleInput {
    /** Camera intro finished or was skipped: INTRO → RUNNING. No-op in any other phase. */
    void introFinished();

    /**
     * Submits a monk command. Returns false (and raises {@link BattleEvent.CommandRejected}) when the
     * command window is closed or the command is unavailable.
     */
    boolean submit(BattleCommand command);

    /** Confirm button: resolves an open timing ring or a parry attempt. Ignored otherwise. */
    void pressConfirm();

    /** Directional button: answers the current Focus Combo prompt. Ignored otherwise. */
    void pressDirection(ComboDirection direction);
}
