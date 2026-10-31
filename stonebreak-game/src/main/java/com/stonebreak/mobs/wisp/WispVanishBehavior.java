package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

import java.util.function.Predicate;

/**
 * The Wisp's way out of trouble: a claw swipe if the player is in reach, then a smokescreen, the
 * veil, and a dash to cover. Three triggers share it —
 *
 * <ul>
 *   <li><b>hit</b> (stalking): the player struck it and the meter did not fill — it answers, then
 *       vanishes;</li>
 *   <li><b>break melee</b> (attacking): the player closed in — it scratches and resets to range;</li>
 *   <li><b>spotted</b> (stalking): caught sneaking up for a Woosh, inside the spotting range — no
 *       claw, just smoke.</li>
 * </ul>
 *
 * <p>If the dash finds no way out with the player still close, it is cornered and stops running
 * ({@link WispCorneredBehavior}); none of the three fire while it is.
 */
final class WispVanishBehavior extends WispBehavior {

    private enum Phase { SCRATCH, SMOKE, FLEE, DONE }

    static final float SCRATCH_SECONDS = 0.6f;
    static final float SCRATCH_IMPACT = 0.3f;
    static final float SCRATCH_KNOCKBACK = 3f;
    /** The swipe still lands if the player stepped back a little mid-swing. */
    static final float REACH_GRACE = 1.4f;
    static final float SMOKE_SECONDS = 0.35f;
    static final float FLEE_SECONDS = 5f;
    static final float FLEE_SPEED = 2.0f; // × RUN_SPEED: 7 b/s, past a sprinting player (6)

    private final String name;
    private final int priority;
    private final Predicate<WispBehavior> trigger;
    private final boolean mayScratch;
    private final boolean spotting;
    private final boolean attackBand;
    /** Whether the trigger waits out {@code meleeBreakCooldown} between firings (break-melee). */
    private final boolean cooled;

    private Phase phase = Phase.DONE;
    private float elapsed;
    private boolean landed;
    private Vector3f refuge;
    private float readyAt;

    private WispVanishBehavior(Wisp wisp, String name, int priority, Predicate<WispBehavior> trigger,
                               boolean mayScratch, boolean spotting, boolean attackBand, boolean cooled) {
        super(wisp);
        this.name = name;
        this.priority = priority;
        this.trigger = trigger;
        this.mayScratch = mayScratch;
        this.spotting = spotting;
        this.attackBand = attackBand;
        this.cooled = cooled;
    }

    /** Hit while stalking, meter not full: answer and vanish. */
    static WispVanishBehavior hitReaction(Wisp wisp) {
        return new WispVanishBehavior(wisp, "HitReaction", 2,
                b -> b.mind().mode() == WispMind.Mode.STALK && b.mind().hitPending(),
                true, false, false, false);
    }

    /** Attacking, and the player got too close: scratch, vanish, back to range. */
    static WispVanishBehavior breakMelee(Wisp wisp) {
        return new WispVanishBehavior(wisp, "BreakMelee", 2,
                b -> b.mind().mode() == WispMind.Mode.ATTACK && b.senses().hasVictim()
                        && b.senses().distance() <= b.tuning().meleeBreakRange(),
                true, false, true, true);
    }

    /** Caught sneaking up for a Woosh: smoke and gone. */
    static WispVanishBehavior spotted(Wisp wisp) {
        return new WispVanishBehavior(wisp, "Spotted", 3,
                b -> b.mind().mode() == WispMind.Mode.STALK && b.mind().approaching()
                        && b.senses().watched() && b.senses().distance() <= b.tuning().spottedRange(),
                false, true, false, false);
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean canStart(AiContext context) {
        return !wisp.isDissolving() && !mind().cornered() && (!cooled || wisp.getAge() >= readyAt)
                && trigger.test(this);
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return phase != Phase.DONE && !wisp.isDissolving();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        landed = false;
        refuge = null;
        mind().takeHitPending();
        if (spotting) {
            mind().spotted();
            mind().frighten(tuning().spottedGain());
        }
        context.nav().stop();
        boolean inReach = senses().hasVictim() && senses().distance() <= tuning().scratchReach();
        phase = mayScratch && inReach ? Phase.SCRATCH : Phase.SMOKE;
        if (phase == Phase.SCRATCH) {
            wisp.emit("scratch");
        } else {
            throwSmoke();
        }
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        switch (phase) {
            case SCRATCH -> {
                context.steering().stopMoving();
                if (!landed) {
                    context.faceTarget(540f);
                }
                if (!landed && elapsed >= SCRATCH_IMPACT) {
                    landed = true;
                    if (senses().distance() <= tuning().scratchReach() * REACH_GRACE) {
                        context.hurtTarget(tuning().scratchDamage(), SCRATCH_KNOCKBACK);
                    }
                }
                if (elapsed >= SCRATCH_SECONDS) {
                    throwSmoke();
                }
            }
            case SMOKE -> {
                context.steering().stopMoving();
                if (elapsed >= SMOKE_SECONDS) {
                    phase = Phase.FLEE;
                    elapsed = 0f;
                    refuge = senses().hasVictim() ? refuge() : null;
                }
            }
            case FLEE -> {
                if (refuge == null || (elapsed > 0.5f && tripFailed(context))) {
                    fleeFailed();
                    phase = Phase.DONE;
                    return;
                }
                if (elapsed >= FLEE_SECONDS) {
                    phase = Phase.DONE;
                    return;
                }
                goTo(context, refuge, 1.0f, FLEE_SPEED);
                if (elapsed > 0.5f && tripOver(context)) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> { }
        }
    }

    private void throwSmoke() {
        phase = Phase.SMOKE;
        elapsed = 0f;
        wisp.smokescreen();
        wisp.veil(tuning().veilSeconds());
    }

    private Vector3f refuge() {
        float kite = tuning().kiteDistance();
        return attackBand ? fleeSpot(kite, kite + 4f)
                : fleeSpot(tuning().lurkBandMin() + 2f, tuning().lurkBandMax());
    }

    @Override
    public void stop(AiContext context) {
        phase = Phase.DONE;
        context.nav().stop();
        readyAt = wisp.getAge() + tuning().meleeBreakCooldown();
    }

    @Override
    public MobBehaviorState animationState() {
        return switch (phase) {
            case SCRATCH -> MobBehaviorState.SCRATCH;
            case SMOKE -> MobBehaviorState.CAST;
            case FLEE, DONE -> MobBehaviorState.WANDERING;
        };
    }

    @Override
    public String debugName() {
        return name;
    }
}
