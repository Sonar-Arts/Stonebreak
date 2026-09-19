package com.stonebreak.battle.api;

/** The only way anything outside the model changes the battle. */
public interface BattleInput {
    /** Camera intro finished or was skipped: INTRO → RUNNING. No-op in any other phase. */
    void introFinished();

    /**
     * Submits a monk command. Returns false when the command window is closed or the command is
     * unavailable, raising {@link BattleEvent.CommandRejected} with the reason; a null command, or any
     * command once the battle is decided, is refused silently. A Guard submitted during an Archon
     * telegraph applies immediately (reaction guard): it spends the turn and raises the GUARDING
     * status and a parry prompt, but no {@code ActionStarted}/{@code ActionFinished}.
     */
    boolean submit(BattleCommand command);

    /** Confirm button: resolves an open timing ring or a parry attempt. Ignored otherwise. */
    void pressConfirm();

    /** Directional button: answers the current Focus Combo prompt. Ignored otherwise. */
    void pressDirection(ComboDirection direction);
}
