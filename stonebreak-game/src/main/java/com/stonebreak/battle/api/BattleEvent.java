package com.stonebreak.battle.api;

/**
 * Something that happened in the battle. Events raised during a frame (from input or from the
 * simulation step) are published together by {@link BattleView#frameEvents()} once that frame's
 * {@code update} completes, so every consumer (HUD, camera director, audio) sees the same list.
 */
public sealed interface BattleEvent {

    enum DamageFlavor { NORMAL, CRITICAL, BLOCKED, PARRIED }

    /** Intro finished; gauges start filling. */
    record BattleStarted() implements BattleEvent {}

    /** A combatant's ATB gauge filled. For the monk this opens the command window. */
    record TurnReady(CombatantId who) implements BattleEvent {}

    /** An action began executing. Exactly one of {@code command}/{@code enemyAction} is non-null. */
    record ActionStarted(CombatantId actor, String displayName, BattleCommand command,
                         EnemyAction enemyAction, float duration) implements BattleEvent {}

    record ActionFinished(CombatantId actor) implements BattleEvent {}

    /** The instant a blow lands (camera cut sync point). Raised even when the damage is parried. */
    record Impact(CombatantId actor, CombatantId target, int hitIndex, int hitCount) implements BattleEvent {}

    record DamageDealt(CombatantId target, float amount, DamageFlavor flavor) implements BattleEvent {}

    record Healed(CombatantId target, float amount) implements BattleEvent {}

    record QiChanged(int delta, int now) implements BattleEvent {}

    record FocusChanged(float delta, float now) implements BattleEvent {}

    /** Focus just reached its maximum. */
    record FocusFull() implements BattleEvent {}

    record StatusApplied(CombatantId target, BattleStatus status, float duration) implements BattleEvent {}

    record StatusExpired(CombatantId target, BattleStatus status) implements BattleEvent {}

    record TelegraphStarted(EnemyAction action, float impactTime) implements BattleEvent {}

    /** The telegraphed attack was cancelled (stun). */
    record TelegraphCancelled(EnemyAction action) implements BattleEvent {}

    record PromptOpened(PromptKind kind) implements BattleEvent {}

    /** @param index hit index for RING, prompt index for COMBO, 0 for PARRY */
    record PromptResolved(PromptKind kind, TimedGrade grade, int index) implements BattleEvent {}

    record ComboFinished(int hits, boolean flawless) implements BattleEvent {}

    record CommandRejected(BattleCommand command, String reason) implements BattleEvent {}

    /**
     * The battle is decided. This TERMINATES whatever was in flight: no {@link ActionFinished},
     * {@link PromptResolved} or {@link ComboFinished} follows for an action, prompt or combo string the
     * deciding blow cut short, and no status expiries are raised (statuses are simply cleared).
     * Consumers that pair start/finish events must treat {@code Ended} as the closing bracket.
     */
    record Ended(BattleOutcome outcome) implements BattleEvent {}
}
