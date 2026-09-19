package com.stonebreak.battle.api;

import java.util.List;

/** Read-only view of the whole battle. Safe to read every frame; never mutates the model. */
public interface BattleView {
    BattlePhase phase();
    BattleOutcome outcome();
    /** Seconds of battle time since the intro finished (frozen in RESULT). */
    float elapsedSeconds();

    CombatantView monk();
    CombatantView archon();

    default CombatantView combatant(CombatantId id) {
        return id == CombatantId.MONK ? monk() : archon();
    }

    int qi();
    int maxQi();
    /** Focus gauge value, 0..{@link #maxFocus()}. */
    float focus();
    float maxFocus();
    default boolean focusReady() { return focus() >= maxFocus(); }
    default float focusFraction() { return maxFocus() <= 0f ? 0f : Math.max(0f, Math.min(1f, focus() / maxFocus())); }

    int meditateCharges();
    /**
     * Non-zero while a Martial Surge is queued. The value is the bonus a STRIKE would get; a Flurry
     * gets its own (larger) bonus, so present this as "Surge queued", not as a hit count.
     */
    int queuedSurgeHits();

    /**
     * True while the monk may submit a command: battle undecided, gauge full, no monk command queued
     * or executing, and either nothing is executing or it is the ARCHON's action (as in FF7 the player
     * may input while the enemy animates; that command is queued, except a reaction Guard, which
     * applies at once).
     */
    boolean commandWindowOpen();
    CommandAvailability availability(BattleCommand command);

    /** The executing action, or null. */
    ActionView currentAction();
    /** The Archon attack winding up, or null. */
    TelegraphView telegraph();
    /** The timed prompt awaiting input, or null. */
    PromptView prompt();

    BattleStats stats();

    /**
     * Events published by the most recent {@code update}. Immutable; the same list is returned to
     * every caller until the next update replaces it.
     */
    List<BattleEvent> frameEvents();

    /** True while a ring, block, or parry prompt is open: the camera must not cut or move sharply. */
    default boolean promptSafeRequired() {
        PromptView p = prompt();
        return p != null && p.kind() != PromptKind.COMBO;
    }
}
