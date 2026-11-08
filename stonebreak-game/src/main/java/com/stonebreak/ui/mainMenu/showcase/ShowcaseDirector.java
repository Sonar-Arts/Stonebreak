package com.stonebreak.ui.mainMenu.showcase;

import com.stonebreak.battle.camera.CameraFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Cuts the main menu's live backdrop between its {@link FlyoverShot}s with a dip to black, the way
 * Just Cause 3's menu does: hold black, move the camera, wait until the new area has drawn, fade in,
 * fly the shot, fade out, repeat. A pure state machine over a {@link Stage} seam (camera, readiness,
 * time and weather), so it runs and tests without a world or GL.
 *
 * <pre>
 * MOVE ─▶ WAIT (black until the area is drawn, or {@link #READY_TIMEOUT}) ─▶ FADE_IN ─▶ PLAY ─▶ FADE_OUT ─┐
 *  ▲                                                                                                     │
 *  └─────────────────────────────────────────── next shot ◀─────────────────────────────────────────────┘
 * </pre>
 *
 * The shot's own clock runs from the start of FADE_IN to the end of FADE_OUT (the camera keeps
 * moving through both fades); during WAIT it holds the shot's first frame. A {@linkplain
 * FlyoverShot#loop looping} shot never leaves PLAY: one continuous take, faded in once.
 */
public final class ShowcaseDirector {

    /** What the director drives. */
    public interface Stage {
        /** Places the camera (and so the streaming centre) at this frame. */
        void moveCamera(CameraFrame frame);

        /** True once the area around {@code frame}'s eye has drawn enough to be shown. */
        boolean areaReady(CameraFrame frame);

        /** Sets the time of day / weather while the screen is black (either may be null: unchanged). */
        void setConditions(Long timeTicks, String weather);

        /** How far the shot must rise to clear this world's terrain ({@link FlyoverShot#liftAbove}). */
        float liftFor(FlyoverShot shot);
    }

    public enum Phase { MOVE, WAIT, FADE_IN, PLAY, FADE_OUT }

    public static final float FADE_IN_SECONDS = 1.2f;
    public static final float FADE_OUT_SECONDS = 1.0f;
    /** The shortest black between shots, even when the next area is already drawn. */
    public static final float MIN_BLACK_SECONDS = 0.35f;
    /** The longest the director waits on an area before fading in regardless. */
    public static final float READY_TIMEOUT = 8f;

    private final List<FlyoverShot> shots;
    private final Stage stage;
    private final Random random;
    private final int pinnedShot;

    private final List<Integer> order = new ArrayList<>();
    private int orderIndex = -1;
    private int current = -1;
    private Phase phase = Phase.MOVE;
    private float phaseTime;
    private float shotTime;
    private float lift;
    private CameraFrame frame;

    /**
     * @param seed       orders the shots (a deterministic shuffle per cycle, never one shot twice in a row)
     * @param pinnedShot a shot index to loop forever (dev: {@code -Dstonebreak.showcase.shot}), or -1
     */
    public ShowcaseDirector(List<FlyoverShot> shots, Stage stage, long seed, int pinnedShot) {
        if (shots.isEmpty()) {
            throw new IllegalArgumentException("no shots");
        }
        this.shots = List.copyOf(shots);
        this.stage = stage;
        this.random = new Random(seed);
        this.pinnedShot = pinnedShot >= 0 && pinnedShot < shots.size() ? pinnedShot : -1;
    }

    /** Advances {@code dt} seconds. */
    public void tick(float dt) {
        if (!(dt > 0f) || !Float.isFinite(dt)) {
            dt = 0f;
        }
        dt = Math.min(dt, 0.25f); // a hitch must not skip a fade
        phaseTime += dt;
        switch (phase) {
            case MOVE -> beginShot();
            case WAIT -> {
                stage.moveCamera(frame);
                if (phaseTime >= MIN_BLACK_SECONDS && (stage.areaReady(frame) || phaseTime >= READY_TIMEOUT)) {
                    enter(Phase.FADE_IN);
                }
            }
            case FADE_IN, PLAY, FADE_OUT -> {
                shotTime += dt;
                FlyoverShot shot = shots.get(current);
                frame = shot.evaluate(shotTime, lift);
                stage.moveCamera(frame);
                if (phase == Phase.FADE_IN && phaseTime >= FADE_IN_SECONDS) {
                    enter(Phase.PLAY);
                } else if (phase == Phase.PLAY && !shot.loop() && shotTime >= shot.duration() - FADE_OUT_SECONDS) {
                    enter(Phase.FADE_OUT);
                } else if (phase == Phase.FADE_OUT && phaseTime >= FADE_OUT_SECONDS) {
                    enter(Phase.MOVE);
                }
            }
        }
    }

    private void beginShot() {
        current = nextShot();
        FlyoverShot shot = shots.get(current);
        stage.setConditions(shot.timeTicks(), shot.weather());
        lift = Math.max(0f, stage.liftFor(shot));
        shotTime = 0f;
        frame = shot.evaluate(0f, lift);
        stage.moveCamera(frame);
        enter(Phase.WAIT);
    }

    private int nextShot() {
        if (pinnedShot >= 0) {
            return pinnedShot;
        }
        if (shots.size() == 1) {
            return 0;
        }
        if (++orderIndex >= order.size()) {
            order.clear();
            for (int i = 0; i < shots.size(); i++) {
                order.add(i);
            }
            Collections.shuffle(order, random);
            if (order.getFirst() == current) {
                Collections.swap(order, 0, order.size() - 1); // never the same shot twice in a row
            }
            orderIndex = 0;
        }
        return order.get(orderIndex);
    }

    private void enter(Phase next) {
        phase = next;
        phaseTime = 0f;
    }

    /** How black the screen is, 0 (the world) .. 1 (black). */
    public float fade() {
        return switch (phase) {
            case MOVE, WAIT -> 1f;
            case FADE_IN -> 1f - smooth(phaseTime / FADE_IN_SECONDS);
            case PLAY -> 0f;
            case FADE_OUT -> smooth(phaseTime / FADE_OUT_SECONDS);
        };
    }

    /** The playing shot's caption, or "" while black. */
    public String caption() {
        return current < 0 || phase == Phase.MOVE || phase == Phase.WAIT ? "" : shots.get(current).caption();
    }

    public Phase phase() {
        return phase;
    }

    /** Seconds into the current shot (0 while waiting on it). */
    public float shotTime() {
        return shotTime;
    }

    /** The shot index being shown (or waited on), -1 before the first. */
    public int currentShot() {
        return current;
    }

    /** The camera frame last handed to the stage, or null before the first tick. */
    public CameraFrame frame() {
        return frame;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
