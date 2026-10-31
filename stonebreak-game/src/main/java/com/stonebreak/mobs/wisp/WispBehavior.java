package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import org.joml.Vector3f;

/**
 * What every Wisp behaviour shares: the Wisp it belongs to, with its mind and senses, and the few
 * moves they all make — go somewhere, notice when the trip is over, and keep an eye on the victim.
 */
abstract class WispBehavior implements Behavior {

    /** Turning to keep the victim in front of it, degrees per second. */
    static final float WATCH_TURN_SPEED = 240f;
    /** How far ahead a tracking victim's walk is predicted, seconds. */
    static final float PREDICT_SECONDS = 3f;
    /** With no cover to make for, how far it runs across open ground. */
    static final float RUN_DISTANCE = 10f;
    /** Throwing hand: ahead of and level with its head. */
    static final float HAND_FORWARD = 0.5f;

    protected final Wisp wisp;

    WispBehavior(Wisp wisp) {
        this.wisp = wisp;
    }

    WispMind mind() {
        return wisp.mind();
    }

    WispSenses senses() {
        return wisp.senses();
    }

    WispTuning tuning() {
        return wisp.mind().tuning();
    }

    /** Heads for {@code goal} along a route, if there is one. */
    static void goTo(AiContext context, Vector3f goal, float radius, float speed) {
        context.nav().moveTo(goal, radius, speed);
    }

    /** Whether the current trip is over, one way or another: arrived, no way there, or wedged. */
    static boolean tripOver(AiContext context) {
        PathAgent.Status status = context.nav().status();
        return status == PathAgent.Status.ARRIVED || status == PathAgent.Status.NO_PATH
                || status == PathAgent.Status.STUCK || status == PathAgent.Status.IDLE;
    }

    /** Whether the current trip ended without getting there: no way through, or wedged. */
    static boolean tripFailed(AiContext context) {
        PathAgent.Status status = context.nav().status();
        return status == PathAgent.Status.NO_PATH || status == PathAgent.Status.STUCK;
    }

    /** Stands still facing the victim. */
    static void holdAndWatch(AiContext context) {
        context.steering().stopMoving();
        context.faceTarget(WATCH_TURN_SPEED);
    }

    /** Where to run to: hidden, away from the victim, between {@code bandMin} and {@code bandMax}. */
    Vector3f escapeSpot(float bandMin, float bandMax) {
        WispSenses senses = senses();
        return wisp.cover().find(wisp.getPosition(), 16f, senses.victimEye(), senses.victimLook(),
                bandMin, bandMax, CoverFinder.Preference.AWAY);
    }

    /**
     * Where to run, {@code bandMin}–{@code bandMax} from the victim: hidden cover if there is any,
     * else open ground away from them. Null only when every way off is barred.
     */
    Vector3f fleeSpot(float bandMin, float bandMax) {
        Vector3f spot = escapeSpot(bandMin, bandMax);
        if (spot != null || !senses().hasVictim()) {
            return spot;
        }
        return wisp.cover().runSpot(wisp.getPosition(), senses().victimFeet(), RUN_DISTANCE);
    }

    /**
     * A flight has failed — nowhere to run, no way there, or wedged. With the victim close, that
     * has it cornered, and it turns to fight ({@link WispCorneredBehavior}). True if it is.
     */
    boolean fleeFailed() {
        if (senses().hasVictim() && senses().distance() <= tuning().cornerRange()) {
            mind().corner();
            return true;
        }
        return false;
    }

    /** Where its throwing hand is now. */
    Vector3f handPosition(Vector3f out) {
        Vector3f forward = wisp.getForwardDirection();
        return out.set(wisp.getPosition())
                .add(forward.x * HAND_FORWARD, WispSenses.HEAD_HEIGHT + wisp.renderLift() - 0.2f,
                        forward.z * HAND_FORWARD);
    }

    /** Hurls a will-o'-wisp from its hand at the victim's chest. */
    void castOrb(AiContext context) {
        Vector3f target = context.targetPlayer();
        EntityManager manager = context.world() != null ? context.world().getEntityManager() : null;
        if (target == null || manager == null) {
            return;
        }
        Vector3f origin = handPosition(new Vector3f());
        Vector3f direction = new Vector3f(target).add(0f, PlayerLocator.CHEST_HEIGHT, 0f).sub(origin).normalize();
        manager.addEntity(new WillOWispOrb(context.world(), origin, direction, wisp.lockedTargetPlayerId(),
                tuning().orbDamage(), tuning().orbBurnSeconds(), tuning().orbBurnDps()));
    }

    /**
     * Where to slip to from a victim who is tracking it: hidden, well off, round to their side or
     * behind, clear of where they last saw it and of the way they are heading.
     */
    Vector3f flankSpot(float radius) {
        WispSenses senses = senses();
        return wisp.cover().find(wisp.getPosition(), radius, senses.victimEye(), senses.victimLook(),
                tuning().trackedBandMin(), tuning().trackedBandMax(), CoverFinder.Preference.FLANK, pursuit());
    }

    /** What the victim is about to do, from what it has seen of them. */
    CoverFinder.Pursuit pursuit() {
        Vector3f feet = new Vector3f(senses().victimFeet());
        Vector3f lastSeen = mind().tracking().lastSeen();
        return new CoverFinder.Pursuit(feet, lastSeen != null ? new Vector3f(lastSeen) : null,
                mind().look().predict(feet, PREDICT_SECONDS, new Vector3f()));
    }

    /** Whether the victim is hunting for it, so it should keep its distance and play no tricks. */
    boolean tracked() {
        return mind().tracking().isTracking();
    }
}
