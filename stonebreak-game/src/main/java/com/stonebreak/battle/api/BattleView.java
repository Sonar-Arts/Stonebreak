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
    /** Bonus hits queued by Martial Surge for the next Strike or Flurry. */
    int queuedSurgeHits();

    /** True while the monk may submit a command (gauge full, nothing executing, battle running). */
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

    /** True while a ring or parry prompt is open: the camera must not cut or move sharply. */
    default boolean promptSafeRequired() {
        PromptView p = prompt();
        return p != null && p.kind() != PromptKind.COMBO;
    }
}
