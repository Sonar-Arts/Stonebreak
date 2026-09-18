package com.stonebreak.battle.api;

/**
 * One active status on a combatant.
 *
 * @param remainingSeconds seconds left, or a negative value when the status lasts until consumed
 *                         (GUARDING until the next impact, SURGE until the next attack)
 * @param stacks           stack count (SURGE = queued bonus hits); 1 for unstacked statuses
 */
public record StatusView(BattleStatus status, float remainingSeconds, int stacks) {
    public boolean timed() { return remainingSeconds >= 0f; }
}
