package com.stonebreak.mobs.entities.ai;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import org.joml.Vector3f;

/**
 * Reusable per-enemy stealth awareness for one {@link LivingEntity}. Tracks how aware the owner
 * is of the local player via two detection pillars — a forward sight cone (fast, line-of-sight
 * gated) and the player's noise radius (slow) — feeding a 0..{@code ALERTED_THRESHOLD} meter that
 * decays when the player is undetected. The meter drives three states: UNAWARE → SUSPICIOUS →
 * ALERTED.
 *
 * <p>Attach to any entity that should react to a stealthed player; SUSPICIOUS/ALERTED let
 * {@link #drive} take over movement (investigate / pursue) while {@link #update} maintains the
 * meter. There is no mob→player attack yet, so the ALERTED attack is a stub.</p>
 */
public class AwarenessController {

    public enum AwarenessState { UNAWARE, SUSPICIOUS, ALERTED }

    private final LivingEntity self;

    private AwarenessState state = AwarenessState.UNAWARE;
    private float meter;
    private final Vector3f lastKnownPos = new Vector3f();
    private boolean hasLastKnown;

    public AwarenessController(LivingEntity self) {
        this.self = self;
    }

    public AwarenessState getState() { return state; }

    // ── Detection update ──────────────────────────────────────────────────────

    /** Advances the awareness meter from sight/sound detection and applies decay otherwise. */
    public void update(float deltaTime) {
        if (!self.isAlive()) return;

        Player player = Game.getPlayer();
        if (player == null || player.isDead()) {
            decay(deltaTime);
            resolveState();
            return;
        }

        Vector3f playerPos = player.getPosition();
        Vector3f selfPos = self.getPosition();
        float dist = selfPos.distance(playerPos);

        if (canSee(player, selfPos, playerPos, dist)) {
            meter += PlayerConstants.AWARENESS_SIGHT_GAIN_PER_SEC * deltaTime;
            rememberPlayer(playerPos);
        } else if (dist <= player.getCurrentNoiseRadius()) {
            meter += PlayerConstants.AWARENESS_SOUND_GAIN_PER_SEC * deltaTime;
            rememberPlayer(playerPos);
        } else {
            decay(deltaTime);
        }

        meter = Math.max(0f, Math.min(PlayerConstants.AWARENESS_ALERTED_THRESHOLD, meter));
        resolveState();
    }

    /** True when the player is inside the forward sight cone, in range, with clear line of sight. */
    private boolean canSee(Player player, Vector3f selfPos, Vector3f playerPos, float dist) {
        if (dist > PlayerConstants.ENEMY_SIGHT_RANGE) return false;

        Vector3f toPlayer = new Vector3f(playerPos).sub(selfPos);
        toPlayer.y = 0f;
        if (toPlayer.lengthSquared() < 0.0001f) return true; // on top of us
        toPlayer.normalize();

        // Forward from yaw (model front faces -Z, matching LivingEntity.faceDirection).
        float yaw = (float) Math.toRadians(self.getRotation().y);
        Vector3f forward = new Vector3f((float) Math.sin(yaw), 0f, (float) -Math.cos(yaw));

        float halfAngleCos = (float) Math.cos(Math.toRadians(PlayerConstants.ENEMY_SIGHT_ANGLE_DEG * 0.5f));
        if (forward.dot(toPlayer) < halfAngleCos) return false;

        // Line of sight: a solid block between us and the player breaks it.
        Vector3f eye = new Vector3f(selfPos.x, selfPos.y + self.getType().getHeight() * 0.9f, selfPos.z);
        Vector3f dir = new Vector3f(playerPos.x, playerPos.y + PlayerConstants.PLAYER_HEIGHT * 0.5f, playerPos.z)
                .sub(eye);
        float losDist = dir.length();
        if (losDist < 0.0001f) return true;
        dir.div(losDist);
        return player.getRaycastEngine().distanceToFirstSolid(eye, dir, losDist) >= losDist;
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

    // ── Behaviour ─────────────────────────────────────────────────────────────

    /**
     * Drives movement for the current awareness state, returning true when it took control (so the
     * owner should skip its passive AI this frame). SUSPICIOUS investigates the last known position;
     * ALERTED pursues the player. UNAWARE yields control back to the owner.
     */
    public boolean drive(float deltaTime) {
        if (!self.isAlive()) return false;
        switch (state) {
            case SUSPICIOUS -> {
                if (hasLastKnown) {
                    self.moveToward(lastKnownPos, deltaTime);
                }
                return true;
            }
            case ALERTED -> {
                Player player = Game.getPlayer();
                if (player != null && !player.isDead()) {
                    self.moveToward(player.getPosition(), deltaTime);
                    attackPlayerStub(player);
                }
                return true;
            }
            case UNAWARE -> {
                return false;
            }
        }
        return false;
    }

    /**
     * Placeholder for the ALERTED melee attack. No mob→player damage exists in the game yet, so
     * this intentionally does nothing; wire actual contact damage here once that system lands.
     */
    private void attackPlayerStub(Player player) {
        // TODO: deal contact damage to the player when within reach (no mob attack system yet).
    }
}
