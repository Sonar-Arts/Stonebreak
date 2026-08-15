package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Acts on what a mob's {@link AwarenessController} has noticed: walks to where the player was last
 * seen while suspicious, and chases the player while alerted.
 *
 * <p>This is the movement half of awareness, split out from the detection half. The controller
 * still owns the meter and the states — which the stealth UI reads — while getting there became a
 * behaviour like any other. That split is what lets a pursuing mob route around the wall it lost
 * the player behind, instead of walking into it: the old version steered in a straight line at the
 * remembered position and stopped when something solid got in the way.
 *
 * <p>Ranks above fleeing, so an alerted mob commits to the chase rather than flinching away from a
 * player who hits it.
 */
public final class PursuePlayerBehavior implements Behavior {

    private static final int PRIORITY = 10;

    /** Close enough to be "at" the target — a chase, not a docking manoeuvre. */
    private static final float ARRIVAL_RADIUS = 1.5f;

    private final AwarenessController awareness;
    private final float investigateSpeedMultiplier;
    private final float pursueSpeedMultiplier;

    private final Vector3f target = new Vector3f();

    public PursuePlayerBehavior(AwarenessController awareness,
                                float investigateSpeedMultiplier, float pursueSpeedMultiplier) {
        this.awareness = awareness;
        this.investigateSpeedMultiplier = investigateSpeedMultiplier;
        this.pursueSpeedMultiplier = pursueSpeedMultiplier;
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
        return destinationFor(context) != null;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        Vector3f destination = destinationFor(context);
        if (destination == null) {
            return; // shouldContinue ends this on the next tick
        }
        boolean alerted = awareness.getState() == AwarenessController.AwarenessState.ALERTED;
        context.nav().moveTo(destination, ARRIVAL_RADIUS,
                alerted ? pursueSpeedMultiplier : investigateSpeedMultiplier);
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING; // pursuit is walking, as far as the model knows
    }

    @Override
    public String debugName() {
        return awareness.getState() == AwarenessController.AwarenessState.ALERTED
                ? "Pursue" : "Investigate";
    }

    /**
     * Where to head: the player while alerted, their last known position while suspicious, and
     * nowhere at all while unaware.
     */
    private Vector3f destinationFor(AiContext context) {
        return switch (awareness.getState()) {
            case ALERTED -> {
                Vector3f player = context.nearestPlayer();
                if (player != null) {
                    yield target.set(player);
                }
                yield awareness.lastKnownPlayerPosition(target);
            }
            case SUSPICIOUS -> awareness.lastKnownPlayerPosition(target);
            case UNAWARE -> null;
        };
    }
}
