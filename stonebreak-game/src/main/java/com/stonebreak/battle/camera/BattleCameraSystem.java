package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade over the cinematic camera: {@link CameraDirector} (what to show) + {@link CameraRig} (how
 * it moves) + {@link CameraShake}. Produces one {@link CameraFrame} per update for the player
 * Camera's cinematic override and the projection FOV seam. GL-free.
 */
public final class BattleCameraSystem {

    /** Longest step the camera integrates in one update (a hitch must not skip a whole shot). */
    private static final float MAX_DT = 0.25f;
    /** Shake is damped, not removed, while a timing prompt is open: hits should still register. */
    private static final float PROMPT_SHAKE_SCALE = 0.35f;

    private final BattleStageLayout layout;
    private final boolean staticOnly;
    private final ShotLibrary library;
    private final CameraRig rig;
    private final CameraDirector director;
    private final CameraShake shake;
    private CameraFrame frame;

    /**
     * @param seed       seeds variant choice, idle cadence and shake noise (never the battle's Random)
     * @param staticOnly reduced-motion mode: pin the WIDE shot, no cuts, no shake
     */
    public BattleCameraSystem(BattleStageLayout layout, long seed, boolean staticOnly) {
        this.layout = layout;
        this.staticOnly = staticOnly;
        this.library = ShotLibrary.standard();
        this.rig = new CameraRig(layout, library.wide());
        this.director = new CameraDirector(library, new ShotValidator(layout), rig, seed, staticOnly);
        this.shake = new CameraShake(seed ^ 0x5DEECE66DL);
        this.frame = rig.evaluate(StagePoses.HOME);
    }

    /**
     * Advance the camera. Call once per frame after the battle model's update, with the
     * <em>unscaled</em> frame dt (slow motion slows the battle, not the camera).
     */
    public void update(BattleView view, float dt) {
        float step = Float.isFinite(dt) && dt > 0f ? Math.min(dt, MAX_DT) : 0f;
        if (view == null) {
            rig.tick(step, true);
            frame = rig.evaluate(StagePoses.HOME);
            return;
        }
        director.update(view, step);
        CameraFrame base = rig.evaluate(StagePoses.of(view));
        float beat = rig.takeShakeBeat();
        if (staticOnly) {
            frame = base;
            return;
        }
        shake.addTrauma(beat);
        for (BattleEvent event : view.frameEvents()) {
            shake.onEvent(event);
        }
        shake.update(step);
        frame = aboveFloor(shake.apply(base, view.promptSafeRequired() ? PROMPT_SHAKE_SCALE : 1f));
    }

    private CameraFrame aboveFloor(CameraFrame shaken) {
        float minY = layout.floorY() + ShotValidator.FLOOR_CLEARANCE;
        if (!shaken.eye().isFinite() || !shaken.target().isFinite()) return frame;
        if (shaken.eye().y >= minY) return shaken;
        return new CameraFrame(new Vector3f(shaken.eye().x, minY, shaken.eye().z), shaken.target(),
                shaken.rollDeg(), shaken.fovDeg());
    }

    /** The frame to show right now. Never null, always finite. */
    public CameraFrame frame() {
        return frame;
    }

    /** Skip the intro sequence (any confirm press). */
    public void skipIntro() {
        director.skipIntro();
    }

    /** True once the intro sequence has finished or was skipped. */
    public boolean introFinished() {
        return director.introFinished();
    }

    /**
     * Battle time scale requested by the live shot: 1 normally, below 1 during a slow-motion beat.
     * The coordinator multiplies the battle model's dt by this.
     */
    public float timeScale() {
        if (staticOnly) return 1f;
        float scale = rig.timeScale();
        return Float.isFinite(scale) && scale > 0f ? Math.min(1f, scale) : 1f;
    }

    /** Name of the live shot, for the debug overlay. */
    public String shotName() {
        return rig.liveShot().name();
    }

    /** Name of the live sequence (a sequence is one or more shots). */
    public String sequenceName() {
        return rig.sequence().name();
    }

    /** The situation the director is covering. */
    public Situation situation() {
        return director.situation();
    }

    /** True when this update entered a shot by a hard cut (HUD floaters may want to know). */
    public boolean cutThisFrame() {
        return rig.cutThisFrame();
    }

    /** True while blending between two shots. */
    public boolean blending() {
        return rig.blending();
    }

    /** Current shake trauma, 0..1. */
    public float shakeTrauma() {
        return shake.trauma();
    }

    /** Names of every authored sequence, for a debug "step through the shots" mode. */
    public List<String> sequenceNames() {
        List<String> names = new ArrayList<>();
        library.all().forEach(s -> names.add(s.name()));
        return names;
    }

    /** Debug: hold one library sequence regardless of the battle; null or an unknown name releases it. */
    public void debugPinSequence(String sequenceName) {
        director.pin(sequenceName == null ? null : library.byName(sequenceName));
    }
}
