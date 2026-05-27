package com.stonebreak.ui.mainMenu;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;

/**
 * Drives the hidden title interaction on the main menu: a first click shakes
 * the logo, a second click slams it into the background, firing a shockwave
 * that wipes the dirt away to reveal the procedural space scene. Holds all
 * animation state and exposes per-frame values the renderer reads; advanced
 * once per frame from {@link com.stonebreak.ui.MainMenu#render}.
 */
public final class MainMenuStage {

    /** What the renderer should draw behind the menu this frame. */
    public enum BackgroundMode { DIRT, REVEALING, SPACE }

    private enum Phase { IDLE, SHAKE, ARMED, SLAM, SPACE }

    // First-click shake.
    private static final float SHAKE_DURATION = 0.35f;
    private static final float SHAKE_AMPLITUDE = 6f;
    private static final float SHAKE_FREQUENCY = 42f;

    // Second-click slam: pop toward the viewer, snap back past rest (impact),
    // then settle. Impact fires at the end of the snap-back.
    private static final float SLAM_POP_DURATION = 0.16f;
    private static final float SLAM_HIT_DURATION = 0.10f;
    private static final float SLAM_SETTLE_DURATION = 0.26f;
    private static final float SLAM_POP_SCALE = 1.22f;
    private static final float SLAM_SQUASH_SCALE = 0.86f;
    private static final float SLAM_IMPACT_TIME = SLAM_POP_DURATION + SLAM_HIT_DURATION;
    private static final float SLAM_TOTAL = SLAM_IMPACT_TIME + SLAM_SETTLE_DURATION;

    // Shockwave ring that erases the dirt.
    private static final float SHOCKWAVE_DURATION = 0.70f;

    // Full-screen shake fired at impact.
    private static final float SCREEN_SHAKE_DURATION = 0.45f;
    private static final float SCREEN_SHAKE_AMPLITUDE = 11f;
    private static final float SCREEN_SHAKE_FREQUENCY = 58f;

    // Idle sway once the space scene is showing.
    private static final float TILT_MAX_DEGREES = 4f;
    private static final float TILT_PERIOD = 6f;

    private Phase phase = Phase.IDLE;

    private float shakeElapsed;
    private float slamElapsed;
    private boolean impactFired;

    private float pendingCenterX;
    private float pendingCenterY;

    private boolean shockwaveActive;
    private float shockwaveElapsed;
    private float shockwaveCenterX;
    private float shockwaveCenterY;
    private float shockwaveMaxRadius;

    private boolean screenShakeActive;
    private float screenShakeElapsed;

    private float tiltClock;

    // Per-frame outputs, refreshed by update().
    private float titleOffsetX;
    private float titleOffsetY;
    private float titleScale = 1f;
    private float titleRotationDeg;
    private float screenShakeX;
    private float screenShakeY;

    /**
     * Registers a click on the title. The first click starts the shake; once
     * the shake has settled, the next click starts the slam and records the
     * shockwave origin (the title's centre).
     *
     * @param titleCenterX title centre x in screen pixels
     * @param titleCenterY title centre y in screen pixels
     */
    public void onTitleClick(float titleCenterX, float titleCenterY) {
        switch (phase) {
            case IDLE -> {
                phase = Phase.SHAKE;
                shakeElapsed = 0f;
            }
            case ARMED -> {
                phase = Phase.SLAM;
                slamElapsed = 0f;
                impactFired = false;
                pendingCenterX = titleCenterX;
                pendingCenterY = titleCenterY;
            }
            default -> {
                // Clicks during the shake, slam, or space phases are ignored.
            }
        }
    }

    /** Advances all animation timers and recomputes per-frame outputs. */
    public void update(float deltaTime, int screenWidth, int screenHeight, float uiScale) {
        titleOffsetX = 0f;
        titleOffsetY = 0f;
        titleScale = 1f;
        titleRotationDeg = 0f;

        updateShake(deltaTime, uiScale);
        updateSlam(deltaTime, screenWidth, screenHeight);
        updateShockwave(deltaTime);
        updateScreenShake(deltaTime, uiScale);
        updateTilt(deltaTime);
    }

    private void updateShake(float deltaTime, float uiScale) {
        if (phase != Phase.SHAKE) {
            return;
        }
        shakeElapsed += deltaTime;
        if (shakeElapsed >= SHAKE_DURATION) {
            phase = Phase.ARMED;
            return;
        }
        float decay = 1f - shakeElapsed / SHAKE_DURATION;
        float amplitude = SHAKE_AMPLITUDE * uiScale * decay;
        titleOffsetX = amplitude * (float) Math.sin(shakeElapsed * SHAKE_FREQUENCY);
        titleOffsetY = amplitude * 0.35f * (float) Math.sin(shakeElapsed * SHAKE_FREQUENCY * 1.7f);
    }

