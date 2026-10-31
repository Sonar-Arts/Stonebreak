package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Cornered: a flight failed with its victim close — pinned against the edge of the Shade, run into
 * a dead end, wedged — so the Wisp stops running. It drops its veil, shrieks, and holds its ground:
 * a claw for a victim in reach, whose knockback buys it room, and blue fire otherwise. It answers
 * any blow struck meanwhile the same way. After a few seconds, or as soon as the victim backs off,
 * it tries to get away again; if that fails too, it is back here.
 *
 * <p>Fights in either mode: a stalking Wisp does not need a full fright meter to fight for its
 * life, and an attacking one stops trying to break away.
 */
final class WispCorneredBehavior extends WispBehavior {

    private enum Phase { HOLD, SCRATCH, CAST }

    /** Pause between claws, so a victim who stays in reach is not shredded. */
    static final float SCRATCH_GAP = 0.8f;
    /** It needs a moment to turn before its first throw. */
    static final float FIRST_CAST_DELAY = 0.6f;
    /** Backed off this far past the corner range, the victim has let it go. */
    static final float RELEASE_MARGIN = 4f;
    static final float TURN_SPEED = 540f;

    private Phase phase = Phase.HOLD;
    private float elapsed;
    private float phaseElapsed;
    private float scratchReadyAt;
    private float castReadyAt;
    private boolean landed;
    private final Vector3f scratch = new Vector3f();

    WispCorneredBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public boolean canStart(AiContext context) {
        return mind().cornered() && senses().hasVictim() && !wisp.isDissolving();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        if (wisp.isDissolving() || !senses().hasVictim()) {
            return false;
        }
        // A swing or throw in progress always finishes.
        return phase != Phase.HOLD || (elapsed < tuning().cornerFightSeconds()
                && senses().distance() <= tuning().cornerRange() + RELEASE_MARGIN);
    }

    @Override
    public void start(AiContext context) {
        phase = Phase.HOLD;
        elapsed = 0f;
        scratchReadyAt = 0f;
        castReadyAt = FIRST_CAST_DELAY;
        wisp.unveil();
        wisp.emit("shriek");
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        phaseElapsed += deltaTime;
        mind().takeHitPending(); // fighting is its answer
        context.steering().stopMoving();
        context.faceTarget(TURN_SPEED);
        WispSenses senses = senses();
        switch (phase) {
            case HOLD -> {
                if (senses.distance() <= tuning().scratchReach() && elapsed >= scratchReadyAt) {
                    enter(Phase.SCRATCH);
                    landed = false;
                    wisp.emit("scratch");
                } else if (senses.distance() <= tuning().castRange() && elapsed >= castReadyAt
                        && context.canSeeTarget(handPosition(scratch))) {
                    enter(Phase.CAST);
                    landed = false;
                    wisp.emit("cast");
                }
            }
            case SCRATCH -> {
                if (!landed && phaseElapsed >= WispVanishBehavior.SCRATCH_IMPACT) {
                    landed = true;
                    if (senses.distance() <= tuning().scratchReach() * WispVanishBehavior.REACH_GRACE) {
                        context.hurtTarget(tuning().scratchDamage(), WispVanishBehavior.SCRATCH_KNOCKBACK);
                    }
                }
                if (phaseElapsed >= WispVanishBehavior.SCRATCH_SECONDS) {
                    enter(Phase.HOLD);
                    scratchReadyAt = elapsed + SCRATCH_GAP;
                }
            }
            case CAST -> {
                if (!landed && phaseElapsed >= WispKiteAndCastBehavior.CAST_RELEASE) {
                    landed = true;
                    castOrb(context);
                }
                if (phaseElapsed >= WispKiteAndCastBehavior.CAST_SECONDS) {
                    enter(Phase.HOLD);
                    castReadyAt = elapsed + context.randomBetween(tuning().castIntervalMin(), tuning().castIntervalMax());
                }
            }
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseElapsed = 0f;
    }

    @Override
    public void stop(AiContext context) {
        phase = Phase.HOLD;
        mind().uncorner();
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return switch (phase) {
            case SCRATCH -> MobBehaviorState.SCRATCH;
            case CAST -> MobBehaviorState.CAST;
            case HOLD -> MobBehaviorState.IDLE;
        };
    }

    @Override
    public String debugName() {
        return "Cornered";
    }
}
