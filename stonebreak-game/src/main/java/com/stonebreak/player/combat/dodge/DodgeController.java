package com.stonebreak.player.combat.dodge;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.stonebreak.player.PlayerConstants.DODGE_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.DODGE_DASH_DISTANCE;
import static com.stonebreak.player.PlayerConstants.DODGE_INVINCIBILITY_TIME;
import static com.stonebreak.player.PlayerConstants.DODGE_SPEED;
import static com.stonebreak.player.PlayerConstants.DODGE_STEALTH_NOISE_DURATION;
import static com.stonebreak.player.PlayerConstants.DODGE_STEALTH_NOISE_RADIUS;

/**
 * Universal directional dodge available to every player class (not a class ability).
 * Pressing the dodge key dashes the player a short distance in their current movement
 * direction — or backward when standing still — granting a brief invincibility window
 * and entering a per-dodge cooldown. State machine: IDLE -&gt; DASHING -&gt; COOLDOWN.
 *
 * <p>On successful completion, fires {@link DodgeListener#onDodgeSuccess(Player)} to
 * registered listeners (the future Rogue Momentum passive subscribes here). While
 * dodging, the player briefly raises a "noise radius" value that a future stealth
 * system will read; the dodge itself never breaks stealth.</p>
 *
 * <p>Collision-stepped movement mirrors {@code RampageAbility.updateCharge}.</p>
 */
public class DodgeController {

    private static final float COLLISION_STEP = 0.2f;

    private enum State { IDLE, DASHING, COOLDOWN }

    private State state = State.IDLE;
    private float cooldownRemaining;
    private float invincibilityRemaining;
    private float noiseRemaining;

    private final Vector3f direction = new Vector3f();
    private float distanceTraveled;

    private final Set<DodgeListener> listeners = new CopyOnWriteArraySet<>();

    // ── Listener registration ─────────────────────────────────────────────────
    public void addDodgeListener(DodgeListener listener)    { listeners.add(listener); }
    public void removeDodgeListener(DodgeListener listener) { listeners.remove(listener); }

    // ── Activation ────────────────────────────────────────────────────────────

    /**
     * Attempts to begin a dodge in the given intended movement direction. Returns true if a dash
     * started (only possible from IDLE). {@code intendedDir} is the player's current WASD input
     * direction (horizontal, normalized) as computed by the caller; when it is zero (no movement
     * keys held) the dodge dashes backward — opposite of where the camera is looking.
     */
    public boolean tryDodge(Player player, Vector3f intendedDir) {
        if (state != State.IDLE) return false;

        // Dash where the player is inputting; if no movement key is held, dash backward.
        if (intendedDir != null && intendedDir.lengthSquared() > 0.0001f) {
            direction.set(intendedDir);
        } else {
            Vector3f front = player.getCamera().getFront();
            direction.set(-front.x, 0f, -front.z);
            if (direction.lengthSquared() < 0.0001f) {
                direction.set(0f, 0f, 1f);
            }
        }
        direction.normalize();

        distanceTraveled = 0f;
        invincibilityRemaining = DODGE_INVINCIBILITY_TIME;
        noiseRemaining = DODGE_STEALTH_NOISE_DURATION;
        state = State.DASHING;
        return true;
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    public void update(float deltaTime, Player player) {
        if (invincibilityRemaining > 0f) invincibilityRemaining -= deltaTime;
        if (noiseRemaining > 0f)         noiseRemaining -= deltaTime;

        switch (state) {
            case DASHING -> updateDash(deltaTime, player);
            case COOLDOWN -> {
                cooldownRemaining -= deltaTime;
                if (cooldownRemaining <= 0f) {
                    state = State.IDLE;
                }
            }
            case IDLE -> { }
        }
    }

    private void updateDash(float deltaTime, Player player) {
        World world = Game.getWorld();
        if (world == null) {
            completeDash(player);
            return;
        }

        float remaining = Math.min(DODGE_SPEED * deltaTime, DODGE_DASH_DISTANCE - distanceTraveled);
        boolean stoppedByWall = false;

        while (remaining > 0f) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            Vector3f newPos = new Vector3f(direction).mul(stepLen).add(player.getPosition());

            if (isBlocked(world, newPos)) {
                stoppedByWall = true;
                break;
            }

            player.setPosition(newPos);
            distanceTraveled += stepLen;
            remaining -= stepLen;
        }

        if (stoppedByWall || distanceTraveled >= DODGE_DASH_DISTANCE) {
            completeDash(player);
        }
    }

    private void completeDash(Player player) {
        state = State.COOLDOWN;
        cooldownRemaining = DODGE_COOLDOWN;
        fireDodgeSuccess(player);
    }

    private void fireDodgeSuccess(Player player) {
        // onDodgeSuccess: the Rogue's Momentum passive will subscribe here (via DodgeListener) to gain +1 stack.
        // PARRY HOOK — parry success will also grant +1 Momentum stack here
        for (DodgeListener listener : listeners) {
            listener.onDodgeSuccess(player);
        }
    }

    private boolean isBlocked(World world, Vector3f pos) {
        return isSolidAt(world, pos.x, pos.y + 0.1f, pos.z)
            || isSolidAt(world, pos.x, pos.y + PlayerConstants.PLAYER_HEIGHT * 0.6f, pos.z);
    }

    private boolean isSolidAt(World world, float x, float y, float z) {
        BlockType block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return block != null && block.isSolid();
    }

    // ── Queries (UI / damage / stealth) ───────────────────────────────────────

    /** True during the dodge's invincibility window; combat damage is negated while this holds. */
    public boolean isInvincible() { return invincibilityRemaining > 0f; }

    /** True while the dash is driving the player; WASD movement input should be suppressed. */
    public boolean isMovementLocked() { return state == State.DASHING; }

    /** True when a dodge can be triggered (off cooldown and not mid-dash). */
    public boolean isReady() { return state == State.IDLE; }

    /** Cooldown recovery in [0,1]; 1 means the dodge is ready. */
    public float getCooldownProgress() {
        if (state != State.COOLDOWN) return 1f;
        return Math.max(0f, Math.min(1f, 1f - cooldownRemaining / DODGE_COOLDOWN));
    }

    /** Player noise radius (blocks) spiked by a recent dodge; 0 when not spiked. Read by the future stealth system. */
    public float getCurrentNoiseRadius() {
        return noiseRemaining > 0f ? DODGE_STEALTH_NOISE_RADIUS : 0f;
    }
}
