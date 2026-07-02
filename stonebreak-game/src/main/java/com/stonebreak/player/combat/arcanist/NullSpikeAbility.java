package com.stonebreak.player.combat.arcanist;

import static com.stonebreak.player.PlayerConstants.ARCANIST_INT_SCALING;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_BASE_DAMAGE;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_BURST_DAMAGE;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_MANA_COST;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_SPELLMARKED_DURATION;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_STACK_BONUS;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.magic.AbilitySpell;
import com.stonebreak.player.combat.magic.MagicSchool;
import com.stonebreak.player.combat.magic.SpellCast;
import org.joml.Vector3f;

/**
 * Arcanist active (ARCANA): fires a fast arcane projectile that damages and Spellmarks
 * the first enemy hit. Damage scales with the Resonance stacks held at the moment of
 * firing. Overloaded casts pierce every enemy in the line (Spellmark duration doubled)
 * and detonate a radial burst where the spike ends.
 * State machine: IDLE -&gt; COOLDOWN; the projectile is an independent world entity.
 */
public class NullSpikeAbility implements AbilitySpell {

    private enum State { IDLE, COOLDOWN }

    private State state = State.IDLE;
    private float cooldownRemaining;

    @Override
    public MagicSchool getMagicSchool() {
        return MagicSchool.ARCANA;
    }

    @Override
    public float getBaseManaCost() {
        return NULL_SPIKE_MANA_COST;
    }

    @Override
    public boolean isActive() {
        return state != State.IDLE;
    }

    @Override
    public boolean isOnCooldown() {
        return state == State.COOLDOWN;
    }

    @Override
    public float getCooldownRemaining() {
        return state == State.COOLDOWN ? cooldownRemaining : 0f;
    }

    @Override
    public boolean tryActivate(Player player, SpellCast cast) {
        if (state != State.IDLE) return false;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return false;

        int intelligence = player.getCharacterStats().getIntelligence();
        // INT scaling is multiplicative like Leyline Breach and the burst (the design
        // doc's additive form would be a no-op +0.4 damage at INT 10).
        float damagePerHit = NULL_SPIKE_BASE_DAMAGE * (1f + ARCANIST_INT_SCALING * intelligence)
            * (1f + NULL_SPIKE_STACK_BONUS * cast.stacksAtCast())
            * cast.damageMult();
        float spellmarkDuration = cast.overloaded()
            ? NULL_SPIKE_SPELLMARKED_DURATION * 2f
            : NULL_SPIKE_SPELLMARKED_DURATION;
        float burstDamage = NULL_SPIKE_BURST_DAMAGE * (1f + ARCANIST_INT_SCALING * intelligence);

        Vector3f direction = new Vector3f(player.getCamera().getFront()).normalize();
        Vector3f spawnPos = new Vector3f(player.getCamera().getPosition())
            .add(new Vector3f(direction).mul(0.5f));
        // Server-authoritative spawn (replicated to all); local fallback only with no session.
        if (!com.stonebreak.network.MultiplayerSession.sendProjectileSpawn(
                com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_NULL_SPIKE,
                spawnPos, direction,
                damagePerHit, spellmarkDuration, cast.overloaded() ? 1f : 0f, burstDamage)) {
            entityManager.spawnNullSpike(spawnPos, direction, damagePerHit, spellmarkDuration,
                cast.overloaded(), burstDamage);
        }

        state = State.COOLDOWN;
        cooldownRemaining = NULL_SPIKE_COOLDOWN;
        return true;
    }

    @Override
    public void update(float deltaTime, Player player) {
        if (state == State.COOLDOWN) {
            cooldownRemaining -= deltaTime;
            if (cooldownRemaining <= 0f) {
                state = State.IDLE;
            }
        }
    }

    @Override
    public void reset() {
        state = State.IDLE;
        cooldownRemaining = 0f;
    }
}
