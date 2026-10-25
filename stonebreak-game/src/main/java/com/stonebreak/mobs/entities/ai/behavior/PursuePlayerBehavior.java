package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Acts on what a mob's {@link AwarenessController} has noticed: walks to where the player was last
 * perceived while suspicious, and closes in on the player while alerted, holding at a working
 * distance facing them — melee reach for a brawler, bow range for an archer. The attack itself is
 * a separate, higher-priority behaviour that cuts in whenever its own conditions are met.
 *
 * <p>This is the movement half of awareness, split out from the detection half. The controller
 * still owns the meter and the states — which the stealth UI reads — while getting there became a
 * behaviour like any other. That split is what lets a pursuing mob route around the wall it lost
 * the player behind, instead of walking into it. Searching once the player is lost belongs to
 * {@link SearchBehavior}.
 *
 * <p>Ranks above fleeing, so an alerted mob commits to the chase rather than flinching away from a
 * player who hits it.
 */
public final class PursuePlayerBehavior implements Behavior {

    private static final int PRIORITY = 10;
    private static final float TURN_SPEED = 360.0f;

    /** Close enough to be "at" a remembered spot — a chase, not a docking manoeuvre. */
    private static final float ARRIVAL_RADIUS = 1.5f;

    private final AwarenessController awareness;
    private final float holdDistance;
    private final float investigateSpeedMultiplier;
    private final float pursueSpeedMultiplier;

    private final Vector3f target = new Vector3f();
    private boolean holding;

    /**
     * @param holdDistance stop closing in on an alerted target once this near, and just face them
     */
    public PursuePlayerBehavior(AwarenessController awareness, float holdDistance,
                                float investigateSpeedMultiplier, float pursueSpeedMultiplier) {
        this.awareness = awareness;
        this.holdDistance = holdDistance;
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
        return switch (awareness.getState()) {
            case ALERTED -> context.nearestPlayer() != null || awareness.lastKnownPlayerPosition(target) != null;
            case SUSPICIOUS -> awareness.lastKnownPlayerPosition(target) != null;
            case UNAWARE, SEARCHING -> false;
        };
    }

    @Override
    public void start(AiContext context) {
        holding = false;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        if (awareness.getState() == AwarenessController.AwarenessState.ALERTED) {
            Vector3f player = context.nearestPlayer();
            if (player != null) {
                closeIn(context, player);
                return;
            }
        }
        holding = false;
        Vector3f destination = awareness.lastKnownPlayerPosition(target);
        if (destination != null) {
            boolean alerted = awareness.getState() == AwarenessController.AwarenessState.ALERTED;
            context.nav().moveTo(destination, ARRIVAL_RADIUS,
                    alerted ? pursueSpeedMultiplier : investigateSpeedMultiplier);
        }
    }

    private void closeIn(AiContext context, Vector3f player) {
        if (context.entity().getPosition().distance(player) > holdDistance) {
            holding = false;
            context.nav().moveTo(player, Math.max(1.0f, holdDistance * 0.8f), pursueSpeedMultiplier);
        } else {
            if (!holding) {
                context.nav().stop();
                holding = true;
            }
            context.steering().stopMoving();
            context.facePlayer(TURN_SPEED);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return holding ? MobBehaviorState.IDLE : MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        if (awareness.getState() != AwarenessController.AwarenessState.ALERTED) {
            return "Investigate";
        }
        return holding ? "Hold" : "Pursue";
    }
}
