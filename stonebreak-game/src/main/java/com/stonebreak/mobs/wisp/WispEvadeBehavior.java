package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Slipping the victim's eye. Stared at — or merely glimpsed by a victim already hunting for it —
 * the Wisp draws its veil, dims even its eyes, darts sideways off their line of sight, and makes for
 * cover round on their flank: well off, away from where they saw it, and out of the way they are
 * heading. A player who keeps tracking it finds only empty trees. If there is no way out with
 * the player close, it is cornered and fights instead ({@link WispCorneredBehavior}).
 *
 * <p>It costs nothing on the fright meter: being hunted is not one of its tricks, and a player who
 * merely looks around a lot should not be what pushes it into an attack. Peek-a-boo, which wants
 * to be seen, outranks it, so a noticed peek is not mistaken for being caught.
 */
final class WispEvadeBehavior extends WispBehavior {

    private enum Phase { BREAK, FLANK, DONE }

    /** The sideways dart: across their view cone loses their eye faster than backing off. */
    static final float BREAK_SECONDS = 0.6f;
    static final float BREAK_SPEED = 2.0f; // × RUN_SPEED: 7 b/s
    /** How far out it tests each side for Shade before darting that way. */
    static final float BREAK_PROBE = 3f;
    static final float FLANK_SPEED = 1.6f;
    static final float FLANK_SECONDS = 6f;
    static final float SEARCH_RADIUS = 18f;
    /** How often it checks its flank spot is still out of their sight. */
    static final float REPLAN_SECONDS = 1f;

    private Phase phase = Phase.DONE;
    private float elapsed;
    private float replanAt;
    private float readyAt;
    private Vector3f goal;
    private final Vector3f side = new Vector3f();

    WispEvadeBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean canStart(AiContext context) {
        WispSenses senses = senses();
        return mind().mode() == WispMind.Mode.STALK && !wisp.isDissolving() && wisp.getAge() >= readyAt
                && senses.hasVictim() && senses.victimLook() != null
                && (senses.watched() || (tracked() && senses.inView()));
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return phase != Phase.DONE && mind().mode() == WispMind.Mode.STALK && senses().hasVictim()
                && !wisp.isDissolving();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        goal = null;
        mind().spotted();
        wisp.veil(tuning().veilSeconds());
        wisp.darkenEyes(tuning().veilSeconds());
        context.nav().stop();
        if (chooseBreakSide()) {
            phase = Phase.BREAK;
        } else {
            startFlank();
        }
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        switch (phase) {
            case BREAK -> {
                context.steering().steerAlong(side, BREAK_SPEED, deltaTime, false);
                if (elapsed >= BREAK_SECONDS) {
                    startFlank();
                }
            }
            case FLANK -> {
                if (goal == null || (elapsed > 0.5f && tripFailed(context))) {
                    fleeFailed();
                    phase = Phase.DONE;
                    return;
                }
                if (elapsed >= FLANK_SECONDS || (elapsed > 0.5f && tripOver(context))) {
                    phase = Phase.DONE;
                    return;
                }
                // They moved and the spot is in plain view now: find another.
                if (elapsed >= replanAt && !wisp.cover().hidden(senses().victimEye(), goal)) {
                    replanAt = elapsed + REPLAN_SECONDS;
                    Vector3f next = flankSpot(SEARCH_RADIUS);
                    if (next != null) {
                        goal = next;
                    }
                }
                goTo(context, goal, 1.0f, FLANK_SPEED);
            }
            case DONE -> { }
        }
    }

    /**
     * Picks which way to dart: square to the victim's line to it, into the Shade, and the side
     * further from the way they are heading. False when neither side is open Shade.
     */
    private boolean chooseBreakSide() {
        Vector3f self = wisp.getPosition();
        Vector3f feet = senses().victimFeet();
        float dx = self.x - feet.x;
        float dz = self.z - feet.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3f) {
            return false;
        }
        CoverFinder.Pursuit pursuit = pursuit();
        Vector3f probe = new Vector3f();
        float bestClearance = Float.NEGATIVE_INFINITY;
        boolean found = false;
        for (int sign = -1; sign <= 1; sign += 2) {
            float sx = -dz / len * sign;
            float sz = dx / len * sign;
            probe.set(self.x + sx * BREAK_PROBE, self.y, self.z + sz * BREAK_PROBE);
            if (!wisp.shade().isShade(probe)) {
                continue;
            }
            float clearance = pursuit.distanceToPath(probe);
            if (clearance > bestClearance) {
                bestClearance = clearance;
                side.set(sx, 0f, sz);
                found = true;
            }
        }
        return found;
    }

    private void startFlank() {
        phase = Phase.FLANK;
        elapsed = 0f;
        replanAt = REPLAN_SECONDS;
        goal = flankSpot(SEARCH_RADIUS);
        if (goal == null) {
            goal = fleeSpot(tuning().trackedBandMin(), tuning().trackedBandMax());
        }
    }

    @Override
    public void stop(AiContext context) {
        phase = Phase.DONE;
        wisp.brightenEyes();
        context.nav().stop();
        readyAt = wisp.getAge() + tuning().evadeCooldown();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "Evade";
    }
}
