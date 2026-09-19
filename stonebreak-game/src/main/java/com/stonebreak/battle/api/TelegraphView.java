package com.stonebreak.battle.api;

/**
 * An Archon attack winding up. {@code cancelled} is RESERVED: actions never overlap in the current
 * model, so nothing can interrupt a windup and it is always false (an interrupted telegraph would
 * simply disappear, with {@link BattleEvent.TelegraphCancelled} raised). The HUD's cancelled look and
 * the camera's cancel handling exist for a future stun-mid-windup mechanic and are exercised only by
 * tests today.
 *
 * <p>Timeline: {@code elapsed / impactTime} is the cast-bar fill; the parry window
 * is a sub-range of the same timeline, in seconds from windup start.
 */
public record TelegraphView(EnemyAction action, float elapsed, float impactTime,
                            float parryWindowStart, float parryWindowEnd, boolean cancelled) {
    public float progress() {
        return impactTime <= 0f ? 1f : Math.max(0f, Math.min(1f, elapsed / impactTime));
    }

    public boolean inParryWindow() {
        return !cancelled && elapsed >= parryWindowStart && elapsed <= parryWindowEnd;
    }
}
