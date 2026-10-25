package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.combat.WeaponProfile;

import java.util.EnumSet;

/**
 * Swings a melee weapon at a player in reach: plant feet, turn to face, play the swing, and land
 * the blow at the moment the clip shows it. The mob is committed for the whole clip — no gliding
 * attacks, no cancelling a swing to chase.
 */
public final class MeleeAttackBehavior implements Behavior {

    /** Outranks the chase, so a mob in reach swings instead of shuffling closer. */
    static final int PRIORITY = 5;

    private static final float TURN_SPEED = 540.0f;
    /** A swing only starts roughly facing the player; the chase's hold turns it the rest of the way. */
    private static final float START_FACING_DEGREES = 45.0f;
    /** The blow still connects if the player has backed off a little mid-swing. */
    private static final float REACH_GRACE = 1.5f;

    private final WeaponProfile.Melee weapon;

    private float elapsed;
    private boolean landed;
    /** Entity age at which the next swing may start. */
    private float readyAt;

    public MeleeAttackBehavior(WeaponProfile.Melee weapon) {
        this.weapon = weapon;
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
        return context.entity().getAge() >= readyAt
                && context.distanceToNearestPlayer() <= weapon.reach()
                && context.facePlayer(0.0f) <= START_FACING_DEGREES;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return elapsed < weapon.clipSeconds();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0.0f;
        landed = false;
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        context.steering().stopMoving();
        if (!landed) {
            context.facePlayer(TURN_SPEED); // track the target through the wind-up only
        }
        if (!landed && elapsed >= weapon.impactSeconds()) {
            landed = true;
            if (context.distanceToNearestPlayer() <= weapon.reach() * REACH_GRACE) {
                context.hurtNearestPlayer(weapon.damage(), weapon.knockback());
            }
        }
    }

    @Override
    public void stop(AiContext context) {
        readyAt = context.entity().getAge() + weapon.cooldown();
    }

    @Override
    public MobBehaviorState animationState() {
        return weapon.animation();
    }

    @Override
    public String debugName() {
        return "Melee";
    }
}
