package com.stonebreak.battle.api;

/**
 * An Archon attack winding up. {@code elapsed / impactTime} is the cast-bar fill; the parry window
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
