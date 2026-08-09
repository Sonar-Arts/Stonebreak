package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.nav.GroundProbe;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Rests on a water surface, paddling slowly to nearby spots.
 *
 * <p>Ranks above the ambient behaviours so a swimmer that finds itself over water floats instead of
 * trying to stand on it, and it ends the moment the water does — a drained pond puts the mob back
 * on the ground rather than hovering where the surface used to be.
 *
 * <p>Vertical position is a spring toward the surface rather than a snap: the mob settles onto the
 * water over a few frames, which also absorbs the small gap a landing bird arrives with.
 */
public final class FloatOnWaterBehavior implements Behavior {

    private static final int PRIORITY = 90;

    /** Paddling is a drift, not a walk. */
    private static final float PADDLE_SPEED_MULTIPLIER = 0.4f;

    private static final float PADDLE_MIN_DISTANCE = 3.0f;
    private static final float PADDLE_MAX_DISTANCE = 8.0f;
    private static final float ARRIVAL_RADIUS = 1.5f;

    /** Strength of the pull toward the surface, and the speed it is allowed to reach. */
    private static final float SURFACE_SPRING = 6.0f;
    private static final float SURFACE_SPRING_MAX_SPEED = 1.5f;

    private final Vector3f target = new Vector3f();

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
        return context.entity().canSwim() && !Float.isNaN(surfaceOrNaN(context));
    }

    @Override
    public void start(AiContext context) {
        paddleSomewhere(context);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        float surface = surfaceOrNaN(context);
        if (Float.isNaN(surface)) {
            return; // water gone; shouldContinue ends this on the next tick
        }
        if (context.nav().isSettled()) {
            paddleSomewhere(context);
        }

        // Applied after navigation has committed its horizontal velocity for this tick.
        Vector3f velocity = context.entity().getVelocity();
        float toSurface = surface - context.entity().getPosition().y;
        velocity.y = Math.max(-SURFACE_SPRING_MAX_SPEED,
                Math.min(SURFACE_SPRING_MAX_SPEED, toSurface * SURFACE_SPRING));
        context.entity().setVelocity(velocity);
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.SWIMMING;
    }

    @Override
    public String debugName() {
        return "Float";
    }

    private void paddleSomewhere(AiContext context) {
        Vector3f position = context.entity().getPosition();
        float angle = context.random().nextFloat() * (float) (Math.PI * 2.0);
        float distance = context.randomBetween(PADDLE_MIN_DISTANCE, PADDLE_MAX_DISTANCE);
        target.set(
                position.x + (float) Math.cos(angle) * distance,
                position.y,
                position.z + (float) Math.sin(angle) * distance);
        context.nav().moveTo(target, ARRIVAL_RADIUS, PADDLE_SPEED_MULTIPLIER);
    }

    private static float surfaceOrNaN(AiContext context) {
        Vector3f position = context.entity().getPosition();
        float surface = GroundProbe.waterSurface(context.world(), position.x, position.y, position.z);
        return surface == Float.NEGATIVE_INFINITY ? Float.NaN : surface;
    }
}
