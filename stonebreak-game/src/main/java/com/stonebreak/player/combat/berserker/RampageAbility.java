package com.stonebreak.player.combat.berserker;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.stonebreak.player.PlayerConstants.*;

/**
 * Berserker active: charges forward in a straight line, damaging everything in the path.
 * Tier-at-cast determines range, whether a burning trail is left behind, and whether the
 * charge becomes a full-width staggering cleave. State machine: IDLE -&gt; CHARGING -&gt; COOLDOWN.
 */
public class RampageAbility {

    private static final float COLLISION_STEP = 0.2f;

    private enum State { IDLE, CHARGING, COOLDOWN }

    /** A burning patch left behind by a T2 charge; ticks burning onto anything standing in it. */
    private static final class TrailZone {
        final Vector3f position;
        float remaining;
        TrailZone(Vector3f position, float remaining) {
            this.position = position;
            this.remaining = remaining;
        }
    }

    private State state = State.IDLE;
    private float cooldownRemaining;

    private RageTier tier = RageTier.NONE;
    private final Vector3f direction = new Vector3f();
    private float distanceTraveled;
    private float range;
    private float cleaveHalfWidth;
    private final Set<LivingEntity> hitEntities = new HashSet<>();
    private final List<TrailZone> trailZones = new ArrayList<>();
    private float distanceSinceLastWaypoint;

    public boolean isActive() { return state != State.IDLE; }
    public boolean isOnCooldown() { return state == State.COOLDOWN; }

    /** Attempts to begin a charge at the given (pre-consumption) Rage tier. Returns true if the cast started. */
    public boolean tryActivate(Player player, RageTier tierAtCast) {
        if (state != State.IDLE) return false;

        Vector3f front = player.getCamera().getFront();
        direction.set(front.x, 0f, front.z);
        if (direction.lengthSquared() < 0.0001f) {
            direction.set(0f, 0f, -1f);
        }
        direction.normalize();

        this.tier = tierAtCast;
        this.range = tierAtCast.atLeast(RageTier.T1) ? RAMPAGE_T1_RANGE : RAMPAGE_BASE_RANGE;
        this.cleaveHalfWidth = tierAtCast == RageTier.T3 ? computeCleaveHalfWidth(player) : RAMPAGE_HIT_RADIUS;
        this.distanceTraveled = 0f;
        this.distanceSinceLastWaypoint = 0f;
        this.hitEntities.clear();
        this.trailZones.clear();
        this.state = State.CHARGING;
        return true;
    }

    public void update(float deltaTime, Player player) {
        switch (state) {
            case CHARGING -> updateCharge(deltaTime, player);
            case COOLDOWN -> {
                cooldownRemaining -= deltaTime;
                if (cooldownRemaining <= 0f) {
                    state = State.IDLE;
                }
            }
            case IDLE -> { }
        }
        updateTrailZones(deltaTime);
    }

    private void updateCharge(float deltaTime, Player player) {
        World world = Game.getWorld();
        if (world == null) {
            endCharge();
            return;
        }

        float remaining = Math.min(RAMPAGE_SPEED * deltaTime, range - distanceTraveled);
        boolean stoppedByWall = false;

        while (remaining > 0f) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            Vector3f newPos = new Vector3f(direction).mul(stepLen).add(player.getPosition());

            if (isBlocked(world, newPos)) {
                stoppedByWall = true;
                break;
            }

            player.setPosition(newPos);
            distanceTraveled += stepLen;
            distanceSinceLastWaypoint += stepLen;
            remaining -= stepLen;

            applyHits(player, newPos);

            if (tier == RageTier.T2 && distanceSinceLastWaypoint >= RAMPAGE_TRAIL_WAYPOINT_GAP) {
                distanceSinceLastWaypoint = 0f;
                trailZones.add(new TrailZone(new Vector3f(newPos), RAMPAGE_TRAIL_DURATION));
            }
        }

        if (stoppedByWall || distanceTraveled >= range) {
            endCharge();
        }
    }

    private void endCharge() {
        state = State.COOLDOWN;
        cooldownRemaining = RAMPAGE_COOLDOWN;
    }

    private boolean isBlocked(World world, Vector3f pos) {
        return isSolidAt(world, pos.x, pos.y + 0.1f, pos.z)
            || isSolidAt(world, pos.x, pos.y + PlayerConstants.PLAYER_HEIGHT * 0.6f, pos.z);
    }

    private boolean isSolidAt(World world, float x, float y, float z) {
        BlockType block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return block != null && block.isSolid();
    }

    /** Damages any not-yet-hit living entity within the charge's effective hitbox around {@code currentPos}. */
    private void applyHits(Player player, Vector3f currentPos) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive() || hitEntities.contains(entity)) continue;

            Vector3f rel = entity.getPosition().sub(currentPos);
            float longitudinal = rel.dot(direction);
            Vector3f perpendicular = new Vector3f(rel).sub(new Vector3f(direction).mul(longitudinal));

            if (Math.abs(longitudinal) <= RAMPAGE_HIT_RADIUS && perpendicular.length() <= cleaveHalfWidth) {
                hitEntities.add(entity);
                entity.damage(RAMPAGE_DAMAGE, LivingEntity.DamageSource.PLAYER);

                if (tier == RageTier.T3) {
                    Vector3f knockbackDir = perpendicular.lengthSquared() > 0.0001f
                        ? new Vector3f(perpendicular).normalize()
                        : new Vector3f(direction);
                    entity.applyKnockback(knockbackDir, RAMPAGE_KNOCKBACK_HORIZONTAL, RAMPAGE_KNOCKBACK_VERTICAL);
                    entity.applyStatusEffect(StatusEffectType.STUNNED, RAMPAGE_STAGGER_DURATION, 0f);
                }
            }
        }
    }

    /** Scans perpendicular to the charge direction for the nearest walls, capped at a sane maximum. */
    private float computeCleaveHalfWidth(Player player) {
        World world = Game.getWorld();
        if (world == null) return RAMPAGE_HIT_RADIUS;

        Vector3f perpendicular = new Vector3f(-direction.z, 0f, direction.x);
        Vector3f origin = player.getPosition();
        float halfWidth = RAMPAGE_HIT_RADIUS;

        for (float dist = RAMPAGE_HIT_RADIUS; dist <= RAMPAGE_MAX_CLEAVE_HALF_WIDTH; dist += 0.5f) {
            Vector3f posPositive = new Vector3f(perpendicular).mul(dist).add(origin);
            Vector3f posNegative = new Vector3f(perpendicular).mul(-dist).add(origin);
            boolean positiveBlocked = isBlocked(world, posPositive);
            boolean negativeBlocked = isBlocked(world, posNegative);
            if (positiveBlocked || negativeBlocked) {
                break;
            }
            halfWidth = dist;
        }
        return halfWidth;
    }

    private void updateTrailZones(float deltaTime) {
        if (trailZones.isEmpty()) return;

        EntityManager entityManager = Game.getEntityManager();
        List<LivingEntity> living = entityManager != null ? entityManager.getLivingEntities() : List.of();

        var it = trailZones.iterator();
        while (it.hasNext()) {
            TrailZone zone = it.next();
            zone.remaining -= deltaTime;
            if (zone.remaining <= 0f) {
                it.remove();
                continue;
            }
            for (LivingEntity entity : living) {
                if (!entity.isAlive()) continue;
                if (entity.getPosition().distance(zone.position) <= RAMPAGE_HIT_RADIUS) {
                    entity.applyStatusEffect(StatusEffectType.BURNING, zone.remaining, RAMPAGE_TRAIL_TICK_DAMAGE);
                }
            }
        }
    }
}
