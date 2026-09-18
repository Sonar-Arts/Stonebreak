package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleEvent;

/** Qi pips, 0..max. Raises QiChanged on every real change. */
final class QiPool {

    private final int max;
    private final EventBuffer events;
    private int value;

    QiPool(int max, int start, EventBuffer events) {
        this.max = Math.max(0, max);
        this.value = Math.max(0, Math.min(this.max, start));
        this.events = events;
    }

    int value() {
        return value;
    }

    int max() {
        return max;
    }

    boolean canAfford(int cost) {
        return value >= cost;
    }

    void gain(int amount) {
        change(Math.min(amount, max - value));
    }

    void spend(int cost) {
        change(-Math.min(cost, value));
    }

    private void change(int delta) {
        if (delta == 0) return;
        value += delta;
        events.raise(new BattleEvent.QiChanged(delta, value));
    }
}
