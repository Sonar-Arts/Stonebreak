package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleEvent;

/** The Focus gauge, 0..max. Raises FocusChanged on every real change and FocusFull on reaching max. */
final class FocusMeter {

    private final float max;
    private final EventBuffer events;
    private float value;

    FocusMeter(float max, EventBuffer events) {
        this.max = Math.max(1f, max);
        this.events = events;
    }

    float value() {
        return value;
    }

    float max() {
        return max;
    }

    boolean full() {
        return value >= max;
    }

    void gain(float amount) {
        if (!(amount > 0f) || full()) return;
        float before = value;
        value = Math.min(max, value + amount);
        events.raise(new BattleEvent.FocusChanged(value - before, value));
        if (full()) events.raise(new BattleEvent.FocusFull());
    }

    /** Spends the whole gauge (Focus Combo). */
    void consumeAll() {
        if (value <= 0f) return;
        float before = value;
        value = 0f;
        events.raise(new BattleEvent.FocusChanged(-before, 0f));
    }
}
