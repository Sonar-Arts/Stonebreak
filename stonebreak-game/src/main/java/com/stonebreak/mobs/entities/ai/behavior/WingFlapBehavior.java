package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.EnumSet;

/**
 * The occasional one-shot flourish a bird gives while standing about.
 *
 * <p>Ranks above the ambient behaviours so it interrupts standing or grazing, and its odds are
 * expressed per second — {@link AiContext#deltaTime()} makes that independent of tick rate.
 * A mob without wings is simply never given one.
 */
public final class WingFlapBehavior implements Behavior {

    private static final int PRIORITY = 80;

    private final float chancePerSecond;
    private final float durationSeconds;

    private float remaining;

    public WingFlapBehavior(float chancePerSecond, float durationSeconds) {
        this.chancePerSecond = chancePerSecond;
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
    public boolean canStart(AiContext context) {
        if (chancePerSecond <= 0.0f || !context.entity().isOnGround()) {
            return false;
        }
        return context.random().nextFloat() < chancePerSecond * context.deltaTime();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return remaining > 0.0f;
    }

    @Override
    public void start(AiContext context) {
        remaining = durationSeconds;
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        remaining -= deltaTime;
        context.steering().stopMoving();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WING_FLAP;
    }

    @Override
    public String debugName() {
        return "WingFlap";
    }
}
