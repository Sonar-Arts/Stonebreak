package com.stonebreak.mobs.entities.status;

/**
 * A single active timed debuff instance on a living entity. Mutable holder owned and
 * ticked exclusively by {@link com.stonebreak.mobs.entities.LivingEntity}.
 */
public final class StatusEffect {

    /** How often a DOT effect (e.g. burning) applies its tick damage, in seconds. */
    public static final float DOT_TICK_INTERVAL = 1.0f;

    private final StatusEffectType type;
    private float remainingDuration;
    private float magnitude;
    private float tickAccumulator;

    public StatusEffect(StatusEffectType type, float duration, float magnitude) {
        this.type = type;
        this.remainingDuration = duration;
        this.magnitude = magnitude;
        this.tickAccumulator = 0f;
    }

    public StatusEffectType getType() { return type; }
    public float getMagnitude() { return magnitude; }
    public boolean isExpired() { return remainingDuration <= 0f; }

    /**
     * Reapplies this effect: the remaining duration is extended (never shortened) and the
     * magnitude is replaced with the provided value ({@code LivingEntity} decides whether the
     * caller's magnitude or the stronger of the two should win). Same-type effects are
     * refreshed rather than stacked.
     */
    public void refresh(float duration, float magnitude) {
        this.remainingDuration = Math.max(this.remainingDuration, duration);
        this.magnitude = magnitude;
    }

    /** Advances the effect's clock. Returns true once per elapsed DOT tick interval (for DOT effects). */
    public boolean tick(float deltaTime) {
        remainingDuration -= deltaTime;
        tickAccumulator += deltaTime;
        if (tickAccumulator >= DOT_TICK_INTERVAL) {
            tickAccumulator -= DOT_TICK_INTERVAL;
            return true;
        }
        return false;
    }
}
