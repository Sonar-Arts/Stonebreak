package com.stonebreak.player.combat.illusionist;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DECOY_HIT_SLOW_DURATION;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DECOY_HIT_SLOW_MAG;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DECOY_REVEAL_DURATION;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_MIRRORED_DECEIT_COOLDOWN;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;
import com.stonebreak.rendering.effects.IllusionSmokeParticles;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Illusionist active: Mirrored Deceit. Spawns two {@link IllusionDecoy} figures that fan out from
 * the player (offset 120° and 240°) and mirror the player's movement and attack pose, baiting
 * enemies into striking illusions. A decoy has 1 HP, so any hit destroys it; on death the killer is
 * slowed ({@link StatusEffectType#CRIPPLE}), Revealed through terrain ({@link StatusEffectType#REVEALED}),
 * and gains a stack of Doubt. The cooldown only begins once the last decoy is gone (death or the
 * duration lapsing), not on cast.
 */
public class MirroredDeceitAbility {

    private static final float[] DECOY_ANGLES = {120f, 240f};

    private boolean active;
    private float cooldownRemaining;
    private float durationRemaining;
    private final List<IllusionDecoy> decoys = new ArrayList<>();
    private final IllusionSmokeParticles smoke = new IllusionSmokeParticles();

    public boolean isActive() { return active; }
    public boolean isOnCooldown() { return cooldownRemaining > 0f; }
    public float getCooldownRemaining() { return cooldownRemaining; }
    public IllusionSmokeParticles getSmoke() { return smoke; }

    /**
     * Spawns the decoys at the player's position. Fails (no cooldown consumed) when the ability is
     * still active or on cooldown, or the world/entity manager is unavailable.
     */
    public boolean tryActivate(Player player) {
        if (active || cooldownRemaining > 0f) return false;
        World world = Game.getWorld();
        EntityManager entityManager = Game.getEntityManager();
        if (world == null || entityManager == null) return false;

        Vector3f spawnPos = player.getPosition();
        int heldItemId = player.getInventory().getSelectedBlockTypeId();
        for (float angle : DECOY_ANGLES) {
            IllusionDecoy decoy = new IllusionDecoy(world, new Vector3f(spawnPos), player, angle);
            decoy.setHeldItemId(heldItemId);
            entityManager.addEntity(decoy);
            decoys.add(decoy);
        }
        smoke.burst(new Vector3f(spawnPos.x, spawnPos.y + 1f, spawnPos.z));

        active = true;
        durationRemaining = com.stonebreak.player.PlayerConstants.ILLUSIONIST_DECOY_DURATION;
        return true;
    }

    public void update(float deltaTime, Player player, DoubtController doubt) {
        if (cooldownRemaining > 0f) {
            cooldownRemaining -= deltaTime;
        }
        smoke.update(deltaTime);

        if (!active) return;

        boolean fakeCasting = player.isAttacking();
        durationRemaining -= deltaTime;

        Iterator<IllusionDecoy> it = decoys.iterator();
        while (it.hasNext()) {
            IllusionDecoy decoy = it.next();
            if (!decoy.isAlive()) {
                onDecoyDestroyed(decoy, doubt);
                it.remove();
                continue;
            }
            if (durationRemaining <= 0f) {
                // Duration lapsed — dismiss the decoy quietly (no punish, it wasn't struck).
                dismiss(decoy);
                it.remove();
                continue;
            }
            decoy.setFakeCasting(fakeCasting);
            decoy.update(deltaTime, player);
        }

        if (decoys.isEmpty()) {
            active = false;
            cooldownRemaining = ILLUSIONIST_MIRRORED_DECEIT_COOLDOWN;
        }
    }

    /** Clears decoys and cooldown (world reload — decoy references go stale). */
    public void reset() {
        for (IllusionDecoy decoy : decoys) {
            decoy.setAlive(false);
        }
        decoys.clear();
        active = false;
        cooldownRemaining = 0f;
        durationRemaining = 0f;
    }

    /** Punishes the attacker that destroyed a decoy, then removes the decoy from the world. */
    private void onDecoyDestroyed(IllusionDecoy decoy, DoubtController doubt) {
        smoke.burst(decoy.getPosition());
        LivingEntity killer = resolveKiller(decoy);
        if (killer != null) {
            killer.applyStatusEffect(StatusEffectType.CRIPPLE,
                ILLUSIONIST_DECOY_HIT_SLOW_DURATION, ILLUSIONIST_DECOY_HIT_SLOW_MAG);
            killer.applyStatusEffect(StatusEffectType.REVEALED,
                ILLUSIONIST_DECOY_REVEAL_DURATION, 0f);
            doubt.addStack(killer);
        }
    }

    private void dismiss(IllusionDecoy decoy) {
        smoke.burst(decoy.getPosition());
        decoy.setAlive(false);
    }

    /**
     * Resolves which living entity destroyed the decoy. No hostile mob AI exists yet, so this is a
     * best-effort lookup: the nearest living entity to the decoy (excluding other decoys) within a
     * short reach, preferring the recorded attacker position when available.
     */
    private LivingEntity resolveKiller(IllusionDecoy decoy) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return null;
        Vector3f reference = decoy.getLastAttackerPosition() != null
            ? decoy.getLastAttackerPosition()
            : decoy.getPosition();

        LivingEntity nearest = null;
        float nearestDistSq = 9f; // within ~3 blocks (melee reach)
        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (entity == decoy || entity instanceof IllusionDecoy || !entity.isAlive()) continue;
            float distSq = entity.getPosition().distanceSquared(reference);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entity;
            }
        }
        return nearest;
    }
}
