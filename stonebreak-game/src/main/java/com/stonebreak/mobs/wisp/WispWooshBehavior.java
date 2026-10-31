package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Woosh: with the victim's back turned, the Wisp creeps up behind them, rushes past their ear for a
 * sting, and is veiled and gone before they have turned round. It only tries when it has read them
 * as safe to approach ({@link WispMind#wooshReadIsSafe}); if they turn to face it on the way in it
 * gives up quietly, and if they catch it close the {@code spotted} reaction takes over.
 */
final class WispWooshBehavior extends WispBehavior {

    private enum Phase { APPROACH, STRIKE, ESCAPE, DONE }

    /** How far behind the victim it aims for, and how close counts as at their ear. */
    static final float BEHIND_DISTANCE = 1.3f;
    static final float STRIKE_RANGE = 1.9f;
    static final float APPROACH_SPEED = 2.0f;
    static final float APPROACH_SECONDS = 9f;
    static final float STRIKE_SECONDS = 0.6f;
    static final float STRIKE_IMPACT = 0.25f;
    static final float STRIKE_KNOCKBACK = 2.5f;
    static final float ESCAPE_SECONDS = 5f;
    static final float ESCAPE_SPEED = 2.0f; // × RUN_SPEED: 7 b/s, past a sprinting player (6)

    private Phase phase = Phase.DONE;
    private float elapsed;
    private boolean landed;
    private Vector3f refuge;
    private final Vector3f behind = new Vector3f();

    WispWooshBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public boolean canStart(AiContext context) {
        WispSenses senses = senses();
        return mind().mode() == WispMind.Mode.STALK && mind().wooshReady()
                && senses.hasVictim() && senses.victimInShade() && senses.victimLook() != null
                && !senses.inView() && senses.distance() <= tuning().wooshStartRange()
                && mind().wooshReadIsSafe();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return phase != Phase.DONE && mind().mode() == WispMind.Mode.STALK && senses().hasVictim();
    }

    @Override
    public void start(AiContext context) {
        phase = Phase.APPROACH;
        elapsed = 0f;
        landed = false;
        refuge = null;
        mind().setApproaching(true);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        switch (phase) {
            case APPROACH -> approach(context);
            case STRIKE -> strike(context);
            case ESCAPE -> {
                if (refuge == null || (elapsed > 0.5f && tripFailed(context))) {
                    fleeFailed();
                    phase = Phase.DONE;
                } else if (elapsed >= ESCAPE_SECONDS || (elapsed > 0.5f && tripOver(context))) {
                    phase = Phase.DONE;
                } else {
                    goTo(context, refuge, 1.0f, ESCAPE_SPEED);
                }
            }
            case DONE -> { }
        }
    }

    private void approach(AiContext context) {
        WispSenses senses = senses();
        if (senses.distance() <= STRIKE_RANGE) {
            phase = Phase.STRIKE;
            elapsed = 0f;
            mind().setApproaching(false);
            context.nav().stop();
            wisp.emit("woosh");
            return;
        }
        // They turned round (but not close enough to catch it), went frantic, or it took too long:
        // the moment has passed.
        boolean facing = mind().look().backTurnedSeconds() <= 0f && senses.distance() > tuning().spottedRange();
        if (elapsed >= APPROACH_SECONDS || facing
                || mind().look().franticness() > tuning().wooshMaxFranticness()) {
            phase = Phase.DONE;
            return;
        }
        Vector3f look = senses.victimLook();
        Vector3f feet = senses.victimFeet();
        float lx = look != null ? look.x : 0f;
        float lz = look != null ? look.z : 0f;
        float len = (float) Math.sqrt(lx * lx + lz * lz);
        behind.set(feet);
        if (len > 1.0e-3f) {
            behind.add(-lx / len * BEHIND_DISTANCE, 0f, -lz / len * BEHIND_DISTANCE);
        }
        if (!wisp.shade().isShade(behind)) {
            behind.set(feet); // their back is to the border: come at them straight
        }
        goTo(context, behind, 0.8f, APPROACH_SPEED);
    }

    private void strike(AiContext context) {
        context.faceTarget(720f);
        // The rush carries it past their ear.
        Vector3f forward = wisp.getForwardDirection();
        context.steering().steerAlong(new Vector3f(forward.x, 0f, forward.z).normalize(),
                APPROACH_SPEED, context.deltaTime(), false);
        if (!landed && elapsed >= STRIKE_IMPACT) {
            landed = true;
            context.hurtTarget(tuning().wooshDamage(), STRIKE_KNOCKBACK);
            mind().frighten(tuning().wooshGain());
            wisp.veil(tuning().veilSeconds());
        }
        if (elapsed >= STRIKE_SECONDS) {
            phase = Phase.ESCAPE;
            elapsed = 0f;
            refuge = fleeSpot(tuning().lurkBandMin(), tuning().lurkBandMax());
        }
    }

    @Override
    public void stop(AiContext context) {
        phase = Phase.DONE;
        mind().setApproaching(false);
        mind().usedWoosh(context.random());
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return phase == Phase.STRIKE ? MobBehaviorState.WOOSH : MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "Woosh";
    }
}
