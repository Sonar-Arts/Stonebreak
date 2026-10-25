package com.stonebreak.mobs.entities;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;
import com.stonebreak.core.Game;
import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.LivingEntity.DamageSource;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.ui.worldHud.DamageNumbers;

/**
 * Combat component of a {@link LivingEntity}: authoritative damage application (i-frames,
 * debuff multipliers, network-shadow forwarding, damage numbers and hurt/death voice),
 * death/XP credit to the local player, player knockback, and the Illusionist Fracture stubs
 * (bewildered timer, forced attack target). Extracted from {@code LivingEntity} (issue #233);
 * the entity remains the public facade.
 */
final class LivingEntityCombat {

    private static final float INVULNERABILITY_DURATION = 0.5f; // 500ms after taking damage

    /**
     * What an invulnerability window blocks: further hits from the same weapon. Melee is keyed
     * on the item swung, so switching weapons lands a fresh hit; everything else (arrows, fire,
     * abilities, bleed ticks) on its source alone ({@link LivingEntity#NO_WEAPON}).
     */
    private record HitKey(DamageSource source, int weaponId) {}

    private final LivingEntity owner;

    /** Seconds of invulnerability left, per weapon that recently landed a hit. */
    private final Map<HitKey, Float> invulnerability = new HashMap<>();

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
    }

    /** Counts down the post-hit invulnerability windows. */
    void updateInvulnerability(float deltaTime) {
        if (invulnerability.isEmpty()) return;
        invulnerability.replaceAll((key, seconds) -> seconds - deltaTime);
        invulnerability.values().removeIf(seconds -> seconds <= 0f);
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
     * on the server, so the hit is forwarded as an {@code EntityDamageC2S} intent. Damage
     * numbers and the hurt voice come back with the server's {@code KillCreditS2C}, so hits
     * the server rejects (i-frames, range) show nothing. (Mutating the shadow would also
     * wedge it permanently invulnerable — shadows never tick, so i-frames would never expire.)
     *
     * @param weaponId          item id of the melee weapon swung, or {@link LivingEntity#NO_WEAPON};
     *                          the invulnerability window only blocks that same weapon
     * @param attackerPos       authoritative attacker position for knockback direction, or
     *                          null when unknown (then no knockback)
     * @param creditLocalPlayer whether PLAYER-source stats/XP credit the local player; the
     *                          server passes false for remote attackers so the host isn't
     *                          credited for their kills
     * @param kind              on a shadow, which server rules validate the forwarded intent
     * @return whether the hit counted: forwarded to the server for a shadow (no network id,
     *         e.g. a remote player, means it was dropped), applied for a local entity
     *         (false when dead or invulnerable to this weapon)
     */
    boolean damage(float amount, DamageSource source, DamageType type, int weaponId,
                   Vector3f attackerPos, boolean creditLocalPlayer, HitKind kind) {
        Vector3f position = owner.position;
        float resistance = owner.getType().resistance(type);
        if (owner.isNetworkShadow()) {
            if (owner.getNetworkId() < 0 || !(amount > 0f)) {
                return false;
            }
            // No predicted damage number / hurt voice: the server may refuse the hit
            // (range, cooldown, the real mob's i-frames). Both play from its result
            // (KillCreditS2C) on this client — the only place the attacker can see/hear it.
            MultiplayerSession.onLocalEntityDamage(owner, amount, source, type, kind);
            return true;
        }
        HitKey key = new HitKey(source, weaponId);
        if (!owner.alive || invulnerability.containsKey(key)) return false;
        float effectiveAmount = amount * owner.getIncomingDamageMultiplier(source) * resistance;
        if (source == DamageSource.ARCANE) {
            effectiveAmount *= owner.statusEffects().consumeSpellmark();
        }
        owner.applyBaseDamage(effectiveAmount);
        invulnerability.put(key, INVULNERABILITY_DURATION);
        // Damage numbers are client UI — only spawn them for entities living in the world
        // being rendered. Authoritative entities live in the headless server world, where
        // this would emit a duplicate of the client's predicted number from the tick thread.
        if (Game.getWorld() == owner.world) {
            DamageNumbers.getInstance().spawn(
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
        if (!owner.alive && owner.world != null && !owner.world.isRenderOnly()) {
            // A death is the loudest thing there is (#321): nearby mobs come to look.
            com.stonebreak.mobs.entities.ai.perception.NoiseBus.emitIn(owner.world, position,
                com.stonebreak.mobs.entities.ai.perception.Loudness.DEATH);
        }
        if (attackerPos != null && owner.awareness != null && isHostileSource(source)) {
            owner.awareness.onAttacked(attackerPos); // no-op once dead: a silent kill stays silent
        }
        owner.onDamage(effectiveAmount, source);
        return true;
    }

    /** Sources that mean someone attacked this entity, and so give away where they are. */
    private static boolean isHostileSource(DamageSource source) {
        return source == DamageSource.PLAYER || source == DamageSource.ARCANE || source == DamageSource.ARROW;
    }

    /**
     * Applies knockback away from the attacking player. Without a known attacker there is no
     * direction to push in, so there is no knockback — never a guess from the client's local
     * player, which is another thread's state when this runs on the server tick (#340).
     */
    void applyPlayerKnockback() {
        Vector3f attackerPos = lastAttackerPosition;
        if (attackerPos == null) {
            return;
        }
        Vector3f knockbackDir = new Vector3f(owner.position).sub(attackerPos);
        knockbackDir.y = 0;
        if (knockbackDir.length() > 0.01f) {
            knockbackDir.normalize();
            owner.applyKnockback(knockbackDir, 3.0f, 1.5f);
        }
    }

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
