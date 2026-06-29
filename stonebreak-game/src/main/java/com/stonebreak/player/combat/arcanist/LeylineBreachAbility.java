package com.stonebreak.player.combat.arcanist;

import static com.stonebreak.player.PlayerConstants.ARCANIST_INT_DURATION_SCALING;
import static com.stonebreak.player.PlayerConstants.ARCANIST_INT_SCALING;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_DURATION;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_MANA_COST;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_MAX_RANGE;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_OVERLOAD_DAMAGE_MULT;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_OVERLOAD_PULL_MULT;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_OVERLOAD_RADIUS_MULT;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_PULL_FORCE;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_PULSE_BASE;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_RADIUS;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_ROOT_DURATION;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.magic.AbilitySpell;
import com.stonebreak.player.combat.magic.MagicSchool;
import com.stonebreak.player.combat.magic.SpellCast;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Arcanist active (CONJURATION): tears open a leyline at a targeted ground position,
 * spawning a persistent zone that pulses arcane damage (applying Amplified), and drags
 * enemies toward its center. Overloaded casts widen the zone, strengthen the pull and
 * pulses, and root everything caught inside at the moment of casting.
 * State machine: IDLE -&gt; COOLDOWN; the spawned zone is an independent world entity.
 */
public class LeylineBreachAbility implements AbilitySpell {

    private enum State { IDLE, COOLDOWN }

    private State state = State.IDLE;
    private float cooldownRemaining;

    @Override
    public MagicSchool getMagicSchool() {
        return MagicSchool.CONJURATION;
    }

    @Override
    public float getBaseManaCost() {
        return LEYLINE_BREACH_MANA_COST;
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

        // Fail without consuming the cooldown when no ground is in range (Snare pattern)
        Vector3i blockHit = player.getRaycastEngine().raycast(LEYLINE_BREACH_MAX_RANGE);
        if (blockHit == null) return false;
        Vector3f center = new Vector3f(blockHit.x + 0.5f, blockHit.y + 1f, blockHit.z + 0.5f);

        int intelligence = player.getCharacterStats().getIntelligence();
        float duration = LEYLINE_BREACH_DURATION * (1f + ARCANIST_INT_DURATION_SCALING * intelligence);
        float pulseDamage = LEYLINE_BREACH_PULSE_BASE * (1f + ARCANIST_INT_SCALING * intelligence)
            * cast.damageMult();
        float radius = LEYLINE_BREACH_RADIUS;
        float pullForce = LEYLINE_BREACH_PULL_FORCE;

        if (cast.overloaded()) {
            radius *= LEYLINE_BREACH_OVERLOAD_RADIUS_MULT;
            pullForce *= LEYLINE_BREACH_OVERLOAD_PULL_MULT;
            pulseDamage *= LEYLINE_BREACH_OVERLOAD_DAMAGE_MULT;
            rootEnemiesInZone(center, radius);
        }

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return false;
        entityManager.spawnLeylineBreachZone(center, radius, pullForce, pulseDamage,
            duration, cast.overloaded());

        state = State.COOLDOWN;
        cooldownRemaining = LEYLINE_BREACH_COOLDOWN;
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

    /**
     * Roots everything inside the overloaded zone at the moment of casting. Done here
     * rather than in the zone: the spawned entity only enters the manager's list on the
     * next update tick.
     */
    private void rootEnemiesInZone(Vector3f center, float radius) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            if (entity.getPosition().distance(center) > radius) continue;
            entity.applyStatusEffect(StatusEffectType.ROOT, LEYLINE_BREACH_ROOT_DURATION, 0f);
        }
    }
}
