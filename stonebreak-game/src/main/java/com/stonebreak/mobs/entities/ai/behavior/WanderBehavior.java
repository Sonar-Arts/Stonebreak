package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Ambles to a random spot nearby.
 *
 * <p>It picks a direction and a distance and hands the point straight to navigation — no ground
 * probing, no reachability guessing. The search snaps the point to a real surface and answers with
 * a route or with nothing, and "nothing" simply ends this behaviour so another spot gets picked.
 * That is the whole difference from the old wander, which could only re-roll random points and hope
 * one happened to be reachable in a straight line.
 */
public final class WanderBehavior implements Behavior {

    /** Give up on one spot after this long, however the route is going. */
    private static final float MAX_SECONDS = 12.0f;

    /** Close enough to count as arrived — mobs mill about, they do not park on coordinates. */
    private static final float ARRIVAL_RADIUS = 1.0f;

    private final float weight;
    private final float minDistance;
    private final float maxDistance;
    private final float speedMultiplier;

    private final Vector3f target = new Vector3f();
    private float elapsed;

    public WanderBehavior(float weight, float minDistance, float maxDistance, float speedMultiplier) {
        this.weight = weight;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.speedMultiplier = speedMultiplier;
    }

    @Override
    public int priority() {
        return StandStillBehavior.AMBIENT_PRIORITY;
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
        return elapsed < MAX_SECONDS && !context.nav().isSettled();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0.0f;
        pickSpot(context);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING;
    }

    private void pickSpot(AiContext context) {
        Vector3f position = context.entity().getPosition();
        float angle = context.random().nextFloat() * (float) (Math.PI * 2.0);
        float distance = context.randomBetween(minDistance, maxDistance);

        target.set(
                position.x + (float) Math.cos(angle) * distance,
                position.y,
                position.z + (float) Math.sin(angle) * distance);
        context.nav().moveTo(target, ARRIVAL_RADIUS, speedMultiplier);
    }
}
