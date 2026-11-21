package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Puts distance between the mob and the player — because it was hurt, because the player got too
 * close, or both.
 *
 * <p>Damage arming comes through {@link #onDamaged}, which is delivered to every behaviour whether
 * it is running or not: the behaviour that reacts to a hit is by definition not the one that was
 * running when it landed. A hit runs from the attacker that landed it ({@link AiContext#attacker}),
 * which in multiplayer need not be the nearest player. Proximity is the other trigger, for skittish mobs that bolt before
 * anything touches them, and it is hysteretic — once running, it keeps running until the player is
 * past {@code safeRange}, so a mob does not stutter in and out of panic at the threshold.
 *
 * <p>Fleeing routes rather than sprinting blindly away: the mob picks a spot away from the threat
 * and lets navigation find the way there, so it rounds a rock instead of pressing into it. If the
 * spot turns out unreachable it picks another, which is what running along a cliff edge looks like.
 */
public final class FleeBehavior implements Behavior {

    private static final int PRIORITY = 20;

    /** How close counts as far enough away — this is a retreat, not a destination. */
    private static final float ARRIVAL_RADIUS = 2.0f;

    private final float distance;
    private final float durationSeconds;
    private final float speedMultiplier;
    private final float walkTriggerRange;
    private final float sprintTriggerRange;
    private final float safeRange;

    /** Non-player threats (a goblin, to a sheep) and the ranges they trigger and release a flight at. */
    private ThreatSense threats = ThreatSense.NONE;
    private float threatTriggerRange;
    private float threatSafeRange;
    /** Whether a hit sets off a flight; off for a mob whose reaction to being hit is another behaviour's. */
    private boolean armOnHit = true;

    private final Vector3d threat = new Vector3d();
    private final Vector3d sensed = new Vector3d();
    private final Vector3d target = new Vector3d();
    private boolean threatKnown;
    /** The threat came from a hit, which already says who to run from. */
    private boolean hitArmed;
    private float remaining;

    /**
     * @param walkTriggerRange   player distance that starts a flight; zero for mobs that only flee
     *                           when actually hurt
     * @param sprintTriggerRange the same for a sprinting player, who is noticed further off
     * @param safeRange          the player must be beyond this before a flight ends
     */
    public FleeBehavior(float distance, float durationSeconds, float speedMultiplier,
                        float walkTriggerRange, float sprintTriggerRange, float safeRange) {
        this.distance = distance;
        this.durationSeconds = durationSeconds;
        this.speedMultiplier = speedMultiplier;
        this.walkTriggerRange = walkTriggerRange;
        this.sprintTriggerRange = sprintTriggerRange;
        this.safeRange = safeRange;
    }

    /** Flees only when damaged — the usual livestock reaction. */
    public FleeBehavior(float distance, float durationSeconds, float speedMultiplier) {
        this(distance, durationSeconds, speedMultiplier, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Also bolts from what {@code sense} perceives — within {@code triggerRange} it starts a
     * flight, which runs until the threat is past {@code safeRange}.
     *
     * @return this, for chaining off the constructor
     */
    public FleeBehavior withThreat(ThreatSense sense, float triggerRange, float safeRange) {
        this.threats = sense;
        this.threatTriggerRange = triggerRange;
        this.threatSafeRange = safeRange;
        return this;
    }

    /**
     * Flees only on proximity, never because it was hit — for a mob that reacts to a hit some
     * other way (a chicken freezes; see StartleBehavior).
     *
     * @return this, for chaining
     */
    public FleeBehavior ignoringHits() {
        this.armOnHit = false;
        return this;
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
        if (!armOnHit) {
            return;
        }
        Vector3d attacker = context.attacker();
        Vector3d player = attacker != null ? attacker : context.nearestPlayer();
        if (player != null) {
            // Whoever landed the hit; only when the source did not say, the nearest player.
            threat.set(player);
        } else {
            // Hurt by something we cannot see: run the way we happen to be facing.
            threat.set(context.entity().getPosition()).sub(context.entity().getForwardDirection());
        }
        threatKnown = true;
        hitArmed = true;
        remaining = durationSeconds;
    }

    @Override
    public boolean canStart(AiContext context) {
        if (threatKnown && remaining > 0.0f) {
            return true;
        }
        return playerWithin(context, triggerRange(context)) || sensedWithin(context, threatTriggerRange) != null;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        // Hysteresis: keep going until the timer runs out AND the player is properly clear.
        return remaining > 0.0f || playerWithin(context, safeRange)
                || sensedWithin(context, threatSafeRange) != null;
    }

    @Override
    public void start(AiContext context) {
        if (!hitArmed) {
            rememberThreat(context); // a proximity flight runs from the player that set it off
        }
        pickRetreat(context);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        remaining -= deltaTime;
        // A fleeing mob keeps the player at its back even as the player follows.
        if (playerWithin(context, safeRange) || sensedWithin(context, threatSafeRange) != null) {
            rememberThreat(context);
        }
        if (context.nav().isSettled()) {
            pickRetreat(context); // arrived, or the way is blocked — keep moving off
        }
    }

    /** The distance at which this mob notices the player, wider when the player is sprinting. */
    private float triggerRange(AiContext context) {
        return context.nearestPlayerSprinting() ? sprintTriggerRange : walkTriggerRange;
    }

    private boolean playerWithin(AiContext context, float range) {
        return range > 0.0f && context.distanceToNearestPlayer() <= range;
    }

    /** The nearest sensed non-player threat within {@code range}, or null. Scratch. */
    private Vector3d sensedWithin(AiContext context, float range) {
        return range > 0.0f ? threats.nearest(context, range, sensed) : null;
    }

    /** Runs from the nearer of the nearest player and the nearest sensed threat. */
    private void rememberThreat(AiContext context) {
        Vector3d other = sensedWithin(context, Math.max(threatTriggerRange, threatSafeRange));
        Vector3d player = context.nearestPlayer();
        if (other != null && (player == null || context.distanceTo(other) < context.distanceTo(player))) {
            threat.set(other);
            threatKnown = true;
        } else if (player != null) {
            threat.set(player);
            threatKnown = true;
        }
    }

    @Override
    public void stop(AiContext context) {
        remaining = 0.0f;
        threatKnown = false;
        hitArmed = false;
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING; // fleeing is walking, as far as the model knows
    }

    private void pickRetreat(AiContext context) {
        if (!threatKnown) {
            return; // hurt by nothing we can locate and nobody nearby — nothing to run from
        }
        Vector3d position = context.entity().getPosition();
        target.set(position).sub(threat);
        target.y = 0.0;
        if (target.lengthSquared() < 0.01) {
            // Standing on top of the threat: any direction is away from it.
            double angle = context.random().nextFloat() * (Math.PI * 2.0);
            target.set(Math.cos(angle), 0.0, Math.sin(angle));
        }
        target.normalize().mul(distance).add(position);
        target.y = position.y;
        context.nav().moveTo(target, ARRIVAL_RADIUS, speedMultiplier);
    }
}
