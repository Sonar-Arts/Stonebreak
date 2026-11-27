package com.stonebreak.mobs.sbe;

import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.EnumMap;
import java.util.Map;

/**
 * One graph-animated mob's body (#460): a graph instance of its type's {@link MobAnimationSet}, evaluated once per
 * update into a MODEL-SPACE palette that every pass (figure, shadow casters, wireframe, socket items) draws with the
 * mob's base matrix - as {@code PlayerBodyAnimator} does for player bodies.
 *
 * <p><b>Inputs</b> (all replicated, so a client shadow poses as the server's mob moves): the ground speed (from the
 * interpolated displacement) and the behaviour state. The speed drives the graph's gait: the mob's own walking pace
 * ({@link MobGraphSpec#walkSpeed} x its move speed) is the graph's walk point, its running pace the run point, slower
 * scales down from the walk point toward standing; {@code gaitRate} plays the gait clips at the rate the real ground
 * speed needs (speed / the clips' speed on this body), so the planted feet stay planted at any pace. A behaviour state
 * with a montage (an attack) plays it into the graph's montage slot from the state's start at rate 1 - the state lasts
 * the clip's length, as the named clip did - and stops it if the state ends first.
 *
 * <p>Thread-confined: the thread that updates it (the client's entity update). Close it when the mob goes.
 */
public final class MobBodyAnimator implements AutoCloseable {

    /** Montage blends, seconds (the named clips used to cut). */
    static final float MONTAGE_BLEND_IN = 0.08f;
    static final float MONTAGE_BLEND_OUT = 0.12f;
    /** Gait play-rate bounds (a mob shuffling off from standing, a mob outrunning its run clip). */
    static final float MIN_GAIT_RATE = 0.1f;
    static final float MAX_GAIT_RATE = 8f;

    private final MobAnimationSet set;
    private final Retargeter retargeter;
    private final AnimGraphInstance instance;
    private final Map<MobBehaviorState, Integer> montageIds = new EnumMap<>(MobBehaviorState.class);
    private final JointPalette palette = new JointPalette();

    private MobBehaviorState state;
    private boolean montagePlaying;
    private boolean posed;
    private float lastSpeedParam, lastGaitRate;

    public MobBodyAnimator(MobAnimationSet set) {
        this.set = set;
        this.retargeter = set.newRetargeter();
        this.instance = set.newInstance(retargeter, montageIds);
    }

    /**
     * Advances the body by {@code dt} seconds.
     *
     * @param groundSpeed horizontal speed, blocks per second
     * @param moveSpeed   the mob's base move speed, blocks per second (its walk/run paces are multiples of it)
     * @param bodyScale   the mob's model scale (model units -> blocks)
     */
    public void update(float dt, float groundSpeed, float moveSpeed, float bodyScale, MobBehaviorState behavior,
                       boolean inWater) {
        AnimGraphInstance g = instance;
        float speed = Float.isFinite(groundSpeed) ? Math.max(0f, groundSpeed) : 0f;
        float param = speedParam(speed, moveSpeed);
        if (set.speed >= 0) g.setFloat(set.speed, param);
        if (set.gaitRate >= 0) g.setFloat(set.gaitRate, lastGaitRate = gaitRate(speed, param, bodyScale));
        if (set.grounded >= 0) g.setBool(set.grounded, true);  // shadows carry no ground contact; mobs hop rarely
        if (set.inWater >= 0) g.setBool(set.inWater, inWater);
        lastSpeedParam = param;
        montage(behavior);
        g.evaluate(Float.isFinite(dt) && dt > 0f ? dt : 0f, null, palette);
        posed = true;
    }

    /** The graph speed for a ground speed: walk pace -> walk point, run pace -> run point, linear between and below. */
    float speedParam(float speed, float moveSpeed) {
        float walk = Math.max(1e-3f, moveSpeed * set.spec().walkSpeed());
        float run = Math.max(walk + 1e-3f, moveSpeed * set.spec().runSpeed());
        if (speed <= walk) return set.walkPoint * speed / walk;
        float t = Math.min(1f, (speed - walk) / (run - walk));
        return set.walkPoint + (set.runPoint - set.walkPoint) * t;
    }

    /** Play rate that makes the gait blend at {@code param} cover {@code speed} blocks/s with planted feet. */
    float gaitRate(float speed, float param, float bodyScale) {
        float t = set.runPoint > set.walkPoint
                ? Math.clamp((param - set.walkPoint) / (set.runPoint - set.walkPoint), 0f, 1f) : 0f;
        float natural = (set.walkNatural + (set.runNatural - set.walkNatural) * t) * bodyScale;
        if (!(natural > 1e-5f)) return 1f;
        return Math.clamp(speed / natural, MIN_GAIT_RATE, MAX_GAIT_RATE);
    }

    private void montage(MobBehaviorState behavior) {
        if (behavior == state) return;
        state = behavior;
        if (set.montageSlot < 0) return;
        Integer id = behavior == null ? null : montageIds.get(behavior);
        if (id != null) {
            instance.playMontage(set.montageSlot, id, 1f, MONTAGE_BLEND_IN, MONTAGE_BLEND_OUT, null);
            montagePlaying = true;
        } else if (montagePlaying) {
            // A montage that ran to its end has already left the slot (the state outlived the clip).
            if (instance.montageStatus(set.montageSlot).montage() >= 0) {
                instance.stopMontage(set.montageSlot, MONTAGE_BLEND_OUT);
            }
            montagePlaying = false;
        }
    }

    /** The last evaluated pose, model space (draws supply the base matrix). */
    public JointPalette palette() { return palette; }

    /** Whether the body has been posed at least once. */
    public boolean posed() { return posed; }

    public MobAnimationSet set() { return set; }

    /** The graph instance (debugging, tests). */
    public AnimGraphInstance instance() { return instance; }

    /** The last speed parameter and gait rate handed to the graph (debugging, tests). */
    public float lastSpeedParam() { return lastSpeedParam; }

    public float lastGaitRate() { return lastGaitRate; }

    @Override
    public void close() {
        instance.close();
        if (retargeter != null) retargeter.close();
    }
}