    private void updateSlam(float deltaTime, int screenWidth, int screenHeight) {
        if (phase != Phase.SLAM) {
            return;
        }
        slamElapsed += deltaTime;

        if (slamElapsed < SLAM_POP_DURATION) {
            float k = EasingFunctions.apply(slamElapsed / SLAM_POP_DURATION, EasingType.EaseOutCubic);
            titleScale = lerp(1f, SLAM_POP_SCALE, k);
        } else if (slamElapsed < SLAM_IMPACT_TIME) {
            float k = EasingFunctions.apply(
                    (slamElapsed - SLAM_POP_DURATION) / SLAM_HIT_DURATION, EasingType.EaseInCubic);
            titleScale = lerp(SLAM_POP_SCALE, SLAM_SQUASH_SCALE, k);
        } else if (slamElapsed < SLAM_TOTAL) {
            if (!impactFired) {
                fireImpact(screenWidth, screenHeight);
            }
            float k = EasingFunctions.apply(
                    (slamElapsed - SLAM_IMPACT_TIME) / SLAM_SETTLE_DURATION, EasingType.EaseOutBounce);
            titleScale = lerp(SLAM_SQUASH_SCALE, 1f, k);
        } else {
            if (!impactFired) {
                fireImpact(screenWidth, screenHeight);
            }
            titleScale = 1f;
            phase = Phase.SPACE;
            tiltClock = 0f;
        }
    }

    private void fireImpact(int screenWidth, int screenHeight) {
        impactFired = true;
        shockwaveActive = true;
        shockwaveElapsed = 0f;
        shockwaveCenterX = pendingCenterX;
        shockwaveCenterY = pendingCenterY;
        shockwaveMaxRadius = farthestCornerDistance(pendingCenterX, pendingCenterY,
                screenWidth, screenHeight) + 8f;
        screenShakeActive = true;
        screenShakeElapsed = 0f;
    }

    private void updateShockwave(float deltaTime) {
        if (!shockwaveActive) {
            return;
        }
        shockwaveElapsed += deltaTime;
        if (shockwaveElapsed >= SHOCKWAVE_DURATION) {
            shockwaveActive = false;
        }
    }

    private void updateScreenShake(float deltaTime, float uiScale) {
        if (!screenShakeActive) {
            return;
        }
        screenShakeElapsed += deltaTime;
        if (screenShakeElapsed >= SCREEN_SHAKE_DURATION) {
            screenShakeActive = false;
            return;
        }
        float decay = 1f - screenShakeElapsed / SCREEN_SHAKE_DURATION;
        float amplitude = SCREEN_SHAKE_AMPLITUDE * uiScale * decay * decay;
        screenShakeX = amplitude * (float) Math.sin(screenShakeElapsed * SCREEN_SHAKE_FREQUENCY);
        screenShakeY = amplitude * 0.8f
                * (float) Math.cos(screenShakeElapsed * SCREEN_SHAKE_FREQUENCY * 1.3f);
    }

    private void updateTilt(float deltaTime) {
        if (phase != Phase.SPACE) {
            return;
        }
        tiltClock += deltaTime;
        titleRotationDeg = TILT_MAX_DEGREES
                * (float) Math.sin(tiltClock * (2.0 * Math.PI / TILT_PERIOD));
    }

    /** Resets the interaction back to the dirt-background idle state. */
    public void reset() {
        phase = Phase.IDLE;
        shakeElapsed = 0f;
        slamElapsed = 0f;
        impactFired = false;
        shockwaveActive = false;
        shockwaveElapsed = 0f;
        screenShakeActive = false;
        screenShakeElapsed = 0f;
        tiltClock = 0f;
        titleOffsetX = 0f;
        titleOffsetY = 0f;
        titleScale = 1f;
        titleRotationDeg = 0f;
        screenShakeX = 0f;
        screenShakeY = 0f;
    }

    public BackgroundMode getBackgroundMode() {
        if (!impactFired) {
            return BackgroundMode.DIRT;
        }
        return shockwaveActive ? BackgroundMode.REVEALING : BackgroundMode.SPACE;
    }

    public float getTitleOffsetX() {
        return titleOffsetX;
    }

    public float getTitleOffsetY() {
        return titleOffsetY;
    }

    public float getTitleScale() {
        return titleScale;
    }

    public float getTitleRotationDeg() {
        return titleRotationDeg;
    }

    public float getScreenShakeX() {
        return screenShakeX;
    }

    public float getScreenShakeY() {
        return screenShakeY;
    }

    public boolean isShockwaveActive() {
        return shockwaveActive;
    }

    public float getShockwaveCenterX() {
        return shockwaveCenterX;
    }

    public float getShockwaveCenterY() {
        return shockwaveCenterY;
    }

    public float getShockwaveRadius() {
        float progress = Math.min(1f, shockwaveElapsed / SHOCKWAVE_DURATION);
        return shockwaveMaxRadius * EasingFunctions.apply(progress, EasingType.EaseOutQuad);
    }

    /** Ring opacity in [0, 1]: solid through the first half, fading out after. */
    public float getShockwaveAlpha() {
        float progress = Math.min(1f, shockwaveElapsed / SHOCKWAVE_DURATION);
        if (progress < 0.5f) {
            return 1f;
        }
        return 1f - (progress - 0.5f) / 0.5f;
    }

    private static float farthestCornerDistance(float x, float y, int w, int h) {
        float dx = Math.max(x, w - x);
        float dy = Math.max(y, h - y);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
