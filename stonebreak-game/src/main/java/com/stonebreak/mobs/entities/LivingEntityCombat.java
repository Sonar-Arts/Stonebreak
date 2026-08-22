package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity.DamageSource;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;

/**
 * Combat component of a {@link LivingEntity}: authoritative damage application (i-frames,
 * debuff multipliers, network-shadow forwarding, damage numbers and hurt/death voice),
 * death/XP credit to the local player, player knockback, and the Illusionist Fracture stubs
 * (bewildered timer, forced attack target). Extracted from {@code LivingEntity} (issue #233);
 * the entity remains the public facade.
 */
final class LivingEntityCombat {

    private static final float INVULNERABILITY_DURATION = 0.5f; // 500ms after taking damage

    private final LivingEntity owner;

    private boolean invulnerable;
    private float invulnerabilityTimer;

    /**
     * Position of the most recent attacker, set per damage application. Lets knockback push
     * away from the actual attacker (e.g. a remote player on a host) instead of always the
     * local player. Null when the attacker is unknown or is the local player.
     */
    private Vector3f lastAttackerPosition;

    /**
     * Illusionist Fracture stubs. {@code bewilderedTimer} counts down while the entity is in a
     * panic/friendly-fire state; {@code forcedAttackTarget} names an entity the AI should attack
     * next instead of its normal selection. Both are inert today (no hostile mob AI exists yet)
     * and will be consulted once hostile target selection is implemented.
     */
    private float bewilderedTimer;
    private LivingEntity forcedAttackTarget;

    LivingEntityCombat(LivingEntity owner) {
        this.owner = owner;
        this.invulnerable = false;
        this.invulnerabilityTimer = 0.0f;
    }

    /** Counts down the post-hit invulnerability window. */
    void updateInvulnerability(float deltaTime) {
        if (invulnerable) {
            invulnerabilityTimer -= deltaTime;
            if (invulnerabilityTimer <= 0) {
                invulnerable = false;
                invulnerabilityTimer = 0;
            }
        }
    }

    /** Ticks the Illusionist Bewildered/panic timer; clears the forced target when it lapses. */
    void updateBewildered(float deltaTime) {
        if (bewilderedTimer > 0f) {
            bewilderedTimer -= deltaTime;
            if (bewilderedTimer <= 0f) {
                bewilderedTimer = 0f;
                forcedAttackTarget = null;
            }
        }
    }

    /**
     * Authoritative damage application.
     *
     * <p>On a network shadow this never mutates local state: the authoritative entity lives
     * on the server, so the hit is forwarded as an {@code EntityDamageC2S} intent and a
     * predicted damage number is shown for feedback. (Mutating the shadow would also wedge
     * it permanently invulnerable — shadows never tick, so i-frames would never expire.)
     *
     * @param attackerPos       authoritative attacker position for knockback direction, or
     *                          null to fall back to the local player's position
     * @param creditLocalPlayer whether PLAYER-source stats/XP credit the local player; the
     *                          server passes false for remote attackers so the host isn't
     *                          credited for their kills
     */
    void damage(float amount, DamageSource source, Vector3f attackerPos, boolean creditLocalPlayer) {
        Vector3f position = owner.position;
        if (owner.isNetworkShadow()) {
            if (owner.getNetworkId() >= 0 && amount > 0f) {
                DamageNumberRenderer.getInstance().spawn(
                    position.x, position.y + owner.height * 0.9f, position.z, amount);
                // Predicted hurt voice, like the predicted damage number: the
                // authoritative entity lives in the headless server world, so
                // this shadow is the only place the local player can hear it.
                // Plays only when the entity's SBE declares a hurt event.
                com.stonebreak.audio.EntitySounds.playAt(owner,
                    com.stonebreak.audio.EntitySounds.EVENT_HURT);
                MultiplayerSession.onLocalEntityDamage(owner, amount, source);
            }
            return;
        }
        if (!owner.alive || invulnerable) return;
        float effectiveAmount = amount * owner.getIncomingDamageMultiplier(source);
        if (source == DamageSource.ARCANE) {
            effectiveAmount *= owner.statusEffects().consumeSpellmark();
        }
        owner.applyBaseDamage(effectiveAmount);
        invulnerable = true;
        invulnerabilityTimer = INVULNERABILITY_DURATION;
        // Damage numbers are client UI — only spawn them for entities living in the world
        // being rendered. Authoritative entities live in the headless server world, where
        // this would emit a duplicate of the client's predicted number from the tick thread.
        if (Game.getWorld() == owner.world) {
            DamageNumberRenderer.getInstance().spawn(
                position.x, position.y + owner.height * 0.9f, position.z, effectiveAmount);
            // Data-driven voice: plays only when the entity's SBE declares a
            // hurt/death sound event (SBE 1.4+); silent otherwise.
            com.stonebreak.audio.EntitySounds.playAt(owner,
                owner.alive ? com.stonebreak.audio.EntitySounds.EVENT_HURT
                            : com.stonebreak.audio.EntitySounds.EVENT_DEATH);
        }
        if ((source == DamageSource.PLAYER || source == DamageSource.ARCANE) && creditLocalPlayer) {
            Player player = Game.getPlayer();
            if (player != null) {
                player.getStats().addDamageDealt(effectiveAmount);
                if (!owner.alive) {
                    player.getStats().incrementEntitiesKilled();
                    player.getStats().incrementKillsForType(owner.getType());
                    int xpReward = owner.getXpReward();
                    if (xpReward > 0) {
                        player.getCharacterStats().addXp(xpReward);
                    }
                }
            }
        }
        lastAttackerPosition = attackerPos;
        owner.onDamage(effectiveAmount, source);
    }

    /**
     * Applies knockback away from the attacking player (the most recent attacker if known,
     * else the local player).
     */
    void applyPlayerKnockback() {
        Vector3f attackerPos = lastAttackerPosition;
        if (attackerPos == null) {
            Player player = Game.getPlayer();
            if (player == null) return;
            attackerPos = player.getPosition();
        }
        Vector3f knockbackDir = new Vector3f(owner.position).sub(attackerPos);
        knockbackDir.y = 0;
        if (knockbackDir.length() > 0.01f) {
            knockbackDir.normalize();
            owner.applyKnockback(knockbackDir, 3.0f, 1.5f);
        }
    }

    boolean isInvulnerable() { return invulnerable; }
    float getInvulnerabilityTimer() { return invulnerabilityTimer; }

    /** World position of the most recent attacker (null if unknown / the local player). */
    Vector3f getLastAttackerPosition() { return lastAttackerPosition; }

    /** Puts this entity into the Bewildered panic state for {@code duration} seconds. */
    void setBewildered(float duration) {
        this.bewilderedTimer = Math.max(this.bewilderedTimer, duration);
    }

    /** True while this entity is panicked (Fracture at full Doubt). */
    boolean isBewildered() { return bewilderedTimer > 0f; }

    void setForcedAttackTarget(LivingEntity target) { this.forcedAttackTarget = target; }
    LivingEntity getForcedAttackTarget() { return forcedAttackTarget; }
}
