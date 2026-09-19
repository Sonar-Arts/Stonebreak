package com.stonebreak.battle.api;

import java.util.List;

/** A timed-input prompt that is open right now. */
public sealed interface PromptView {

    PromptKind kind();

    /**
     * Flurry timing ring for one hit. All times are seconds from the moment this ring opened.
     * The ring shrinks over {@code duration}; pressing confirm inside the perfect or good range
     * grades the hit, otherwise (or on timeout) it is a MISS.
     */
    record Ring(int hitIndex, int hitCount, float elapsed, float duration,
                float perfectStart, float perfectEnd, float goodStart, float goodEnd) implements PromptView {
        @Override public PromptKind kind() { return PromptKind.RING; }
        public float progress() { return duration <= 0f ? 1f : Math.max(0f, Math.min(1f, elapsed / duration)); }
    }

    /** Incoming-hit timing: Guard enables a perfect parry; otherwise an on-time press blocks. */
    record Parry(float elapsed, float windowStart, float windowEnd, float impactTime,
                 boolean canParry) implements PromptView {
        public Parry(float elapsed, float windowStart, float windowEnd, float impactTime) {
            this(elapsed, windowStart, windowEnd, impactTime, true);
        }

        @Override public PromptKind kind() { return PromptKind.PARRY; }
    }

    /**
     * Focus Combo input string.
     *
     * @param sequence     the full prompt sequence
     * @param index        index of the prompt currently awaiting input
     * @param stepElapsed  seconds spent on the current prompt
     * @param stepDuration seconds allowed per prompt
     * @param results      grades of the prompts already resolved (size == index)
     */
    record Combo(List<ComboDirection> sequence, int index, float stepElapsed, float stepDuration,
                 List<TimedGrade> results) implements PromptView {
        public Combo {
            sequence = List.copyOf(sequence);
            results = List.copyOf(results);
        }
        @Override public PromptKind kind() { return PromptKind.COMBO; }
    }
}
