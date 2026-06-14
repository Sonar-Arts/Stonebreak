package com.stonebreak.player.combat.stealth;

import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import org.joml.Vector3f;

/**
 * Universal toggleable stealth mode available to every player class (not a class ability).
 * Reads all class-overridable values from a {@link StealthConfig} resolved per the player's
 * selected class, so classes like the Rogue tune stealth without modifying this controller.
 *
 * <p>State machine: {@code INACTIVE -> ENTERING (entry delay) -> STEALTHED -> BROKEN
 * (re-entry cooldown) -> INACTIVE}. Toggling off is immediate; entry has a delay during which
 * the player is still visible/detectable and which is cancelled by taking damage. Stealth breaks
 * instantly on attacking or taking damage, after which a re-entry cooldown must elapse before it
 * can be toggled on again.</p>
 */
public class StealthController {

    private enum State { INACTIVE, ENTERING, STEALTHED, BROKEN }

    private State state = State.INACTIVE;
    private float entryRemaining;
    private float reentryRemaining;
    private float reentryDuration;

    // ── Activation ────────────────────────────────────────────────────────────

    /**
     * Toggles stealth. Turning on from {@code INACTIVE} begins the entry delay; turning off from
     * {@code ENTERING}/{@code STEALTHED} is immediate. Ignored while {@code BROKEN} (the re-entry
     * cooldown must expire first).
     */
    public void toggle(Player player) {
        switch (state) {
            case INACTIVE -> {
                entryRemaining = PlayerConstants.STEALTH_ENTRY_DELAY;
                state = State.ENTERING;
            }
            case ENTERING, STEALTHED -> state = State.INACTIVE; // immediate exit, no cooldown
            case BROKEN -> { } // cannot re-enter until the cooldown drains
        }
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    public void update(float deltaTime, Player player) {
        switch (state) {
            case ENTERING -> {
                entryRemaining -= deltaTime;
                if (entryRemaining <= 0f) {
                    state = State.STEALTHED;
                }
            }
            case BROKEN -> {
                reentryRemaining -= deltaTime;
                if (reentryRemaining <= 0f) {
                    state = State.INACTIVE;
                }
            }
            case INACTIVE, STEALTHED -> { }
        }
    }

    // ── Break triggers ────────────────────────────────────────────────────────

    /** Cancels a pending entry or breaks active stealth when the player takes damage. */
    public void onDamageTaken(Player player) {
        if (state == State.ENTERING || state == State.STEALTHED) {
            enterBroken(player);
        }
    }

    /** Breaks stealth instantly when the player attacks. */
    public void onAttack(Player player) {
        if (state == State.ENTERING || state == State.STEALTHED) {
            enterBroken(player);
        }
    }

    /**
     * Breaks stealth on behalf of a class ability that consumes the stealthed state (e.g. the
     * Rogue's Shadow Step), starting the re-entry cooldown. Distinct from {@link #onAttack} so the
     * intent reads as "this ability spent my stealth", not "I swung a weapon". No-op when not stealthed.
     */
    public void forceBreak(Player player) {
        if (state == State.ENTERING || state == State.STEALTHED) {
            enterBroken(player);
        }
    }

    private void enterBroken(Player player) {
        reentryDuration = config(player).reentryDelay();
        reentryRemaining = reentryDuration;
        state = State.BROKEN;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** True once the entry delay has elapsed and the player is fully stealthed. */
    public boolean isStealthed() { return state == State.STEALTHED; }

    /** True during the entry delay (visible/detectable, not yet stealthed). */
    public boolean isEntering() { return state == State.ENTERING; }

    /** True while the post-break re-entry cooldown is draining. */
    public boolean isReentryCoolingDown() { return state == State.BROKEN; }

    /** True when {@link #toggle} can turn stealth on (not mid-cooldown). */
    public boolean canToggle() { return state != State.BROKEN; }

    /**
     * Remaining fraction of the re-entry cooldown in [0,1]: 1 right after a break, draining to 0
     * when stealth can be re-entered. Drives the depleting HUD cooldown bar. 0 when not cooling down.
     */
    public float getReentryRemainingFraction() {
        if (state != State.BROKEN || reentryDuration <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, reentryRemaining / reentryDuration));
    }

    /** Movement-speed multiplier applied while stealthed; 1 otherwise. */
    public float getMovementMultiplier(Player player) {
        return isStealthed() ? config(player).movementMult() : 1f;
    }

    /** True while stealthed — the player cannot sprint. */
    public boolean isSprintBlocked() { return isStealthed(); }

    /** Additional crit chance an attacker gains against a FLAT_FOOTED target, per the player's class. */
    public float getFlatFootedCritBonus(Player player) { return config(player).flatFootedCritBonus(); }

    /** Duration FLAT_FOOTED lasts when applied to an unaware target, per the player's class. */
    public float getFlatFootedDuration(Player player) { return config(player).flatFootedDuration(); }

    /**
     * Current player noise radius (blocks) for enemy sound detection. Stealthed emits the class
     * stealth radius; otherwise running/walking/idle by horizontal speed. A recent dodge spike
     * ({@link Player#getDodgeNoiseRadius()}) takes over when louder, even mid-stealth.
     */
    public float getNoiseRadius(Player player) {
        float base;
        if (isStealthed()) {
            base = config(player).noiseRadiusStealth();
        } else {
            float horiz = horizontalSpeed(player);
            if (horiz <= 0.5f) {
                base = 0f; // standing still emits no movement noise
            } else if (horiz >= PlayerConstants.MOVE_SPEED * 1.2f) {
                base = PlayerConstants.NOISE_RADIUS_RUN;
            } else {
                base = PlayerConstants.NOISE_RADIUS_WALK;
            }
        }
        return Math.max(base, player.getDodgeNoiseRadius());
    }

    private static float horizontalSpeed(Player player) {
        Vector3f v = player.getVelocity();
        return (float) Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static StealthConfig config(Player player) {
        return StealthConfig.forClass(player.getCharacterStats().getSelectedClassId());
    }
}
