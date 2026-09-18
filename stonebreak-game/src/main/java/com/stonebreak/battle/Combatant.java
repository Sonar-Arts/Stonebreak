package com.stonebreak.battle;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.StatusView;

import java.util.List;

/**
 * One side of the battle: HP, gauge, statuses and the pose the stage should draw.
 *
 * <p>While an action owns the combatant it writes the pose directly ({@link #drive}), as a pure
 * function of the action clock. Otherwise the combatant plays a {@link PoseChain} (reactions, the way
 * home, endings) that falls back on its resting loop, so an action cut short never strands an actor.
 */
final class Combatant implements CombatantView {

    /**
     * The clips a combatant rests in and how it gets home on its own.
     *
     * @param introLoop     rest pose held until the battle starts
     * @param combatLoop    ready stance
     * @param heldStatus    status that swaps the resting loop (GUARDING, STUNNED)
     * @param heldLoop      resting loop while {@code heldStatus} is up
     * @param homeClip      gait played while walking home undriven; null glides in whatever is playing
     * @param homeSeconds   seconds a full-length trip home takes
     * @param followThrough longest an interrupted one-shot keeps playing before the actor turns for home
     */
    record Stance(BattleClip introLoop, BattleClip combatLoop, BattleStatus heldStatus, BattleClip heldLoop,
                  BattleClip homeClip, float homeSeconds, float followThrough) {}

    private final CombatantId id;
    private final String displayName;
    private final float maxHp;
    private final AtbGauge gauge;
    private final Stance stance;
    private final StatusSet statuses = new StatusSet();

    private float hp;
    private boolean inCombat;
    private float recoil;
    private boolean recoilRising;

    // driven pose
    private boolean driven;
    private BattleClip drivenClip;
    private float drivenClipTime;
    private float drivenDash;
    private float standoff;

    // undriven pose
    private PoseChain chain = new PoseChain();
    private float homeFrom;
    private float homeHold;
    private float homeSpan;
    private float homeClock;

    Combatant(CombatantId id, String displayName, float maxHp, AtbGauge gauge, Stance stance) {
        this.id = id;
        this.displayName = displayName;
        this.maxHp = Math.max(1f, maxHp);
        this.hp = this.maxHp;
        this.gauge = gauge;
        this.stance = stance;
    }

    @Override public CombatantId id() { return id; }
    @Override public String displayName() { return displayName; }
    @Override public float hp() { return hp; }
    @Override public float maxHp() { return maxHp; }
    @Override public float atb() { return gauge.value(); }
    @Override public List<StatusView> statuses() { return statuses.views(); }
    @Override public boolean has(BattleStatus status) { return statuses.has(status); }

    /**
     * Presence is always 1: both death clips carry their own collapse and the asset handoff forbids
     * sinking or fading the actor on top of it.
     */
    @Override
    public ActorPose pose() {
        if (driven) {
            return new ActorPose(drivenClip.state(), drivenClipTime, drivenDash, Math.max(recoil, standoff), 1f);
        }
        PoseChain.Frame frame = chain.frame(restingLoop());
        return new ActorPose(frame.clip().state(), frame.clipTime(), homeDash(), recoil, 1f);
    }

    AtbGauge gauge() { return gauge; }
    StatusSet statusSet() { return statuses; }
    boolean driven() { return driven; }

    /** Removes HP (never below 0), kicks the recoil reaction, and returns the amount actually lost. */
    float takeDamage(float amount) {
        float lost = Math.max(0f, Math.min(hp, amount));
        hp -= lost;
        flinch();
        return lost;
    }

    /** Restores HP (never above max) and returns the amount actually restored. */
    float heal(float amount) {
        float gained = Math.max(0f, Math.min(maxHp - hp, amount));
        hp += gained;
        return gained;
    }

    /** Kicks the recoil reaction: it rises to 1 (see {@code Damage.recoilRiseSeconds}) and then decays. */
    void flinch() {
        recoilRising = true;
    }

    // ---- driven ---------------------------------------------------------------------------------

    /**
     * Called by the executing action every step it owns this actor.
     *
     * @param clipTime seconds into {@code clip}; wrapped / clamped here
     * @param standoff 0..1 extra distance from the opponent, published through {@code pose.recoil}
     *                 (a kick reaches farther than a punch)
     */
    void drive(BattleClip clip, float clipTime, float dash, float standoff) {
        driven = true;
        drivenClip = clip;
        drivenClipTime = clip.timeAt(clipTime);
        drivenDash = clamp01(dash);
        this.standoff = clamp01(standoff);
    }

