package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.EnumSet;

/**
 * Freezes on being hit, for mobs that startle rather than bolt.
 *
 * <p>Shares {@link FleeBehavior}'s priority: a mob is given one or the other, and which it gets is
 * the whole of its damage personality.
 */
public final class StartleBehavior implements Behavior {

    private static final int PRIORITY = 20;

    private final float durationSeconds;
    private float remaining;

    public StartleBehavior(float durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    @Override
    public void onDamaged(AiContext context, float damage) {
        remaining = durationSeconds;
    }

    @Override
    public boolean canStart(AiContext context) {
        return remaining > 0.0f;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return remaining > 0.0f;
    }

    @Override
    public void start(AiContext context) {
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        remaining -= deltaTime;
        context.steering().stopMoving();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.IDLE;
    }
}
