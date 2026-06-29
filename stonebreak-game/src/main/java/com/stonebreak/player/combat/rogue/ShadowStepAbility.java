package com.stonebreak.player.combat.rogue;

import static com.stonebreak.player.PlayerConstants.SHADOW_STEP_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.SHADOW_STEP_RANGE;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

/**
 * Rogue active (R): a stealth finisher. While fully stealthed, teleports the Rogue to directly
 * behind a nearby unaware target and leaves it FLAT_FOOTED, opening a guaranteed-crit window
 * (the Rogue's flat-footed crit bonus is 1.0). Breaking stealth here starts the re-entry cooldown.
 *
 * <p>State machine: IDLE -&gt; COOLDOWN. The guaranteed crit happens on the Rogue's next melee hit,
 * resolved by {@link Player#attackEntity}, which is where Momentum is consumed.</p>
 */
public class ShadowStepAbility {

    private enum State { IDLE, COOLDOWN }

    private State state = State.IDLE;
    private float cooldownRemaining;

    public boolean isActive() {
        return state != State.IDLE;
    }

    public boolean isOnCooldown() {
        return state == State.COOLDOWN;
    }

    public float getCooldownRemaining() {
        return state == State.COOLDOWN ? cooldownRemaining : 0f;
    }

    /**
     * True when the ability could fire right now: the player is fully stealthed, off cooldown, and a
     * valid unaware target is within range. Drives the HUD's available/greyed state and range hint.
     */
    public boolean canActivate(Player player) {
        return state == State.IDLE
            && player.getStealth().isStealthed()
            && findTarget(player) != null;
    }

    /**
     * Attempts to Shadow Step. Requires full stealth, off cooldown, and a valid unaware target in
     * range; fails (without consuming the cooldown) otherwise.
     */
    public boolean tryActivate(Player player) {
        if (state != State.IDLE) return false;
        if (!player.getStealth().isStealthed()) return false;

        LivingEntity target = findTarget(player);
        if (target == null) return false;

        teleportBehind(player, target);
        player.getStealth().forceBreak(player); // spends stealth, starts the re-entry cooldown
        target.applyStatusEffect(StatusEffectType.FLAT_FOOTED,
            player.getStealth().getFlatFootedDuration(player), 0f);

        state = State.COOLDOWN;
        cooldownRemaining = SHADOW_STEP_COOLDOWN;
        return true;
    }

    public void update(float deltaTime, Player player) {
        if (state == State.COOLDOWN) {
            cooldownRemaining -= deltaTime;
            if (cooldownRemaining <= 0f) {
                state = State.IDLE;
            }
        }
    }

    public void reset() {
        state = State.IDLE;
        cooldownRemaining = 0f;
    }

    /** Nearest living, unaware entity within {@link com.stonebreak.player.PlayerConstants#SHADOW_STEP_RANGE}, or null. */
    private LivingEntity findTarget(Player player) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return null;

        Vector3f origin = player.getPosition();
        LivingEntity nearest = null;
        float nearestDistSq = SHADOW_STEP_RANGE * SHADOW_STEP_RANGE;
        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            AwarenessController awareness = entity.getAwareness();
            if (awareness == null || awareness.getState() != AwarenessController.AwarenessState.UNAWARE) {
                continue;
            }
            float distSq = entity.getPosition().distanceSquared(origin);
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entity;
            }
        }
        return nearest;
    }

    /**
     * Teleports the player to one block behind the target, opposite the direction the target faces.
     * Uses the same yaw-to-forward convention as {@link AwarenessController} (model front is -Z).
     */
    private void teleportBehind(Player player, LivingEntity target) {
        Vector3f targetPos = target.getPosition();
        float yaw = (float) Math.toRadians(target.getRotation().y);
        Vector3f forward = new Vector3f((float) Math.sin(yaw), 0f, (float) -Math.cos(yaw));
        player.setPosition(targetPos.x - forward.x, targetPos.y, targetPos.z - forward.z);
    }
}
