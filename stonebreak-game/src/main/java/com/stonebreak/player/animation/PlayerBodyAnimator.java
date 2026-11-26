package com.stonebreak.player.animation;

import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.rig.JointPalette;

/**
 * One player-shaped body's animation (#457): a graph instance of its {@link PlayerAnimationSet}, evaluated ONCE per
 * update into a MODEL-SPACE palette ({@code base} = identity). Every pass that draws the body - the main draw,
 * shadow cascades, torch faces, wireframe, socket attachments - reads {@link #palette()} and supplies the body's base
 * matrix ({@code T(position) Ry(yaw) S(scale)}) as the draw's model transform, so they all show the same pose.
 *
 * <p><b>Update rate</b> (Unreal's Update Rate Optimization): {@link #update} with {@code interval} n evaluates every
 * n-th call with the time gathered since the last evaluation; between evaluations the body keeps its last pose while
 * its base matrix still moves with it. A trigger-like input ({@code attacking}) held on any skipped frame is kept, so a
 * short pulse is never missed. {@link #intervalFor} picks n from the distance to the camera.
 *
 * <p><b>Footsteps</b>: a {@link PlayerAnimationSet#FOOTSTEP} notify plants a foot when the clips carry them; until
 * they do, the gait blend's phase does (contact on entering the gait and at every half cycle: both shipped walk/run
 * clips plant alternating feet at phases 0 and 0.5 - the old {@code PlayerGaitClock} rule, now read from the pose the
 * body actually shows). Either way only while grounded and out of water. Every other notify is handed on by name
 * (the asset's FX/sound event of that name). Thread-confined: the thread that updates it.
 */
public final class PlayerBodyAnimator implements AutoCloseable {

    /** What the body's animation emits during an update. */
    public interface Events {
        /** A foot landed (grounded, out of water). */
        void footstep();

        /** A clip notify other than a footstep: the asset event of that name. */
        default void event(String name) {
        }

        Events NONE = new Events() {
            @Override
            public void footstep() {
            }
        };
    }

    /** {@code CM_NOTIFY_END} (anim_clip.h): a state notify's end edge. */
    private static final int NOTIFY_END = 2;

    private final PlayerAnimationSet set;
    private final AnimGraphInstance instance;
    private final JointPalette palette = new JointPalette();
    private final float[] node = new float[3];

    private float pendingDt;
    private int framesSince;
    private boolean attackLatch;
    private boolean posed;
    private boolean inGait;
    private double gaitProgress;
    private float gaitPhase;

    public PlayerBodyAnimator(PlayerAnimationSet set) {
        this.set = set;
        this.instance = set.newInstance();
    }

    /** The player character's body. */
    public static PlayerBodyAnimator player() {
        return new PlayerBodyAnimator(PlayerAnimationSet.player());
    }

    /** Update interval for a body {@code distanceSq} blocks² from the camera: 1 near, up to 4 far. */
    public static int intervalFor(double distanceSq) {
        if (distanceSq <= 32.0 * 32.0) return 1;
        if (distanceSq <= 64.0 * 64.0) return 2;
        if (distanceSq <= 128.0 * 128.0) return 3;
        return 4;
    }

    /** {@link #update(float, PlayerPoseInputs, int, Events)} every call. */
    public boolean update(float dt, PlayerPoseInputs in, Events events) {
        return update(dt, in, 1, events);
    }

    /**
     * Advances the body by {@code dt} seconds with this frame's inputs; evaluates the graph when {@code interval}
     * calls have gathered (always on the first). Returns whether it evaluated.
     */
    public boolean update(float dt, PlayerPoseInputs in, int interval, Events events) {
        if (Float.isFinite(dt) && dt > 0f) pendingDt += dt;
        attackLatch |= in.attacking;
        if (posed && ++framesSince < Math.max(1, interval)) return false;
        evaluate(in, events);
        return true;
    }

    private void evaluate(PlayerPoseInputs in, Events events) {
        AnimGraphInstance g = instance;
        if (set.speed >= 0) g.setFloat(set.speed, in.speed);
        if (set.grounded >= 0) g.setBool(set.grounded, in.grounded);
        if (set.inWater >= 0) g.setBool(set.inWater, in.inWater);
        if (set.seated >= 0) g.setBool(set.seated, in.seated);
        if (set.crouched >= 0) g.setBool(set.crouched, in.crouched);
        if (set.attacking >= 0) g.setBool(set.attacking, attackLatch);
        g.clearExtras();
        if (in.headYaw != 0f || in.headPitch != 0f) g.extra(set.headJoint, in.headYaw, in.headPitch, 0f, 0f, 0f);
        g.evaluate(pendingDt, null, palette);
        pendingDt = 0f;
        framesSince = 0;
        attackLatch = false;
        posed = true;

        boolean feet = in.grounded && !in.inWater;
        boolean contact = gaitContact();
        if (set.footstepNotifies()) contact = false;
        int notifies = g.notifyCount();
        for (int i = 0; i < notifies; i++) {
            AnimGraphInstance.Notify n = g.notifyAt(i);
            if (n.phase() == NOTIFY_END) continue;  // a point, or a state notify's begin (its end is not an event)
            if (PlayerAnimationSet.FOOTSTEP.equals(n.name())) {
                contact |= set.footstepNotifies();
            } else {
                events.event(n.name());
            }
        }
        if (contact && feet) events.footstep();
    }

    /** Reads the gait blend: phase for the bob, one contact on entry and per half cycle (at most one per update). */
    private boolean gaitContact() {
        if (set.gaitNode < 0) return false;
        boolean active = instance.nodeDebug(set.gaitNode, node) && node[0] > 0f;
        if (!active) {
            inGait = false;
            gaitPhase = 0f;
            return false;
        }
        double progress = node[2];
        boolean contact = !inGait || Math.floor(progress * 2 + 1e-7) > Math.floor(gaitProgress * 2 + 1e-7);
        inGait = true;
        gaitProgress = progress;
        gaitPhase = (float) (progress - Math.floor(progress));
        return contact;
    }

    /** The last evaluated pose, model space (draws supply the base matrix). */
    public JointPalette palette() { return palette; }

    /** Whether the body has been posed at least once. */
    public boolean posed() { return posed; }

    /** Gait cycle phase in [0, 1) while walking/running, else 0 (first-person bob). */
    public float gaitPhase() { return gaitPhase; }

    /** Whether the gait blend shows (walking or running). */
    public boolean inGait() { return inGait; }

    public PlayerAnimationSet set() { return set; }

    /** The graph instance (debugging, tests). */
    public AnimGraphInstance instance() { return instance; }

    @Override
    public void close() {
        instance.close();
    }
}
