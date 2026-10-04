package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;
import com.stonebreak.world.TimeOfDay;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Night-time goblin bands. Ticked by {@link EntitySpawner} on the authoritative server world, and
 * borrows its anchors (where the players are) and its standable-site finders, so hostile and
 * passive spawns can never disagree about what ground a mob may stand on.
 *
 * <p>Bands of {@link #MIN_BAND}–{@link #MAX_BAND} spawn {@link #MIN_DISTANCE}–{@link #MAX_DISTANCE}
 * blocks from a player, only at night, up to {@link #CAP} goblins near the players. Goblins that
 * end up far from everyone despawn, so bands don't pile up across nights.
 */
final class HostileSpawner {

    private static final float SPAWN_INTERVAL_SECONDS = 20.0f;
    private static final float DESPAWN_INTERVAL_SECONDS = 1.0f;

    static final int CAP = 8;
    static final int MIN_BAND = 2;
    static final int MAX_BAND = 4;
    /** Ring the band's first goblin lands in; {@link EntitySpawner#findSpawnNear} keeps 24 as its floor. */
    static final int MIN_DISTANCE = 24;
    static final int MAX_DISTANCE = 48;
    /** Farther than this from every player and a goblin is gone. */
    static final int DESPAWN_DISTANCE = 64;

    private final EntitySpawner sites;
    private final EntityManager entityManager;
    private final Random random;

    private BooleanSupplier isNight = HostileSpawner::clientNight;
    private float spawnTimer;
    private float despawnTimer;

    HostileSpawner(EntitySpawner sites, EntityManager entityManager, Random random) {
        this.sites = sites;
        this.entityManager = entityManager;
        this.random = random;
    }

    void setNightSource(BooleanSupplier isNight) {
        this.isNight = isNight != null ? isNight : HostileSpawner::clientNight;
    }

    private static boolean clientNight() {
        TimeOfDay time = Game.getTimeOfDay();
        return time != null && time.isNight();
    }

    void update(float deltaTime) {
        spawnTimer += deltaTime;
        if (spawnTimer >= SPAWN_INTERVAL_SECONDS) {
            spawnTimer = 0f;
            if (isNight.getAsBoolean()) {
                spawnBand();
            }
        }
        despawnTimer += deltaTime;
        if (despawnTimer >= DESPAWN_INTERVAL_SECONDS) {
            despawnTimer = 0f;
            despawnStragglers();
        }
    }

    private void spawnBand() {
        List<EntitySpawner.SpawnAnchor> anchors = sites.collectAnchors();
        int room = CAP - goblinCount();
        if (anchors.isEmpty() || room < MIN_BAND) {
            return;
        }
        EntitySpawner.SpawnAnchor anchor = anchors.get(random.nextInt(anchors.size()));
        Vector3f spot = sites.findSpawnNear(anchor.position(), MAX_DISTANCE);
        if (spot == null) {
            return; // try again next interval
        }
        int band = Math.min(room, MIN_BAND + random.nextInt(MAX_BAND - MIN_BAND + 1));
        for (int i = 0; i < band; i++) {
            Vector3f pos = i == 0 ? spot : sites.jitterStandable(spot);
            if (pos != null && sites.isValidSpawnLocation(pos, EntityType.GOBLIN)) {
                entityManager.spawnEntity(EntityType.GOBLIN, pos);
            }
        }
    }

    private void despawnStragglers() {
        List<EntitySpawner.SpawnAnchor> anchors = sites.collectAnchors();
        if (anchors.isEmpty()) {
            return;
        }
        for (Entity entity : entityManager.getAllEntitiesIncludingPending()) {
            if (entity.isAlive() && entity.getType() == EntityType.GOBLIN && !entity.isCommandSpawned()
                    && !withinDistance(entity.getPosition(), anchors, DESPAWN_DISTANCE)) {
                entityManager.removeEntity(entity);
            }
        }
    }

    private int goblinCount() {
        int count = 0;
        for (Entity entity : entityManager.getAllEntitiesIncludingPending()) {
            if (entity.isAlive() && entity.getType() == EntityType.GOBLIN) {
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
