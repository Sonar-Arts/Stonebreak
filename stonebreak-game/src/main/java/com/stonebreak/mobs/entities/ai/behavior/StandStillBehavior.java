package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.EnumSet;

/**
 * Stands in place for a while, looking like whatever it was told to look like.
 *
 * <p>Idling and grazing are the same behaviour with a different animation and a different weight,
 * so they are the same class configured twice rather than two near-identical ones. A mob that
 * should never graze simply is not given the grazing instance.
 *
 * <p>Velocity is zeroed every tick rather than once on start: water flow and knockback keep pushing
 * a mob that has stopped deciding to move.
 */
public final class StandStillBehavior implements Behavior {

    /** Ambient behaviours all share a priority and compete on weight. */
    public static final int AMBIENT_PRIORITY = 100;

    private final MobBehaviorState animation;
    private final float weight;
    private final float minSeconds;
    private final float maxSeconds;

    private float remaining;

    public StandStillBehavior(MobBehaviorState animation, float weight,
                              float minSeconds, float maxSeconds) {
        this.animation = animation;
        this.weight = weight;
        this.minSeconds = minSeconds;
        this.maxSeconds = maxSeconds;
    }

    /** Standing around doing nothing. */
    public static StandStillBehavior idle(float weight, float minSeconds, float maxSeconds) {
        return new StandStillBehavior(MobBehaviorState.IDLE, weight, minSeconds, maxSeconds);
    }

    /** Head down, eating. */
    public static StandStillBehavior graze(float weight, float minSeconds, float maxSeconds) {
        return new StandStillBehavior(MobBehaviorState.GRAZING, weight, minSeconds, maxSeconds);
    }

    @Override
    public int priority() {
        return AMBIENT_PRIORITY;
    }

    @Override
    public float weight() {
        return weight;
    }

    @Override
    public EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    @Override
    public boolean canStart(AiContext context) {
        return weight > 0.0f;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return remaining > 0.0f;
    }

    @Override
    public void start(AiContext context) {
        remaining = context.randomBetween(minSeconds, maxSeconds);
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        remaining -= deltaTime;
        context.steering().stopMoving();
    }

    @Override
    public MobBehaviorState animationState() {
        return animation;
    }

    @Override
    public String debugName() {
        return animation == MobBehaviorState.GRAZING ? "Graze" : "Idle";
    }
}
