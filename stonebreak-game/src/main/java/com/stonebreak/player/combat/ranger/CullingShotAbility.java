package com.stonebreak.player.combat.ranger;

import static com.stonebreak.player.PlayerConstants.RANGER_BLEED_DPS;
import static com.stonebreak.player.PlayerConstants.RANGER_BLEED_DURATION;
import static com.stonebreak.player.PlayerConstants.RANGER_CRIPPLE_DURATION;
import static com.stonebreak.player.PlayerConstants.RANGER_CRIPPLE_MAGNITUDE;
import static com.stonebreak.player.PlayerConstants.RANGER_CRIT_MULTIPLIER;
import static com.stonebreak.player.PlayerConstants.RANGER_CULLING_BASE_DAMAGE;
import static com.stonebreak.player.PlayerConstants.RANGER_CULLING_CHANNEL_TIME;
import static com.stonebreak.player.PlayerConstants.RANGER_CULLING_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.RANGER_CULLING_RANGE;
import static com.stonebreak.player.PlayerConstants.RANGER_CULLING_WEAKPOINT_BONUS;
import static com.stonebreak.player.PlayerConstants.RANGER_DASH_DISTANCE;
import static com.stonebreak.player.PlayerConstants.RANGER_DASH_SPEED;
import static com.stonebreak.player.PlayerConstants.RANGER_PREY_LOW_HP_FRACTION;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_MAX_STACKS;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Ranger active: a channeled long-range hitscan shot. The aim direction is locked the
 * moment the channel begins (the shot cannot be redirected); when the channel completes a
 * ray fires along that direction and strikes the first living entity it crosses, blocked
 * by solid terrain. Effects scale with Study stacks on the target at the time the shot
 * lands: always a heavy hit plus Bleed, at two stacks bonus weak-point damage, and at
 * Marked Prey a guaranteed critical plus Cripple — followed by a brief forward dash when
 * the prey was below the low-HP threshold at impact.
 * State machine: IDLE -&gt; CHANNELING -&gt; (DASHING) -&gt; COOLDOWN.
 */
public class CullingShotAbility {

    private static final float COLLISION_STEP = 0.2f;

    private enum State { IDLE, CHANNELING, DASHING, COOLDOWN }

    private State state = State.IDLE;
    private float channelRemaining;
    private float cooldownRemaining;

    private final Vector3f lockedDirection = new Vector3f();
    private final Vector3f dashDirection = new Vector3f();
    private float dashTraveled;

    public boolean isActive() { return state != State.IDLE; }
    public boolean isChanneling() { return state == State.CHANNELING; }
    public boolean isDashing() { return state == State.DASHING; }
    public boolean isOnCooldown() { return state == State.COOLDOWN; }
    public float getCooldownRemaining() { return state == State.COOLDOWN ? cooldownRemaining : 0f; }

    /** 0..1 channel progress while channeling; 0 otherwise. Renderer hook for the visible tell. */
    public float getChannelProgress() {
        if (state != State.CHANNELING) return 0f;
        return Math.min(1f, (RANGER_CULLING_CHANNEL_TIME - channelRemaining) / RANGER_CULLING_CHANNEL_TIME);
    }

    /** Attempts to begin channeling, locking the current aim direction. Returns true if started. */
    public boolean tryActivate(Player player) {
        if (state != State.IDLE) return false;

        Vector3f front = player.getCamera().getFront();
        if (front.lengthSquared() < 0.0001f) return false;
        lockedDirection.set(front).normalize();

        channelRemaining = RANGER_CULLING_CHANNEL_TIME;
        state = State.CHANNELING;
        return true;
    }

