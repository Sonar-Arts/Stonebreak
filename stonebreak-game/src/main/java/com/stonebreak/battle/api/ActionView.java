package com.stonebreak.battle.api;

/**
 * The action currently executing (phase ACTION).
 *
 * @param command     the monk command, or null when the Archon is acting
 * @param enemyAction the Archon attack, or null when the monk is acting
 */
public record ActionView(CombatantId actor, String displayName, BattleCommand command,
                         EnemyAction enemyAction, float elapsed, float duration) {
    public float progress() {
        return duration <= 0f ? 1f : Math.max(0f, Math.min(1f, elapsed / duration));
    }
}
