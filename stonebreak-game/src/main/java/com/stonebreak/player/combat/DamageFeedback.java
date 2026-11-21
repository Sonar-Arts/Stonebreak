package com.stonebreak.player.combat;

/**
 * How getting hurt <em>feels</em>: a red screen-edge flash, a heartbeat vignette while health is low,
 * and a camera jolt. GL-free and allocation-free; the player pipeline feeds it the health once per frame
 * and reads the results back.
 *
 * <p>It watches the health instead of being told about each hit, so every source counts the same way:
 * combat, falls, cactus, drowning and the server-owned health of a session ({@code PlayerHealthS2C}
 * lands in {@link HealthController} and shows up here as a drop). A rise (healing, respawn) only
 * resets the baseline.
 *
 * <p><b>Shake</b> follows the "trauma" model: each hit adds trauma (0..1), which decays linearly, and the
 * camera jitters by {@code trauma²} times smooth value noise per axis, so small hits barely move it and
 * big ones jolt. On top sits a short hurt tilt (roll that alternates side per hit, plus a downward pitch
 * nod). All angles are degrees and are meant for the view matrix only, never for aiming.
 *
 * <p><b>Vignette</b> values are 0..1 intensities for the HUD document ({@code hurt} root of the
 * {@code stonebreak:hud} contract): {@link #flash()} spikes on a hit and fades over
 * {@value #FLASH_SECONDS} s, {@link #lowHealth()} beats like a pulse below {@value #LOW_HEALTH_START} of
 * max health, and {@link #vignette()} is the two combined for a single overlay.
 */
public final class DamageFeedback {

    /** A single hit of this share of max health (or more) is a "full" hit. */
    static final float FULL_HIT_FRACTION = 0.3f;
    /** Seconds a full flash takes to fade out. */
    static final float FLASH_SECONDS = 0.65f;
    /** Trauma lost per second. */
    static final float TRAUMA_DECAY = 1.4f;
    /** Seconds the hurt tilt lasts. */
    static final float KICK_SECONDS = 0.35f;
    /** Below this share of max health the low-health pulse starts. */
    static final float LOW_HEALTH_START = 0.3f;

    // Shake amplitudes (degrees) at full trauma, and the jitter's noise rate (lattice steps per second).
    static final float MAX_YAW = 1.6f;
    static final float MAX_PITCH = 1.6f;
    static final float MAX_ROLL = 2.4f;
    static final float NOISE_RATE = 16f;
    // The hurt tilt: roll to one side and a slight downward nod.
    static final float KICK_ROLL = 4.5f;
    static final float KICK_PITCH = -1.8f;

    private boolean primed;
    private float lastHealth;

    private float flash;
    private float trauma;
    private float kick;
    private float kickSign = 1f;
    private float low;
    private float lowPulse;
    private float beatPhase;
    private float time;

    private float shakeYaw;
    private float shakePitch;
    private float shakeRoll;

    /**
     * Advances one frame.
     *
     * @param dt        seconds since the last frame
     * @param health    current health
     * @param maxHealth current max health
     * @param dead      whether the player is dead (no low-health pulse while dead)
     * @param motion    false under reduced motion: the shake angles stay 0 (the vignette is unaffected)
     */
    public void update(float dt, float health, float maxHealth, boolean dead, boolean motion) {
        if (!Float.isFinite(dt) || dt < 0f) {
            dt = 0f;
        }
        if (!primed) {
            primed = true;
            lastHealth = health;
        }
        float drop = lastHealth - health;
        if (drop > 0.001f && maxHealth > 0f) {
            hurt(drop / maxHealth);
        }
        lastHealth = health;

        flash = Math.max(0f, flash - dt / FLASH_SECONDS);
        trauma = Math.max(0f, trauma - dt * TRAUMA_DECAY);
        kick = Math.max(0f, kick - dt / KICK_SECONDS);

        float fraction = maxHealth > 0f ? health / maxHealth : 1f;
        float target = dead || fraction >= LOW_HEALTH_START ? 0f : smoothstep(1f - fraction / LOW_HEALTH_START);
        low += (target - low) * Math.min(1f, dt * 3f);
        if (low < 0.001f && target == 0f) {
            low = 0f;
        }
        // Heartbeat: faster as health runs out (0.9 Hz at the threshold, 1.8 Hz near zero).
        float hz = 0.9f + 0.9f * target;
        beatPhase = (beatPhase + dt * hz) % 1f;
        lowPulse = low == 0f ? 0f : low * (0.55f + 0.45f * heartbeat(beatPhase));

        time += dt;
        if (!motion || (trauma == 0f && kick == 0f)) {
            shakeYaw = shakePitch = shakeRoll = 0f;
            return;
        }
        float shake = trauma * trauma;
        float t = time * NOISE_RATE;
        float k = kick * kick; // ease-out: quick snap, soft settle
        shakeYaw = MAX_YAW * shake * noise(t, 11);
        shakePitch = MAX_PITCH * shake * noise(t, 37) + KICK_PITCH * k;
        shakeRoll = MAX_ROLL * shake * noise(t, 73) + KICK_ROLL * kickSign * k;
    }

    /** A hit of {@code fraction} of max health landed this frame. */
    void hurt(float fraction) {
        float severity = clamp01(fraction / FULL_HIT_FRACTION);
        flash = Math.max(flash, 0.45f + 0.55f * severity);
        trauma = Math.min(1f, trauma + 0.3f + 0.6f * severity);
        kick = 1f;
        kickSign = -kickSign;
    }

    /** Clears everything (world left, player replaced). The next update re-baselines the health. */
    public void reset() {
        primed = false;
        flash = trauma = kick = low = lowPulse = beatPhase = time = 0f;
        shakeYaw = shakePitch = shakeRoll = 0f;
    }

    /** The hit flash, 0..1. */
    public float flash() {
        return flash;
    }

    /** The low-health vignette with its heartbeat applied, 0..1. */
    public float lowHealth() {
        return lowPulse;
    }

    /** Flash and low-health pulse combined, 0..1: the one value a single overlay needs. */
    public float vignette() {
        return Math.max(flash, lowPulse);
    }

    /** Current trauma, 0..1. */
    public float trauma() {
        return trauma;
    }

    public float shakeYaw() {
        return shakeYaw;
    }

    public float shakePitch() {
        return shakePitch;
    }

    public float shakeRoll() {
        return shakeRoll;
    }

    /** Lub-dub: two bumps per beat, the second softer; 0..1. */
    static float heartbeat(float phase) {
        float a = bump(phase, 0.0f, 0.16f);
        float b = 0.6f * bump(phase, 0.22f, 0.16f);
        return Math.max(a, b);
    }

    private static float bump(float phase, float start, float width) {
        float x = (phase - start) / width;
        if (x <= 0f || x >= 1f) {
            return 0f;
        }
        return (float) Math.sin(Math.PI * x);
    }

    /** Smooth value noise in [-1, 1]: hashed lattice values, smoothstep-interpolated. */
    static float noise(float t, int seed) {
        int i = (int) Math.floor(t);
        float f = t - i;
        float a = lattice(i, seed);
        float b = lattice(i + 1, seed);
        return a + (b - a) * (f * f * (3f - 2f * f));
    }

    private static float lattice(int i, int seed) {
        int h = i * 0x27d4eb2d ^ seed * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    private static float smoothstep(float x) {
        x = clamp01(x);
        return x * x * (3f - 2f * x);
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }
}
