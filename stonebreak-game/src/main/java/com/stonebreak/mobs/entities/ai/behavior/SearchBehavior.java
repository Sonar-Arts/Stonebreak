package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * What a mob does once it has lost the player (#321): goes to where they were last perceived, then
 * pokes about nearby — a few short walks with a pause to look around at each — until its
 * {@link AwarenessController} memory runs out and it goes back to its business.
 *
 * <p>Runs only while the controller says SEARCHING. Seeing the player again flips the controller to
 * ALERTED, which ends this and hands over to {@link PursuePlayerBehavior}.
 */
public final class SearchBehavior implements Behavior {

    private static final int PRIORITY = 10;

    /** Close enough to a search spot to stop and look. */
    private static final float ARRIVAL_RADIUS = 1.5f;
    /** Spots to poke at lie this far from where the player was last perceived. */
    private static final float LOOK_RADIUS = 4.0f;
    private static final float MIN_PAUSE = 0.8f;
    private static final float MAX_PAUSE = 1.8f;
    private static final float LOOK_TURN_SPEED = 120.0f;

    private final AwarenessController awareness;
    private final float speedMultiplier;

    private final Vector3f anchor = new Vector3f();
    private final Vector3f spot = new Vector3f();
    private final Vector3f lookDir = new Vector3f();
    private final Vector3f lastKnown = new Vector3f();
    private boolean walking;
    private float pause;

    public SearchBehavior(AwarenessController awareness, float speedMultiplier) {
        this.awareness = awareness;
        this.speedMultiplier = speedMultiplier;
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
        return awareness.getState() == AwarenessController.AwarenessState.SEARCHING
                && awareness.lastKnownPlayerPosition(lastKnown) != null;
    }

    @Override
    public void start(AiContext context) {
        awareness.lastKnownPlayerPosition(anchor);
        searchFromAnchor(context);
    }

    private void searchFromAnchor(AiContext context) {
        spot.set(anchor);
        walking = true;
        context.nav().moveTo(spot, ARRIVAL_RADIUS, speedMultiplier);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        // A fresh sound or a packmate's shout moves the search: start again from there.
        if (awareness.lastKnownPlayerPosition(lastKnown) != null && lastKnown.distanceSquared(anchor) > 1.0f) {
            anchor.set(lastKnown);
            searchFromAnchor(context);
            return;
        }
        if (walking) {
            if (context.nav().isSettled()) {
                walking = false;
                pause = context.randomBetween(MIN_PAUSE, MAX_PAUSE);
                float angle = context.random().nextFloat() * (float) (Math.PI * 2.0);
                lookDir.set((float) Math.cos(angle), 0f, (float) Math.sin(angle));
                context.nav().stop();
            }
            return;
        }
        context.steering().faceDirection(lookDir, LOOK_TURN_SPEED, deltaTime);
        pause -= deltaTime;
        if (pause <= 0f) {
            float angle = context.random().nextFloat() * (float) (Math.PI * 2.0);
            float distance = context.randomBetween(1.0f, LOOK_RADIUS);
            spot.set(anchor.x + (float) Math.cos(angle) * distance, anchor.y,
                    anchor.z + (float) Math.sin(angle) * distance);
            walking = true;
            context.nav().moveTo(spot, ARRIVAL_RADIUS, speedMultiplier);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return walking ? MobBehaviorState.WANDERING : MobBehaviorState.IDLE;
    }

    @Override
    public String debugName() {
        return walking ? "Search" : "Look around";
    }
}
