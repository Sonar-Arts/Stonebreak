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
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;
import com.stonebreak.rendering.effects.IllusionSmokeParticles;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Illusionist active: Mirrored Deceit. Spawns two {@link IllusionDecoy} figures that fan out from
 * the player (offset 120° and 240°) and mirror the player's movement and attack pose, baiting
 * enemies into striking illusions. A decoy has 1 HP, so any hit destroys it; on death the killer is
 * slowed ({@link StatusEffectType#CRIPPLE}), Revealed through terrain ({@link StatusEffectType#REVEALED}),
 * and gains a stack of Doubt. The cooldown only begins once the last decoy is gone (death or the
 * duration lapsing), not on cast.
 */
public class MirroredDeceitAbility {

    private static final int DECOY_COUNT = IllusionDecoy.fanAngles().length;

    private boolean active;
    private float cooldownRemaining;
    private float durationRemaining;
    private final List<IllusionDecoy> decoys = new ArrayList<>();
    private final IllusionSmokeParticles smoke = new IllusionSmokeParticles();
    /**
     * True when this cast's decoys live on the server (#341): they arrive as replicated shadows
     * and are adopted into {@link #decoys} by owner id; {@link #adoptedIds} holds the network
     * ids already taken so a re-sent spawn is never adopted twice.
     */
    private boolean serverDecoys;
    private final Set<Integer> adoptedIds = new HashSet<>();

    public boolean isActive() { return active; }
    public boolean isOnCooldown() { return cooldownRemaining > 0f; }
    public float getCooldownRemaining() { return cooldownRemaining; }
    public IllusionSmokeParticles getSmoke() { return smoke; }

    /**
     * Casts the decoys at the player's position. In a session the SERVER spawns them (#341), so
     * server mobs can target them and every player sees them; only without one (battle-test
     * arena) are they spawned locally. Fails (no cooldown consumed) when the ability is still
     * active or on cooldown, or there is neither a session nor a local world.
     */
    public boolean tryActivate(Player player) {
        if (active || cooldownRemaining > 0f) return false;
        Vector3f spawnPos = player.getPosition();
        if (MultiplayerSession.sendDecoyCast()) {
            serverDecoys = true;
            adoptedIds.clear();
        } else if (!spawnLocalDecoys(player)) {
            return false;
        }
        smoke.burst(new Vector3f(spawnPos.x, spawnPos.y + 1f, spawnPos.z));

        active = true;
        durationRemaining = com.stonebreak.player.PlayerConstants.ILLUSIONIST_DECOY_DURATION;
        return true;
    }

    /** No-session fallback: owner-driven decoys in the local world, mirroring {@code player}. */
    private boolean spawnLocalDecoys(Player player) {
        World world = Game.getWorld();
        EntityManager entityManager = Game.getEntityManager();
        if (world == null || entityManager == null) return false;

        serverDecoys = false;
        IllusionDecoy.MirrorSource source = mirrorOf(player);
        com.stonebreak.items.Item held = selectedItem(player);
        for (float angle : IllusionDecoy.fanAngles()) {
            IllusionDecoy decoy = new IllusionDecoy(world, new Vector3f(player.getPosition()), source, angle);
            decoy.setHeldItem(held);
            entityManager.addEntity(decoy);
            decoys.add(decoy);
        }
        return true;
    }

    /** Adopts this player's replicated decoy shadows as they arrive (server-spawned casts). */
    private void adoptServerDecoys() {
        if (adoptedIds.size() >= DECOY_COUNT || durationRemaining <= 0f) return;
        EntityManager entityManager = Game.getEntityManager();
        int me = MultiplayerSession.localPlayerId();
        if (entityManager == null || me < 0) return;
        for (com.stonebreak.mobs.entities.Entity e
                : entityManager.getEntitiesByType(com.stonebreak.mobs.entities.EntityType.ILLUSION_DECOY)) {
            if (e instanceof IllusionDecoy decoy && decoy.isNetworkShadow()
                    && decoy.getOwnerPlayerId() == me && adoptedIds.add(decoy.getNetworkId())) {
                decoys.add(decoy);
                if (adoptedIds.size() >= DECOY_COUNT) return;
            }
        }
    }

    /** The local player as a decoy mirror source (no-session fallback). */
    private static IllusionDecoy.MirrorSource mirrorOf(Player player) {
        return new IllusionDecoy.MirrorSource() {
            @Override
            public Vector3f position() {
                return player.getPosition();
            }

            @Override
            public float bodyYaw() {
                return player.getBodyYaw();
            }

            @Override
            public float lookYaw() {
                double yawRad = Math.toRadians(player.getCamera().getYaw());
                return com.stonebreak.player.PlayerBodyOrientation.modelYawFromDirection(
                        (float) Math.cos(yawRad), (float) Math.sin(yawRad));
            }
        };
    }

    public void update(float deltaTime, Player player, DoubtController doubt) {
        if (cooldownRemaining > 0f) {
            cooldownRemaining -= deltaTime;
        }
        smoke.update(deltaTime);

        if (!active) return;

        boolean fakeCasting = player.isAttacking();
        durationRemaining -= deltaTime;
        // Track the caster's current hotbar selection so decoys mirror item swaps mid-cast.
        com.stonebreak.items.Item held = selectedItem(player);
        if (serverDecoys) {
            adoptServerDecoys();
        }

        Iterator<IllusionDecoy> it = decoys.iterator();
        while (it.hasNext()) {
            IllusionDecoy decoy = it.next();
            if (!decoy.isAlive()) {
                if (decoy.isNetworkShadow()) {
                    // The server's decoys cannot be struck yet (IllusionDecoy), so a despawn is
                    // its clock running out — usually a little ahead of ours, since our frame
                    // delta is clamped. Never punish a bystander mob for that.
                    dismiss(decoy);
                } else {
                    onDecoyDestroyed(decoy, doubt);
                }
                it.remove();
                continue;
            }
            if (durationRemaining <= 0f) {
                // Duration lapsed — dismiss the decoy quietly (no punish, it wasn't struck).
                dismiss(decoy);
                it.remove();
                continue;
            }
            // The decoy moves itself (the server's, or a local one mirroring us); these only
            // set what it shows. On a shadow the attack pose rides the replicated-style flag.
            decoy.setFakeCasting(fakeCasting);
            decoy.setHeldItem(held);
            if (decoy.isNetworkShadow()) {
                decoy.setStateFlags((short) (fakeCasting ? PlayerStateFlags.ATTACKING : 0));
            }
        }

        // A server cast is over once its decoys have all arrived and gone, or its time is up
        // (decoys that never arrived — a refused cast — must not hold the ability forever).
        boolean allArrived = !serverDecoys || adoptedIds.size() >= DECOY_COUNT;
        if (decoys.isEmpty() && (allArrived || durationRemaining <= 0f)) {
            active = false;
            cooldownRemaining = ILLUSIONIST_MIRRORED_DECEIT_COOLDOWN;
        }
    }

    /** The caster's currently selected hotbar item, or {@code null} when empty-handed. */
    private static com.stonebreak.items.Item selectedItem(Player player) {
        com.stonebreak.items.ItemStack selected = player.getInventory().getSelectedHotbarSlot();
        return (selected == null || selected.isEmpty()) ? null : selected.getItem();
    }

    /** Clears decoys and cooldown (world reload — decoy references go stale). */
    public void reset() {
        for (IllusionDecoy decoy : decoys) {
            decoy.setAlive(false);
        }
        decoys.clear();
        adoptedIds.clear();
        serverDecoys = false;
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
     *
     * <p>Only mobs qualify: remote players (and decoys, a RemotePlayer subclass) cannot take the
     * punishment until PvP exists (#343/#374). On a mob's network copy the CRIPPLE/REVEALED/Doubt
     * effects are forwarded to the server and applied to the real mob (#339).
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
            if (entity == decoy || !Player.isCombatTarget(entity)) continue;
            float distSq = entity.getPosition().distanceSquared(reference);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entity;
            }
        }
        return nearest;
    }
}
