package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Reusable per-enemy stealth awareness for one {@link LivingEntity}. Tracks how aware the owner
 * is of the nearest player via two detection pillars — a forward sight cone (fast, line-of-sight
 * gated) and the player's noise radius (slow) — feeding a 0..{@code ALERTED_THRESHOLD} meter that
 * decays when the player is undetected. The meter drives three states: UNAWARE → SUSPICIOUS →
 * ALERTED.
 *
 * <p>Attach to any entity that should react to a stealthed player; SUSPICIOUS/ALERTED let
 * {@link com.stonebreak.mobs.entities.ai.behavior.PursuePlayerBehavior} take over movement
 * (investigate / pursue) while {@link #update} maintains the meter. There is no mob→player attack
 * yet, so the ALERTED attack is a stub.</p>
 *
 * <p>Players come from a {@link PlayerLocator} (the mob's world's roster by default) and line of
 * sight is marched through the mob's own world, so this runs on the server tick thread without
 * touching the client. What the server cannot know yet is the player's noise: stealth state,
 * walking noise and dodge spikes are client-side, so the sound pillar only hears a sprinting
 * player ({@link PlayerLocator#nearestPlayerNoiseRadius}) until those are replicated.</p>
 */
public class AwarenessController {

    public enum AwarenessState { UNAWARE, SUSPICIOUS, ALERTED }

    private final LivingEntity self;
    private final PlayerLocator players;

    private AwarenessState state = AwarenessState.UNAWARE;
    private float meter;
    private final Vector3f lastKnownPos = new Vector3f();
    private boolean hasLastKnown;
    private final Vector3f playerScratch = new Vector3f();

    /** Line-of-sight marcher over the mob's world; rebuilt only if the mob changes worlds. */
    private RaycastEngine sightRay;
    private World sightRayWorld;

    /** Watches the players sharing the mob's world. */
    public AwarenessController(LivingEntity self) {
        this(self, PlayerLocator.ofWorld(self));
    }

    public AwarenessController(LivingEntity self, PlayerLocator players) {
        this.self = self;
        this.players = players;
    }

    public AwarenessState getState() { return state; }

    // ── Detection update ──────────────────────────────────────────────────────

    /** Advances the awareness meter from sight/sound detection and applies decay otherwise. */
    public void update(float deltaTime) {
        if (!self.isAlive()) return;

        Vector3f selfPos = self.getPosition();
        Vector3f playerPos = players.nearestPlayer(selfPos, playerScratch);
        if (playerPos == null) {
            decay(deltaTime);
            resolveState();
            return;
        }

        float dist = selfPos.distance(playerPos);

        if (canSee(selfPos, playerPos, dist)) {
            meter += PlayerConstants.AWARENESS_SIGHT_GAIN_PER_SEC * deltaTime;
            rememberPlayer(playerPos);
        } else if (dist <= players.nearestPlayerNoiseRadius(selfPos)) {
            meter += PlayerConstants.AWARENESS_SOUND_GAIN_PER_SEC * deltaTime;
            rememberPlayer(playerPos);
        } else {
            decay(deltaTime);
        }

        meter = Math.max(0f, Math.min(PlayerConstants.AWARENESS_ALERTED_THRESHOLD, meter));
        resolveState();
    }

    /** True when the player is inside the forward sight cone, in range, with clear line of sight. */
    private boolean canSee(Vector3f selfPos, Vector3f playerPos, float dist) {
        if (dist > PlayerConstants.ENEMY_SIGHT_RANGE) return false;

        Vector3f toPlayer = new Vector3f(playerPos).sub(selfPos);
        toPlayer.y = 0f;
        if (toPlayer.lengthSquared() < 0.0001f) return true; // on top of us
        toPlayer.normalize();

        // Forward from yaw, honoring the entity type's model yaw offset
        // (matches LivingEntity.faceDirection and Steering).
        Vector3f forward = self.getForwardDirection();

        float halfAngleCos = (float) Math.cos(Math.toRadians(PlayerConstants.ENEMY_SIGHT_ANGLE_DEG * 0.5f));
        if (forward.dot(toPlayer) < halfAngleCos) return false;

        // Line of sight: a solid block between us and the player breaks it.
        Vector3f eye = new Vector3f(selfPos.x, selfPos.y + self.getType().getHeight() * 0.9f, selfPos.z);
        Vector3f dir = new Vector3f(playerPos.x, playerPos.y + PlayerConstants.PLAYER_HEIGHT * 0.5f, playerPos.z)
                .sub(eye);
        float losDist = dir.length();
        if (losDist < 0.0001f) return true;
        dir.div(losDist);
        RaycastEngine ray = sightRay();
        return ray == null || ray.distanceToFirstSolid(eye, dir, losDist) >= losDist;
    }

    /**
     * A ray marcher over the MOB's world — never the client player's, whose engine walks the
     * render world from the main thread. Only {@code distanceToFirstSolid} is used, which needs
     * nothing but the world. Null without a world: nothing there to block the view.
     */
    private RaycastEngine sightRay() {
        World world = self.getWorld();
        if (world == null) {
            return null;
        }
        if (sightRay == null || sightRayWorld != world) {
            sightRay = new RaycastEngine(null, null, world);
            sightRayWorld = world;
        }
        return sightRay;
    }

    private void rememberPlayer(Vector3f playerPos) {
        lastKnownPos.set(playerPos);
        hasLastKnown = true;
    }

    private void decay(float deltaTime) {
        meter -= PlayerConstants.AWARENESS_DECAY_PER_SEC * deltaTime;
    }

    private void resolveState() {
        AwarenessState previous = state;
        if (meter >= PlayerConstants.AWARENESS_ALERTED_THRESHOLD) {
            state = AwarenessState.ALERTED;
        } else if (state == AwarenessState.ALERTED) {
            // Sticky: an alerted enemy stays alerted until its meter fully drains.
            if (meter <= 0f) state = AwarenessState.UNAWARE;
        } else if (meter >= PlayerConstants.AWARENESS_SUSPICIOUS_THRESHOLD) {
            state = AwarenessState.SUSPICIOUS;
        } else {
            state = AwarenessState.UNAWARE;
        }

        // Becoming alerted shakes off the flat-footed opener window.
        if (state == AwarenessState.ALERTED && previous != AwarenessState.ALERTED) {
            self.removeStatusEffect(StatusEffectType.FLAT_FOOTED);
        }
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    /**
     * Where the player was last detected, or {@code null} if this mob has never noticed one.
     *
     * <p>Acting on it belongs to
     * {@link com.stonebreak.mobs.entities.ai.behavior.PursuePlayerBehavior}: this class decides
     * <em>whether</em> the mob has noticed the player, the behaviour decides what to do about it,
     * and navigation works out how to get there.
     */
    public Vector3f lastKnownPlayerPosition(Vector3f out) {
        return hasLastKnown ? out.set(lastKnownPos) : null;
    }
}