    public void update(float deltaTime, Player player, QuarryController quarry) {
        switch (state) {
            case CHANNELING -> {
                channelRemaining -= deltaTime;
                if (channelRemaining <= 0f) {
                    fire(player, quarry);
                }
            }
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

    /** Cancels any channel/dash and clears the cooldown (world reload). */
    public void reset() {
        state = State.IDLE;
        channelRemaining = 0f;
        cooldownRemaining = 0f;
        dashTraveled = 0f;
    }

    private void fire(Player player, QuarryController quarry) {
        state = State.COOLDOWN;
        cooldownRemaining = RANGER_CULLING_COOLDOWN;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        Vector3f origin = player.getRaycastEngine().eyeOrigin();
        LivingEntity hit = player.getRaycastEngine().raycastEntityAlong(
            origin, lockedDirection, entityManager.getLivingEntities(), RANGER_CULLING_RANGE);
        if (hit == null || !hit.isAlive()) {
            return; // whiff — cooldown already started
        }

        // Snapshot stack tier and HP state before the shot resolves, so the hit can't
        // self-upgrade its own scaling and the dash keys off HP at the moment of impact.
        int stacks = hit == quarry.getQuarry() ? quarry.getStudyStacks() : 0;
        boolean lowHpAtImpact = hit.getMaxHealth() > 0f
            && hit.getHealth() / hit.getMaxHealth() < RANGER_PREY_LOW_HP_FRACTION;

        // Single damage() call: the weak-point bonus and the guaranteed crit multiplier
        // resolve together (crit applied exactly once — there is no other crit system).
        float damage = RANGER_CULLING_BASE_DAMAGE;
        if (stacks >= 2) {
            damage += RANGER_CULLING_WEAKPOINT_BONUS;
        }
        if (stacks >= RANGER_STUDY_MAX_STACKS) {
            damage *= RANGER_CRIT_MULTIPLIER;
        }
        hit.damage(damage, LivingEntity.DamageSource.PLAYER);

        hit.applyStatusEffect(StatusEffectType.BLEED, RANGER_BLEED_DURATION, RANGER_BLEED_DPS);
        if (stacks >= RANGER_STUDY_MAX_STACKS) {
            hit.applyStatusEffect(StatusEffectType.CRIPPLE,
                RANGER_CRIPPLE_DURATION, RANGER_CRIPPLE_MAGNITUDE);
        }

        // The shot counts as a hit on the target (after the snapshot): builds/refreshes
        // Study but never consumes it.
        quarry.onPlayerHit(hit);

        if (stacks >= RANGER_STUDY_MAX_STACKS && lowHpAtImpact) {
            Vector3f toTarget = hit.getPosition().sub(player.getPosition());
            toTarget.y = 0f;
            if (toTarget.lengthSquared() > 0.0001f) {
                dashDirection.set(toTarget).normalize();
                dashTraveled = 0f;
                state = State.DASHING;
            }
        }
    }

    /** Drives the player forward in collision-checked steps, mirroring Rampage's charge movement. */
    private void updateDash(float deltaTime, Player player) {
        World world = Game.getWorld();
        if (world == null) {
            endDash();
            return;
        }

        float remaining = Math.min(RANGER_DASH_SPEED * deltaTime, RANGER_DASH_DISTANCE - dashTraveled);
        boolean stoppedByWall = false;

        while (remaining > 0f) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            Vector3f newPos = new Vector3f(dashDirection).mul(stepLen).add(player.getPosition());

            if (isBlocked(world, newPos)) {
                stoppedByWall = true;
                break;
            }

            player.setPosition(newPos);
            dashTraveled += stepLen;
            remaining -= stepLen;
        }

        if (stoppedByWall || dashTraveled >= RANGER_DASH_DISTANCE) {
            endDash();
        }
    }

    private void endDash() {
        state = State.COOLDOWN;
        cooldownRemaining = RANGER_CULLING_COOLDOWN;
    }

    private boolean isBlocked(World world, Vector3f pos) {
        return isSolidAt(world, pos.x, pos.y + 0.1f, pos.z)
            || isSolidAt(world, pos.x, pos.y + PlayerConstants.PLAYER_HEIGHT * 0.6f, pos.z);
    }

    private boolean isSolidAt(World world, float x, float y, float z) {
        BlockType block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return block != null && block.isSolid();
    }
}
