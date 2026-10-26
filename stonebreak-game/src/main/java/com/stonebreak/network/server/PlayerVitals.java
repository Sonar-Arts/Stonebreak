package com.stonebreak.network.server;

import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerStateFlags;

import static com.stonebreak.player.PlayerConstants.DODGE_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.DODGE_INVINCIBILITY_TIME;
import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;
import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;

/**
 * A player's server-owned health and the rules that change it (issue #343): the server's twin
 * of the client's {@code HealthController}, {@code FallDamageHandler} and spawn protection, fed
 * by what the server knows — reported positions and state flags, its own world, and the hits it
 * resolves. Pure state: no packets, no world; {@code ServerHealthHandler} drives it.
 *
 * <p>Tick-thread only, like the rest of a {@link ServerPlayer}'s game state.
 */
public final class PlayerVitals {

    /** Falls up to this many blocks are free; each block past it costs a quarter heart. */
    static final float SAFE_FALL_BLOCKS = 4.0f;
    static final float DAMAGE_PER_FALL_BLOCK = HEALTH_PER_HEART * 0.25f;
    /** A reported move longer than this is a teleport (respawn, /tp, correction): no fall from it. */
    static final float TELEPORT_DISTANCE = 8.0f;
    /** Spawn protection ends early once on the ground for this long (the client's rule). */
    static final long PROTECTION_GROUND_GRACE_NS = 500_000_000L;
    /** A dodge's i-frames, plus the report interval and some latency: what the server honours. */
    static final long DODGE_WINDOW_NS = (long) ((DODGE_INVINCIBILITY_TIME + 0.15f) * 1e9);
    /** Dodges closer together than this are not real dodges (cooldown, less jitter). */
    static final long DODGE_MIN_INTERVAL_NS = (long) (DODGE_COOLDOWN * 0.8f * 1e9);

    private float health;
    private float maxHealth;
    private byte lastCause = PlayerHealthS2C.CAUSE_NONE;
    /** Something a client mirrors changed since the last broadcast. */
    private boolean dirty = true;

    private long protectionStartNs;
    private long protectionUntilNs;

    private boolean hasLastReport;
    private float lastX, lastY, lastZ;
    private boolean airborne;
    /** The ground height while grounded; the highest point reached while airborne. */
    private float fallApexY;

    private float cactusTimer;

    private boolean wasDodging;
    private long dodgeRiseNs = Long.MIN_VALUE;
    private long dodgeWindowEndNs = Long.MIN_VALUE;

    public PlayerVitals(float health, float maxHealth) {
        this.maxHealth = Math.max(1f, maxHealth);
        this.health = clamp(health, this.maxHealth);
    }

    // ─── Health ─────────────────────────────────────────────────────────────────

    public float health() { return health; }
    public float maxHealth() { return maxHealth; }
    public byte lastCause() { return lastCause; }

    /** Sets the health outright (a loaded save, a seeded character), clamped to the maximum. */
    public void setHealth(float health) {
        this.health = clamp(health, maxHealth);
        dirty = true;
    }

    /**
     * A new maximum (the CON score changed). As on the client: a raised maximum adds the
     * difference, a lowered one only clamps.
     */
    public void applyMaxHealth(float newMax) {
        newMax = Math.max(1f, newMax);
        if (newMax == maxHealth) {
            return;
        }
        float diff = newMax - maxHealth;
        maxHealth = newMax;
        health = diff > 0 ? Math.min(maxHealth, health + diff) : Math.min(health, maxHealth);
        dirty = true;
    }

    /**
     * Takes {@code amount} of {@code cause} damage; returns what was taken (0 when nothing was).
     * The caller handles the death a drop to 0 means. Combat damage inside an honoured dodge
     * window is negated; fall and cactus damage are not (as on the client).
     */
    public float damage(float amount, byte cause, long nowNs) {
        if (!(amount > 0f) || health <= 0f) {
            return 0f;
        }
        if (PlayerHealthS2C.isCombat(cause) && nowNs < dodgeWindowEndNs) {
            return 0f;
        }
        float taken = Math.min(amount, health);
        health -= taken;
        lastCause = cause;
        dirty = true;
        return taken;
    }

    /** Heals by {@code amount} up to the maximum; returns what was restored. A dead body heals nothing. */
    public float heal(float amount) {
        if (!(amount > 0f) || health <= 0f) {
            return 0f;
        }
        float restored = Math.min(amount, maxHealth - health);
        if (restored > 0f) {
            health += restored;
            lastCause = PlayerHealthS2C.CAUSE_NONE;
            dirty = true;
        }
        return restored;
    }

