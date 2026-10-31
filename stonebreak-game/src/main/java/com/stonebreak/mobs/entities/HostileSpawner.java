package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Night-time hostile spawns, one {@link HostileSpawnRule} per creature: goblin bands everywhere
 * (seldom in the Shade), the Wisp for whoever lingers in the Shade's fog. Ticked by {@link EntitySpawner} on the
 * authoritative server world, and borrows its anchors (where the players are) and its
 * standable-site finders, so hostile and passive spawns can never disagree about what ground a mob
 * may stand on.
 *
 * <p>Groups spawn {@link #MIN_DISTANCE}–{@link #MAX_DISTANCE} blocks from a player, up to each
 * rule's cap near the players. Hostiles that end up far from everyone despawn, so they don't pile
 * up across nights.
 */
final class HostileSpawner {

    private static final float SPAWN_INTERVAL_SECONDS = 20.0f;
    private static final float DESPAWN_INTERVAL_SECONDS = 1.0f;
    /** How often the players' Shade clocks are run on. */
    private static final float CLOCK_INTERVAL_SECONDS = 1.0f;
    /** Tries at a spot far enough out before a rule gives up for this interval. */
    private static final int SPOT_ATTEMPTS = 4;

    /** Ring the group's first mob lands in; {@link EntitySpawner#findSpawnNear} keeps 24 as its floor. */
    static final int MIN_DISTANCE = 24;
    static final int MAX_DISTANCE = 48;
    /** Farther than this from every player and a hostile is gone. */
    static final int DESPAWN_DISTANCE = 64;

    static final List<HostileSpawnRule> RULES = List.of(HostileSpawnRule.GOBLIN, HostileSpawnRule.WISP);

    private final EntitySpawner sites;
    private final EntityManager entityManager;
    private final Random random;
    /** A Shade clock for each rule that spawns on one, in place of the interval. */
    private final Map<HostileSpawnRule, ShadeClock> clocks = new LinkedHashMap<>();

    private BooleanSupplier isNight = HostileSpawner::clientNight;
    private float spawnTimer;
    private float despawnTimer;
    private float clockTimer;

    HostileSpawner(EntitySpawner sites, EntityManager entityManager, Random random) {
        this.sites = sites;
        this.entityManager = entityManager;
        this.random = random;
        for (HostileSpawnRule rule : RULES) {
            if (rule.shadeClockSeconds() > 0f) {
                clocks.put(rule, new ShadeClock(rule.shadeClockSeconds()));
            }
        }
    }

    void setNightSource(BooleanSupplier isNight) {
        this.isNight = isNight != null ? isNight : HostileSpawner::clientNight;
    }

    private static boolean clientNight() {
        TimeOfDay time = Game.getTimeOfDay();
        return time != null && time.isNight();
    }

    void update(float deltaTime) {
        clockTimer += deltaTime;
        if (clockTimer >= CLOCK_INTERVAL_SECONDS) {
            runClocks(clockTimer);
            clockTimer = 0f;
        }
        spawnTimer += deltaTime;
        if (spawnTimer >= SPAWN_INTERVAL_SECONDS) {
            spawnTimer = 0f;
            boolean night = isNight.getAsBoolean();
            for (HostileSpawnRule rule : RULES) {
                if (!clocks.containsKey(rule) && rule.when().allows(sites.world(), night)) {
                    spawnGroup(rule);
                }
            }
        }
        despawnTimer += deltaTime;
        if (despawnTimer >= DESPAWN_INTERVAL_SECONDS) {
            despawnTimer = 0f;
            despawnStragglers();
        }
    }

    /** Runs the Shade clocks on; each that comes round while its rule's time is right spawns near its player. */
    private void runClocks(float elapsed) {
        World world = sites.world();
        List<EntitySpawner.SpawnAnchor> anchors = sites.collectAnchors();
        boolean night = isNight.getAsBoolean();
        clocks.forEach((rule, clock) -> {
            for (EntitySpawner.SpawnAnchor anchor : clock.update(anchors, elapsed,
                    point -> HostileSpawnRule.isShade(world, point))) {
                if (rule.when().allows(world, night)) {
                    spawnGroupNear(rule, anchor);
                }
            }
        });
    }

    private void spawnGroup(HostileSpawnRule rule) {
        List<EntitySpawner.SpawnAnchor> anchors = sites.collectAnchors();
        if (!anchors.isEmpty()) {
            spawnGroupNear(rule, anchors.get(random.nextInt(anchors.size())));
        }
    }

    private void spawnGroupNear(HostileSpawnRule rule, EntitySpawner.SpawnAnchor anchor) {
        int room = rule.cap() - count(rule.type());
        if (room < rule.groupMin()) {
            return;
        }
        Vector3f spot = findSpot(rule, anchor.position());
        if (spot == null || !roll(rule.chance().at(sites.world(), spot))) {
            return; // try again next interval
        }
        int size = Math.min(room, rule.groupMin() + random.nextInt(rule.groupMax() - rule.groupMin() + 1));
        for (int i = 0; i < size; i++) {
            Vector3f pos = i == 0 ? spot : sites.jitterStandable(spot);
            if (pos != null && sites.isValidSpawnLocation(pos, rule.type())
                    && rule.where().allows(sites.world(), pos, anchor.position())) {
                entityManager.spawnEntity(rule.type(), pos);
            }
        }
    }

    private boolean roll(float chance) {
        return chance >= 1f || random.nextFloat() < chance;
    }

    private Vector3f findSpot(HostileSpawnRule rule, Vector3f anchor) {
        World world = sites.world();
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            Vector3f spot = sites.findSpawnNear(anchor, MAX_DISTANCE);
            if (spot != null && spot.distance(anchor) >= rule.minDistance()
                    && rule.where().allows(world, spot, anchor)) {
                return spot;
            }
        }
        return null;
    }

    private void despawnStragglers() {
        List<EntitySpawner.SpawnAnchor> anchors = sites.collectAnchors();
        if (anchors.isEmpty()) {
            return;
        }
        for (Entity entity : entityManager.getAllEntitiesIncludingPending()) {
            if (entity.isAlive() && isRuled(entity.getType()) && !entity.isCommandSpawned()
                    && !withinDistance(entity.getPosition(), anchors, DESPAWN_DISTANCE)) {
                entityManager.removeEntity(entity);
            }
        }
    }

    private static boolean isRuled(EntityType type) {
        for (HostileSpawnRule rule : RULES) {
            if (rule.type() == type) {
                return true;
            }
        }
        return false;
    }

    private int count(EntityType type) {
        int count = 0;
        for (Entity entity : entityManager.getAllEntitiesIncludingPending()) {
            if (entity.isAlive() && entity.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private static boolean withinDistance(Vector3f point, List<EntitySpawner.SpawnAnchor> anchors, float distance) {
        for (EntitySpawner.SpawnAnchor anchor : anchors) {
            if (point.distance(anchor.position()) <= distance) {
                return true;
            }
        }
        return false;
    }
}
