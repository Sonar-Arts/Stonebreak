package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.perception.LookTracker;
import com.stonebreak.mobs.entities.ai.perception.PlayerTracking;

import java.util.Random;

/**
 * What a Wisp's behaviours share: its fright meter and the mode it drives, the pacing that keeps
 * its tricks from coming so thick and fast that the player can follow it, and what it has learned
 * about its victim. The behaviours decide <em>what</em> to do; this decides whether they may.
 *
 * <p>Pure state on the Wisp's own clock (its age), so it can be driven from a test.
 */
public final class WispMind {

    /** Stalking scares the victim out of the Shade; attacking tries to kill them. */
    public enum Mode { STALK, ATTACK }

    private final WispTuning tuning;
    private final LookTracker look = new LookTracker();
    private final PlayerTracking tracking = new PlayerTracking();

    private Mode mode = Mode.STALK;
    private float meter;
    private float now;

    private float peekReadyAt;
    private float wooshReadyAt;
    private float veilReadyAt;
    /** Peek-a-boo and Woosh both wait for this: the quiet between scares. */
    private float sharedGapUntil;
    private float spottedAt = Float.NEGATIVE_INFINITY;
    /** Peek-a-boo just jerked back out of sight: the perfect moment to vanish. */
    private boolean perfectMoment;
    /** The player hit it and it has not answered yet. */
    private boolean hitPending;
    /** It is sneaking up for a Woosh, the one time being looked at means being spotted. */
    private boolean approaching;
    /** A flight failed with its victim close: it has nowhere to run and will fight. */
    private boolean cornered;

    public WispMind(WispTuning tuning) {
        this.tuning = tuning;
    }

    public WispTuning tuning() {
        return tuning;
    }

    public LookTracker look() {
        return look;
    }

    public PlayerTracking tracking() {
        return tracking;
    }

    /** Advances the clock to {@code age} and lets fear fade a little while it only stalks. */
    public void tick(float age, float dt) {
        now = age;
        if (mode == Mode.STALK) {
            meter = Math.max(0f, meter - tuning.decayPerSec() * dt);
        }
    }

    public Mode mode() {
        return mode;
    }

    public float meter() {
        return meter;
    }

    public float now() {
        return now;
    }

    /** Raises the meter; at the top it turns from stalking to attacking. */
    public void frighten(float amount) {
        meter = Math.min(tuning.meterMax(), meter + amount);
        if (meter >= tuning.meterMax()) {
            mode = Mode.ATTACK;
        }
    }

    /** The player struck it: a big jump on the meter, and an answer owed if it still stalks. */
    public void onHitByPlayer() {
        frighten(tuning.playerHitGain());
        hitPending = mode == Mode.STALK;
    }

    /** Takes the owed answer to a hit, if there is one. */
    public boolean takeHitPending() {
        boolean pending = hitPending;
        hitPending = false;
        return pending;
    }

    public boolean hitPending() {
        return hitPending;
    }

    /** The attack is over (its victim died or got out): back to stalking, still on edge. */
    public void calmDown() {
        mode = Mode.STALK;
        meter = Math.min(meter, tuning.calmMeter());
        hitPending = false;
        approaching = false;
    }

    // ── Pacing ──────────────────────────────────────────────────────────────

    public boolean peekReady() {
        return now >= peekReadyAt && now >= sharedGapUntil;
    }

    public boolean wooshReady() {
        return now >= wooshReadyAt && now >= sharedGapUntil;
    }

    public boolean veilReady() {
        return now >= veilReadyAt;
    }

    /** A Peek-a-boo is over: its cooldown and the shared quiet start now. */
    public void usedPeek(Random random) {
        peekReadyAt = now + tuning.peekCooldown();
        startSharedGap(random);
    }

    /** A Woosh is over, landed or abandoned: its cooldown and the shared quiet start now. */
    public void usedWoosh(Random random) {
        wooshReadyAt = now + tuning.wooshCooldown();
        perfectMoment = false;
        startSharedGap(random);
    }

    public void usedVeil() {
        veilReadyAt = now + tuning.veilCooldown();
    }

    private void startSharedGap(Random random) {
        sharedGapUntil = now + tuning.sharedGapMin()
                + random.nextFloat() * (tuning.sharedGapMax() - tuning.sharedGapMin());
    }

    // ── Reading the victim ──────────────────────────────────────────────────

    public void armPerfectMoment() {
        perfectMoment = true;
    }

    public boolean perfectMoment() {
        return perfectMoment;
    }

    public void spotted() {
        spottedAt = now;
        approaching = false;
    }

    public boolean recentlySpotted() {
        return now - spottedAt < tuning.spottedMemory();
    }

    public void setApproaching(boolean approaching) {
        this.approaching = approaching;
    }

    public boolean approaching() {
        return approaching;
    }

    /** It tried to get away and could not, with its victim close: it turns to fight. */
    public void corner() {
        cornered = true;
    }

    /** The cornered fight is over, won or broken off; it may try to run again. */
    public void uncorner() {
        cornered = false;
    }

    public boolean cornered() {
        return cornered;
    }

    /**
     * Whether sneaking up for a Woosh stands a chance: the victim has had their back turned long
     * enough (less long if they are walking, eyes on the path), is not whipping the camera about,
     * has not just caught it at this, and is not hunting for it. A Peek-a-boo that just made them
     * look the other way counts in its favour.
     */
    public boolean wooshReadIsSafe() {
        if (recentlySpotted() || tracking.isTracking() || look.franticness() > tuning.wooshMaxFranticness()) {
            return false;
        }
        float needed = look.isMoving() ? tuning.wooshBackTurnedMoving() : tuning.wooshBackTurned();
        if (perfectMoment) {
            needed *= 0.5f;
        }
        return look.backTurnedSeconds() >= needed;
    }
}
