package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Notices a player who comes within a radius and closes in on them, then holds at a working
 * distance facing them — melee reach for a brawler, bow range for an archer. The attack itself is a
 * separate, higher-priority behaviour that cuts in whenever its own conditions are met.
 *
 * <p>A plain distance check, unlike {@link PursuePlayerBehavior}, which acts on what an
 * {@link com.stonebreak.mobs.entities.ai.AwarenessController} has noticed. The give-up radius is
 * wider than the notice radius so a player hovering at the edge does not flip the mob between
 * chasing and wandering every step.
 */
public final class ChasePlayerBehavior implements Behavior {

    private static final int PRIORITY = 10;
    private static final float TURN_SPEED = 360.0f;

    private final float noticeRadius;
    private final float giveUpRadius;
    private final float holdDistance;
    private final float speedMultiplier;

    private boolean holding;

    /**
     * @param holdDistance stop closing in once this near, and just face the player
     */
    public ChasePlayerBehavior(float noticeRadius, float giveUpRadius, float holdDistance,
                               float speedMultiplier) {
        this.noticeRadius = noticeRadius;
        this.giveUpRadius = giveUpRadius;
        this.holdDistance = holdDistance;
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
        return context.distanceToNearestPlayer() <= noticeRadius;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return context.distanceToNearestPlayer() <= giveUpRadius;
    }

    @Override
    public void start(AiContext context) {
        holding = false;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        Vector3f player = context.nearestPlayer();
        if (player == null) {
            return; // shouldContinue ends this on the next tick
        }
        if (context.entity().getPosition().distance(player) > holdDistance) {
            holding = false;
            context.nav().moveTo(player, Math.max(1.0f, holdDistance * 0.8f), speedMultiplier);
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
        return holding ? "Hold" : "Chase";
    }
}