    /**
     * Back to full health (respawn). Spawn protection reopens now and again with the first
     * report from the new spot ({@link #onStateReport}).
     */
    public void restore(long nowNs) {
        health = maxHealth;
        lastCause = PlayerHealthS2C.CAUSE_NONE;
        dirty = true;
        protect(nowNs);
        hasLastReport = false;
        airborne = false;
        cactusTimer = 0f;
    }

    /** Opens the spawn-protection window ({@code SPAWN_PROTECTION_DURATION}). */
    public void protect(long nowNs) {
        protectionStartNs = nowNs;
        protectionUntilNs = nowNs + (long) (SPAWN_PROTECTION_DURATION * 1e9);
    }

    public boolean isProtected(long nowNs) {
        return nowNs < protectionUntilNs;
    }

    /** Whether a broadcast is owed; clears the mark ({@link #takeCause} reads what caused it). */
    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    /** The cause of the changes since the last broadcast, reset for the next one. */
    public byte takeCause() {
        byte cause = lastCause;
        lastCause = PlayerHealthS2C.CAUSE_NONE;
        return cause;
    }

    public void markDirty() { dirty = true; }

    // ─── State reports ─────────────────────────────────────────────────────────

    /**
     * Folds in one accepted state report and returns the fall damage it lands (0 for none).
     * Mirrors the client's {@code FallDamageHandler}: the fall is measured from the highest
     * point since leaving the ground, the first 4 blocks are free, and landing in water, flying
     * and spawn protection cost nothing. Water anywhere in the fall, a seat, or a teleport-sized
     * jump between reports starts the measurement over.
     *
     * @param seated riding a vehicle (no fall while seated)
     */
    public float onStateReport(float x, float y, float z, short flags, boolean seated, long nowNs) {
        if (!hasLastReport) {
            protect(nowNs); // arriving in the world (join, respawn): spawn protection starts here
        }
        boolean teleported = hasLastReport && distanceSq(x, y, z) > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
        hasLastReport = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        observeDodge(PlayerStateFlags.has(flags, PlayerStateFlags.DODGING), nowNs);

        boolean onGround = PlayerStateFlags.has(flags, PlayerStateFlags.ON_GROUND);
        boolean inWater = PlayerStateFlags.has(flags, PlayerStateFlags.PARTIALLY_IN_WATER)
                || PlayerStateFlags.has(flags, PlayerStateFlags.SWIMMING);
        boolean flying = PlayerStateFlags.has(flags, PlayerStateFlags.FLYING);
        if (onGround && isProtected(nowNs) && nowNs - protectionStartNs >= PROTECTION_GROUND_GRACE_NS) {
            protectionUntilNs = nowNs; // safely down: protection ends early, as on the client
        }
        if (teleported || flying || inWater || seated || isProtected(nowNs)) {
            airborne = !onGround;
            fallApexY = y;
            return 0f;
        }
        if (!onGround) {
            // fallApexY holds the ground height until the player leaves it, then the apex.
            fallApexY = Math.max(fallApexY, y);
            airborne = true;
            return 0f;
        }
        float damage = 0f;
        if (airborne) {
            float fallen = fallApexY - y;
            if (fallen > SAFE_FALL_BLOCKS) {
                damage = (fallen - SAFE_FALL_BLOCKS) * DAMAGE_PER_FALL_BLOCK;
            }
        }
        airborne = false;
        fallApexY = y;
        return damage;
    }

    /**
     * Advances cactus contact by {@code dt} seconds and returns the damage due (the client's
     * {@code CactusContactDamage}: one pulse per interval in contact).
     */
    public float onCactusContact(boolean touching, boolean flying, float dt, long nowNs,
                                 float pulseInterval, float damagePerPulse) {
        if (!touching || flying || isProtected(nowNs)) {
            cactusTimer = 0f;
            return 0f;
        }
        cactusTimer += dt;
        float damage = 0f;
        while (cactusTimer >= pulseInterval) {
            cactusTimer -= pulseInterval;
            damage += damagePerPulse;
        }
        return damage;
    }

    /** The dodge flag's rising edge opens an i-frame window — at most once per dodge cooldown. */
    private void observeDodge(boolean dodging, long nowNs) {
        if (dodging && !wasDodging
                && (dodgeRiseNs == Long.MIN_VALUE || nowNs - dodgeRiseNs >= DODGE_MIN_INTERVAL_NS)) {
            dodgeRiseNs = nowNs;
            dodgeWindowEndNs = nowNs + DODGE_WINDOW_NS;
        }
        wasDodging = dodging;
    }

    private float distanceSq(float x, float y, float z) {
        float dx = x - lastX;
        float dy = y - lastY;
        float dz = z - lastZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float clamp(float health, float max) {
        return Float.isFinite(health) ? Math.max(0f, Math.min(health, max)) : max;
    }
}
