package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleEvent;
import org.joml.Vector3f;

/**
 * Trauma-based shake: events add trauma (0..1), trauma decays linearly, and the offset scales with
 * trauma² so small hits barely register while big ones kick. Offsets come from smooth value noise
 * (seeded, so replays match) and are added on top of whatever shot is live. A separate FOV punch
 * gives parries and crits a short zoom snap.
 */
public final class CameraShake {

    public static final float TRAUMA_DECAY_PER_SECOND = 1.5f;
    public static final float MAX_EYE_OFFSET = 0.30f;
    public static final float MAX_TARGET_OFFSET = 0.10f;
    public static final float MAX_ROLL_DEG = 1.6f;
    public static final float MAX_FOV_PUNCH_DEG = 9f;
    private static final float NOISE_HZ = 13f;
    private static final float PUNCH_DECAY_PER_SECOND = 9f;

    private final long seed;
    private float trauma;
    private float fovPunch;
    private float time;

    public CameraShake(long seed) {
        this.seed = seed;
    }

    /** Maps battle events to trauma and FOV punch. */
    public void onEvent(BattleEvent event) {
        switch (event) {
            case BattleEvent.DamageDealt d -> {
                float base = Math.max(0.18f, Math.min(0.55f, 0.18f + Math.max(0f, d.amount()) / 120f));
                switch (d.flavor()) {
                    case NORMAL -> addTrauma(base);
                    case CRITICAL -> { addTrauma(base * 1.6f); punchFov(-4f); }
                    case BLOCKED -> addTrauma(0.12f);
                    case PARRIED -> { trauma = Math.max(trauma, 0.5f); punchFov(-7f); } // sharp snap, not a rumble
                }
            }
            case BattleEvent.ComboFinished c -> {
                if (c.flawless()) { trauma = 1f; punchFov(-8f); } else { addTrauma(0.4f); }
            }
            default -> { }
        }
    }

    public void addTrauma(float amount) {
        if (Float.isFinite(amount)) trauma = Math.max(0f, Math.min(1f, trauma + amount));
    }

    /** Negative = zoom in. */
    public void punchFov(float degrees) {
        if (!Float.isFinite(degrees)) return;
        fovPunch = Math.max(-MAX_FOV_PUNCH_DEG, Math.min(MAX_FOV_PUNCH_DEG, fovPunch + degrees));
    }

    public void update(float dt) {
        time += dt;
        trauma = Math.max(0f, trauma - TRAUMA_DECAY_PER_SECOND * dt);
        fovPunch *= (float) Math.exp(-PUNCH_DECAY_PER_SECOND * dt);
        if (Math.abs(fovPunch) < 0.01f) fovPunch = 0f;
    }

    public void reset() {
        trauma = 0f;
        fovPunch = 0f;
    }

    public float trauma() { return trauma; }
    public boolean active() { return trauma > 0f || fovPunch != 0f; }

    public Vector3f eyeOffset(float scale) {
        float a = trauma * trauma * MAX_EYE_OFFSET * scale;
        return new Vector3f(noise(0) * a, noise(1) * a, noise(2) * a);
    }

    public Vector3f targetOffset(float scale) {
        float a = trauma * trauma * MAX_TARGET_OFFSET * scale;
        return new Vector3f(noise(3) * a, noise(4) * a, noise(5) * a);
    }

    public float rollOffset(float scale) { return trauma * trauma * MAX_ROLL_DEG * scale * noise(6); }

    public float fovOffset(float scale) { return fovPunch * scale; }

    /** Adds the current shake to a frame. {@code scale} damps it (timing prompts want a steadier picture). */
    public CameraFrame apply(CameraFrame frame, float scale) {
        if (!active() || scale <= 0f) return frame;
        float fov = Math.max(CameraShot.MIN_FOV, Math.min(CameraShot.MAX_FOV, frame.fovDeg() + fovOffset(scale)));
        return new CameraFrame(new Vector3f(frame.eye()).add(eyeOffset(scale)),
                new Vector3f(frame.target()).add(targetOffset(scale)), frame.rollDeg() + rollOffset(scale), fov);
    }

    /** Smooth value noise in [-1, 1] for a channel at the current time. */
    private float noise(int channel) {
        float x = time * NOISE_HZ + channel * 37.13f;
        int i = (int) Math.floor(x);
        float f = x - i;
        float w = f * f * (3f - 2f * f);
        return lattice(i, channel) * (1f - w) + lattice(i + 1, channel) * w;
    }

    private float lattice(int i, int channel) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ (channel * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return ((h >>> 40) / (float) (1 << 24)) * 2f - 1f;
    }
}
