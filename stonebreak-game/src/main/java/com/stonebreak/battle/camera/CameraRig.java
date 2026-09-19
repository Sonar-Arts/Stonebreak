package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.camera.ShotSequence.AdvanceOn;
import com.stonebreak.battle.camera.ShotSequence.Step;
import com.stonebreak.battle.camera.ShotSequence.Transition;
import org.joml.Vector3f;

/**
 * Plays one {@link ShotSequence}: tracks the live step and its clock, advances on time or on a
 * signalled event, and blends from whatever was last on screen when a step asks for a BLEND.
 * The blend source is a frozen copy of the last output, so output is continuous whatever was
 * interrupted.
 */
public final class CameraRig {

    /**
     * A blend that would have to average more than this many blocks per second becomes a cut: a
     * 0.4 s "push-in" from the far side of the arena is a whip-pan through the scenery, not a push-in.
     */
    public static final float MAX_BLEND_SPEED = 28f;

    private final BattleStageLayout layout;
    private StagePoses lastPoses = StagePoses.HOME;

    private ShotSequence sequence;
    private int stepIndex;
    private float stepTime;
    private boolean finished;

    private CameraFrame lastFrame;
    private CameraFrame blendFrom;
    private float blendElapsed;
    private float blendSeconds;
    private boolean cutThisFrame;
    private float shakeBeat;

    public CameraRig(BattleStageLayout layout, ShotSequence initial) {
        this.layout = layout;
        play(initial, Transition.CUT);
        cutThisFrame = false;
    }

    /** Starts a sequence. {@code entryOverride} replaces the sequence's own entry transition when non-null. */
    public void play(ShotSequence next, Transition entryOverride) {
        sequence = next;
        stepIndex = 0;
        finished = false;
        enter(entryOverride != null ? entryOverride : next.entry());
    }

    /**
     * Advances the clocks. Call once per frame before any {@link #play}/{@link #signal}.
     *
     * @param allowAdvance false while a timing prompt is open: a time-based step change is a cut too
     */
    public void tick(float dt, boolean allowAdvance) {
        cutThisFrame = false;
        float beatAt = liveShot().shakeAtSeconds();
        if (beatAt >= 0f && stepTime < beatAt && stepTime + dt >= beatAt) shakeBeat = liveShot().shakeTrauma();
        stepTime += dt;
        if (blendFrom != null) blendElapsed += dt;
        if (allowAdvance && !finished && step().advanceOn() == AdvanceOn.TIME && stepTime >= step().shot().duration()) {
            advance();
        }
    }

    /** Reports an event; advances when the live step is waiting for it. Returns true if it did. */
    public boolean signal(AdvanceOn event) {
        if (finished || step().advanceOn() != event) return false;
        advance();
        return true;
    }

    private void advance() {
        if (stepIndex + 1 >= sequence.steps().size()) {
            finished = true; // keep showing the last shot (held / looping) until the director replaces it
            return;
        }
        stepIndex++;
        enter(step().entry());
    }

    private void enter(Transition transition) {
        stepTime = 0f;
        if (transition.blend() && transition.seconds() > 0f && lastFrame != null && !tooFarToBlend(transition.seconds())) {
            blendFrom = lastFrame;
            blendElapsed = 0f;
            blendSeconds = transition.seconds();
        } else {
            blendFrom = null;
            cutThisFrame = true;
        }
    }

    private boolean tooFarToBlend(float seconds) {
        CameraFrame destination = ShotEvaluator.evaluate(liveShot(), 0f, layout, lastPoses);
        return destination.eye().distance(lastFrame.eye()) / seconds > MAX_BLEND_SPEED;
    }

    /** The frame for the current clocks (before shake). */
    public CameraFrame evaluate(StagePoses poses) {
        lastPoses = poses;
        CameraFrame frame = ShotEvaluator.evaluate(liveShot(), stepTime, layout, poses);
        if (blendFrom != null) {
            float t = blendElapsed / blendSeconds;
            if (t >= 1f) {
                blendFrom = null;
            } else {
                float w = t * t * (3f - 2f * t);
                frame = ShotEvaluator.sanitize(
                        new Vector3f(blendFrom.eye()).lerp(frame.eye(), w),
                        new Vector3f(blendFrom.target()).lerp(frame.target(), w),
                        blendFrom.rollDeg() + (frame.rollDeg() - blendFrom.rollDeg()) * w,
                        blendFrom.fovDeg() + (frame.fovDeg() - blendFrom.fovDeg()) * w, layout);
            }
        }
        lastFrame = frame;
        return frame;
    }

    /** Trauma of a shot-authored shake beat reached since the last call (0 when none); clears it. */
    public float takeShakeBeat() {
        float beat = shakeBeat;
        shakeBeat = 0f;
        return beat;
    }

    /** Battle time scale the live shot asks for right now. */
    public float timeScale() {
        CameraShot shot = liveShot();
        return stepTime < shot.timeScaleSeconds() ? shot.timeScale() : 1f;
    }

    private Step step() { return sequence.steps().get(stepIndex); }

    public CameraShot liveShot() { return step().shot(); }
    public ShotSequence sequence() { return sequence; }
    public int stepIndex() { return stepIndex; }
    public float stepTime() { return stepTime; }
    /** True once the last step has advanced (the last shot keeps showing). */
    public boolean finished() { return finished; }
    public boolean blending() { return blendFrom != null; }
    /** True when a step was entered by a CUT since the last {@link #tick}. */
    public boolean cutThisFrame() { return cutThisFrame; }
}