    /** The executing action ran to its end: its timeline already brought the actor home. */
    void settleHome() {
        driven = false;
        standoff = 0f;
        chain = new PoseChain();
        startHome(0f, 0f);
    }

    /**
     * The executing action was cut short (battle over): let the blow in flight follow through, then
     * walk home from wherever the actor is.
     */
    void release() {
        if (!driven) return;
        float hold = drivenClip.loop() ? 0f
                : Math.max(0f, Math.min(stance.followThrough(), drivenClip.duration() - drivenClipTime));
        PoseChain next = new PoseChain().then(drivenClip, drivenClipTime, hold);
        float span = stance.homeSeconds() * drivenDash;
        if (stance.homeClip() != null) {
            next.then(stance.homeClip(), drivenClip == stance.homeClip() ? drivenClipTime : 0f, span);
        }
        undrive(next, hold);
    }

    // ---- undriven -------------------------------------------------------------------------------

    /** Battle start: play the entrance (if any), then the ready stance from now on. */
    void enterCombat(BattleClip... entrance) {
        inCombat = true;
        play(entrance);
    }

    /** Plays one-shots in order, then the resting loop. Takes over from whatever was playing, driven or not. */
    void play(BattleClip... oneShots) {
        playFrom(0f, oneShots);
    }

    /** {@link #play} with the first clip entered {@code firstFrom} seconds in (so a cue can be made to land on time). */
    void playFrom(float firstFrom, BattleClip... oneShots) {
        PoseChain next = new PoseChain();
        for (int i = 0; i < oneShots.length; i++) {
            BattleClip clip = oneShots[i];
            if (i == 0) next.then(clip, firstFrom, clip.duration() - firstFrom);
            else next.then(clip);
        }
        if (driven) undrive(next, 0f);
        else chain = next;
    }

    /** Ends the battle for this actor right here: a one-shot, then its terminal loop. */
    void collapse(BattleClip oneShot, BattleClip loop) {
        PoseChain next = new PoseChain().then(oneShot).endingIn(loop);
        if (driven) undrive(next, 0f);
        else chain = next;
    }

    /** Ends the battle for this actor once it is back home: finish the blow, walk home, then celebrate. */
    void celebrate(BattleClip oneShot, BattleClip loop) {
        if (driven) release();
        else chain = new PoseChain();
        chain.then(oneShot).endingIn(loop);
    }

    private void undrive(PoseChain next, float hold) {
        driven = false;
        // A kick stand-off must not snap shut: hand it to the recoil timer, which eases it away.
        recoil = Math.max(recoil, standoff);
        standoff = 0f;
        chain = next;
        startHome(drivenDash, hold);
    }

    private void startHome(float from, float hold) {
        homeFrom = from;
        homeHold = hold;
        homeSpan = stance.homeSeconds() * from;
        homeClock = 0f;
    }

    private float homeDash() {
        if (homeFrom <= 0f) return 0f;
        float t = homeClock - homeHold;
        if (t <= 0f) return homeFrom;
        if (t >= homeSpan) return 0f;
        return homeFrom * (1f - BattleAction.ease(t / homeSpan));
    }

    private BattleClip restingLoop() {
        if (!inCombat) return stance.introLoop();
        return statuses.has(stance.heldStatus()) ? stance.heldLoop() : stance.combatLoop();
    }

    /** Pose clock of an undriven actor. Runs on battle time while the battle runs, frame time otherwise. */
    void advancePose(float dt) {
        if (driven || !(dt > 0f)) return;
        chain.advance(dt);
        homeClock += dt;
    }

    /**
     * Hit-reaction recoil: up to 1 over {@code riseSeconds} after a hit, then 1→0 over
     * {@code recoilSeconds}. Purely cosmetic, runs in every phase. The frame that publishes the hit
     * still shows the target where the blow met it; the knock-back follows.
     */
    void advanceRecoil(float dt, float riseSeconds, float recoilSeconds) {
        float left = dt;
        if (recoilRising) {
            float toPeak = Math.max(0f, (1f - recoil) * riseSeconds);
            if (left < toPeak) {
                recoil += left / riseSeconds;
                return;
            }
            recoil = 1f;
            recoilRising = false;
            left -= toPeak; // a long frame carries on into the decay
        }
        if (recoil <= 0f) return;
        recoil = recoilSeconds <= 0f ? 0f : Math.max(0f, recoil - left / recoilSeconds);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
