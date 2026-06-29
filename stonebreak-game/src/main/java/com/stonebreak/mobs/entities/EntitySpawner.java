package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server-side passive mob spawner. Authoritative under the two-world model — only the headless
 * server's spawner is ticked (see {@code ServerLevel.tick}); the client render world never owns
 * a live spawner.
 *
 * <p><b>Single source of truth.</b> Passive mobs (cow/chicken/sheep) are populated entirely by a
 * continuous, visibility-capped cycle — there is no per-chunk one-shot spawn. The population target
 * is a dynamic cap sized to the <i>visible</i> world: the number of loaded, feature-populated chunks
 * within each connected player's view distance. Depopulated areas refill toward the cap over
 * successive cycles and stop once it's reached, so the world self-balances instead of accumulating
 * mobs without bound.
 *
 * <p><b>Cap.</b> {@code cap = round(DENSITY_PER_CHUNK × eligibleChunks)} where {@code eligibleChunks}
 * is the de-duplicated union, over every player anchor, of the {@code (2r+1)²} chunk square around
 * the player ({@code r} = that player's view distance) that is currently resident and feature-
 * populated. The density is tuned so the default view distance (~289 chunks) targets ~35 passives.
 *
 * <p><b>Counting.</b> All population counts read {@link EntityManager#getAllEntitiesIncludingPending()}
 * so mobs queued earlier in the same tick (not yet promoted into the live list) are counted — this
 * is what stops a single cycle from overspawning past the cap.
 *
 * <p><b>Despawning.</b> A passive despawns when farther than its view radius (+margin) from
 * <i>every</i> anchor. The sweep also enforces the cap, thinning the in-range population from the
 * outside in (gradually, never within {@link #CAP_PROTECTION_RADIUS}).
 *
 * <p>Public API: {@link #update}, {@link #setSpawnAnchorSource}, {@link #isValidSpawnLocation},
 * {@link #forceSpawnEntity}, {@link #spawnCowHerd}, {@link #getSpawnStats}.
 */
public class EntitySpawner {

    // ─── Tuning ───────────────────────────────────────────────────────────────
    /** Seconds of sim time between continuous spawn cycles. Short so freshly-loaded areas fill quickly. */
    private static final float PASSIVE_SPAWN_INTERVAL_SECONDS = 10.0f;
    /** Seconds between despawn sweeps; no need to scan every update. */
    private static final float DESPAWN_CHECK_INTERVAL_SECONDS = 1.0f;

    /**
     * Target passive density: ~35 mobs per the ~289-chunk default view area (vanilla-like spawn
     * area = a 17×17 chunk square). The cap scales linearly with the visible chunk count, so larger
     * render distances yield proportionally more animals.
     */
    private static final float DENSITY_PER_CHUNK = 35.0f / 289.0f;

    /** Continuous spawns must land at least this far from the target player. */
    private static final int MIN_SPAWN_DISTANCE = 24;
    /** A pack is this many mobs of one type, clustered around a found spot. */
    private static final int MIN_PACK = 2;
    private static final int MAX_PACK = 4;
    /** Horizontal spread (blocks) of pack members around the pack's anchor spot. */
    private static final int PACK_SPREAD = 5;
    /** Hard bound on spawns attempted per cycle, so one cycle can't stall the tick. */
    private static final int MAX_SPAWN_PER_CYCLE = 16;

    /** Mobs nearer than this to any player are never cap-culled (so animals don't vanish in your face). */
    private static final int CAP_PROTECTION_RADIUS = 16;
    /** Cap-driven despawns per sweep — drains a flooded world smoothly instead of popping a herd at once. */
    private static final int MAX_CAP_DESPAWNS_PER_SWEEP = 8;
    /** Slack (blocks) beyond a player's view radius before a mob is distance-despawned. */
    private static final int DESPAWN_MARGIN_BLOCKS = 16;

    private static final int MIN_SPAWN_HEIGHT = 60;
    private static final int MAX_SPAWN_HEIGHT = 120;

    /** The passive types this spawner manages. */
    private static final EntityType[] PASSIVE_SPAWN_TYPES = {EntityType.COW, EntityType.CHICKEN, EntityType.SHEEP};

    /** Cow texture variants — delegates to EntityType to kill duplication. */
    private static final String[] COW_TEXTURE_VARIANTS = EntityType.COW.getTextureVariants();

    // ─── Collaborators ────────────────────────────────────────────────────────
    private final World world;
    private final EntityManager entityManager;
    private final Random random = new Random();

    /**
     * Supplier of spawn anchors (player position + that player's view distance) used to size the
     * cap and to place/despawn mobs. Injected by the world owner so this class doesn't reach into
     * the network layer. Defaults to "the single local player from {@code Game}" for tests/dev runs.
     */
    private volatile Supplier<List<SpawnAnchor>> anchorSource = EntitySpawner::defaultLocalAnchor;

    private float spawnTimer = 0f;
    private float despawnTimer = 0f;

    /**
     * A point of interest for spawning: a player's position and the view distance (in chunks) they
     * have loaded around it. The view distance defines both the eligible-chunk square and the
     * spawn/despawn radius for this anchor.
     */
    public record SpawnAnchor(Vector3f position, int viewDistanceChunks) {}

    public EntitySpawner(World world, EntityManager entityManager) {
        this.world = world;
        this.entityManager = entityManager;
    }

    /**
     * Replaces the spawn-anchor source. Server-side wiring (the integrated server) supplies one
     * that enumerates every connected player with its reported view distance.
     */
    public void setSpawnAnchorSource(Supplier<List<SpawnAnchor>> source) {
        this.anchorSource = (source != null) ? source : EntitySpawner::defaultLocalAnchor;
    }

    private static List<SpawnAnchor> defaultLocalAnchor() {
        Player local = Game.getPlayer();
        return local != null
            ? List.of(new SpawnAnchor(new Vector3f(local.getPosition()), WorldConfiguration.DEFAULT_RENDER_DISTANCE))
            : Collections.emptyList();
    }

    // ─── Tick loop ────────────────────────────────────────────────────────────

    /**
     * Per-tick update. Only the authoritative server world ever calls this (the client render
     * world's spawner is never ticked), so no host/client guard is needed here.
     */
    public void update(float deltaTime) {
        spawnTimer += deltaTime;
        if (spawnTimer >= PASSIVE_SPAWN_INTERVAL_SECONDS) {
            spawnTimer = 0f;
            performContinuousSpawning();
        }
        despawnTimer += deltaTime;
        if (despawnTimer >= DESPAWN_CHECK_INTERVAL_SECONDS) {
            despawnTimer = 0f;
            checkDespawning();
        }
    }

    // ─── Cap math ─────────────────────────────────────────────────────────────

    /** The passive-mob cap for a given count of eligible (loaded, feature-populated) chunks. */
    static int computeCap(int eligibleChunks) {
        return Math.round(DENSITY_PER_CHUNK * eligibleChunks);
    }

    /**
     * Counts the de-duplicated loaded + feature-populated chunks within view distance of any anchor.
     * Reads only resident chunks ({@link World#getChunkIfLoaded}) — never generates — so it measures
     * the currently visible area, not the server's monotonically-growing residency set.
     */
    private int countEligibleChunks(List<SpawnAnchor> anchors) {
        Set<Long> eligible = new HashSet<>();
        for (SpawnAnchor anchor : anchors) {
            int r = anchor.viewDistanceChunks();
            int pcx = Math.floorDiv((int) Math.floor(anchor.position().x), 16);
            int pcz = Math.floorDiv((int) Math.floor(anchor.position().z), 16);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = chunkKey(cx, cz);
                    if (eligible.contains(key)) {
                        continue;
                    }
                    var chunk = world.getChunkIfLoaded(cx, cz);
                    if (chunk != null && chunk.areFeaturesPopulated()) {
                        eligible.add(key);
                    }
                }
            }
        }
        return eligible.size();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ─── Continuous spawning (the only spawn path) ────────────────────────────

    private void performContinuousSpawning() {
        List<SpawnAnchor> anchors = collectAnchors();
        if (anchors.isEmpty()) {
            return;
        }
        int cap = computeCap(countEligibleChunks(anchors));
        if (cap <= 0) {
            return;
        }
        int active = passivesWithinView(anchors).size();
        int deficit = Math.min(cap - active, MAX_SPAWN_PER_CYCLE);
        if (deficit <= 0) {
            return;
        }

        // Local running tally: mobs spawned this cycle won't appear in the live list until the next
        // update() promotes them, so we count them here to avoid overshooting the cap.
        int spawned = 0;
        int maxAttempts = deficit * 4 + 8;
        for (int attempt = 0; attempt < maxAttempts && spawned < deficit; attempt++) {
            SpawnAnchor anchor = anchors.get(random.nextInt(anchors.size()));
            Vector3f spot = findSpawnNear(anchor.position(), anchor.viewDistanceChunks() * 16);
            if (spot == null) {
                continue;
            }
            spawned += spawnPack(spot, deficit - spawned);
        }
    }

    /** Spawns a small clustered pack of one random passive type around {@code spot}; returns the count placed. */
    private int spawnPack(Vector3f spot, int remaining) {
        EntityType type = PASSIVE_SPAWN_TYPES[random.nextInt(PASSIVE_SPAWN_TYPES.length)];
        int packSize = Math.min(remaining, MIN_PACK + random.nextInt(MAX_PACK - MIN_PACK + 1));
        int placed = 0;
        for (int i = 0; i < packSize; i++) {
            Vector3f pos = (i == 0) ? spot : jitterStandable(spot);
            if (pos != null && isValidSpawnLocation(pos, type) && spawnPassiveMob(type, pos) != null) {
                placed++;
            }
        }
        return placed;
    }

    /** A standable spot within {@link #PACK_SPREAD} blocks of {@code center}, or null. */
    private Vector3f jitterStandable(Vector3f center) {
        int x = (int) Math.floor(center.x) + random.nextInt(PACK_SPREAD * 2 + 1) - PACK_SPREAD;
        int z = (int) Math.floor(center.z) + random.nextInt(PACK_SPREAD * 2 + 1) - PACK_SPREAD;
        return findStandableColumn(x, z);
    }

    // ─── Despawning ───────────────────────────────────────────────────────────

    private void checkDespawning() {
        List<SpawnAnchor> anchors = collectAnchors();
        if (anchors.isEmpty()) {
            return;
        }

        // Distance despawn: cull anything beyond view radius (+margin) of EVERY anchor. These are
        // off-screen, so removing them causes no visible pop.
        List<Entity> survivors = new ArrayList<>();
        for (Entity mob : allPassives()) {
            if (!withinAnyViewRadius(mob.getPosition(), anchors, DESPAWN_MARGIN_BLOCKS)) {
                entityManager.removeEntity(mob);
            } else {
                survivors.add(mob);
            }
        }

        // Cap cull: if the in-range population still exceeds the dynamic cap, thin it from the
        // outside in — farthest from any anchor first, never within CAP_PROTECTION_RADIUS, and only
        // a few per sweep so a flooded world drains smoothly rather than a whole herd vanishing.
        int cap = computeCap(countEligibleChunks(anchors));
        int excess = survivors.size() - cap;
        if (excess <= 0) {
            return;
        }
        survivors.sort((a, b) -> Float.compare(
                nearestAnchorDistance(b.getPosition(), anchors),
                nearestAnchorDistance(a.getPosition(), anchors)));
        int culled = 0;
        for (Entity mob : survivors) {
            if (excess <= 0 || culled >= MAX_CAP_DESPAWNS_PER_SWEEP) {
                break;
            }
            if (nearestAnchorDistance(mob.getPosition(), anchors) <= CAP_PROTECTION_RADIUS) {
                continue;
            }
            entityManager.removeEntity(mob);
            excess--;
            culled++;
        }
    }

    // ─── Population queries (count queued spawns too) ─────────────────────────

    /** All live + just-queued passive mobs. */
    private List<Entity> allPassives() {
        List<Entity> out = new ArrayList<>();
        for (Entity e : entityManager.getAllEntitiesIncludingPending()) {
            if (e.isAlive() && isPassive(e.getType())) {
                out.add(e);
            }
        }
        return out;
    }

    /** Passive mobs within view radius of any anchor (the "visible" population the cap targets). */
    private List<Entity> passivesWithinView(List<SpawnAnchor> anchors) {
        List<Entity> out = new ArrayList<>();
        for (Entity mob : allPassives()) {
            if (withinAnyViewRadius(mob.getPosition(), anchors, 0)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static boolean isPassive(EntityType type) {
        return type == EntityType.COW || type == EntityType.CHICKEN || type == EntityType.SHEEP;
    }

    private static boolean withinAnyViewRadius(Vector3f point, List<SpawnAnchor> anchors, int extraBlocks) {
        for (SpawnAnchor anchor : anchors) {
            float radius = anchor.viewDistanceChunks() * 16f + extraBlocks;
            if (point.distance(anchor.position()) <= radius) {
                return true;
            }
        }
        return false;
    }

    private static float nearestAnchorDistance(Vector3f point, List<SpawnAnchor> anchors) {
        float best = Float.POSITIVE_INFINITY;
        for (SpawnAnchor anchor : anchors) {
            float d = point.distance(anchor.position());
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private List<SpawnAnchor> collectAnchors() {
        List<SpawnAnchor> result = anchorSource.get();
        return result != null ? result : Collections.emptyList();
    }

    // ─── Spawn-location finders ───────────────────────────────────────────────

    /**
     * Picks a random point on a ring around {@code center} (between {@link #MIN_SPAWN_DISTANCE} and
     * {@code maxRadiusBlocks}) and finds a valid standable column there. Returns null if the target
     * chunk isn't resident + feature-populated, or no standable column exists.
     */
    private Vector3f findSpawnNear(Vector3f center, int maxRadiusBlocks) {
        int outer = Math.max(maxRadiusBlocks, MIN_SPAWN_DISTANCE + 1);
        float angle = random.nextFloat() * (float) (Math.PI * 2);
        float distance = MIN_SPAWN_DISTANCE + random.nextFloat() * (outer - MIN_SPAWN_DISTANCE);
        int x = (int) (center.x + Math.cos(angle) * distance);
        int z = (int) (center.z + Math.sin(angle) * distance);
        return findStandableColumn(x, z);
    }

    /**
     * The lowest-priced valid standable position in the column at {@code (x,z)}, scanning down from
     * {@link #MAX_SPAWN_HEIGHT}. Gated on the chunk being resident + feature-populated so mobs never
     * land on unloaded/half-baked terrain where they'd fall through the world.
     */
    private Vector3f findStandableColumn(int x, int z) {
        if (!isChunkReadyForSpawn(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
            return null;
        }
        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            if (isStandableColumn(world.getBlockAt(x, y, z),
                                  world.getBlockAt(x, y + 1, z),
                                  world.getBlockAt(x, y + 2, z))) {
                return new Vector3f(x + 0.5f, y + 1, z + 0.5f);
            }
        }
        return null;
    }

    /**
     * A chunk is ready for a spawn iff it's resident in the store AND its features have been
     * populated. Both conditions together mean the chunk is part of the server's working set and
     * its blocks are final.
     */
    private boolean isChunkReadyForSpawn(int chunkX, int chunkZ) {
        var chunk = world.getChunkIfLoaded(chunkX, chunkZ);
        return chunk != null && chunk.areFeaturesPopulated();
    }

    private static boolean isStandableColumn(BlockType ground, BlockType head, BlockType above) {
        if (ground == null || ground == BlockType.AIR || ground == BlockType.WATER) return false;
        return (head == null || head == BlockType.AIR) && (above == null || above == BlockType.AIR);
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Validates a candidate spawn position for the given type. Public so tools and commands can
     * reuse the same gate.
     */
    public boolean isValidSpawnLocation(Vector3f position, EntityType type) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y);
        int z = (int) Math.floor(position.z);
        return switch (type) {
            case COW, CHICKEN, SHEEP -> isValidGroundSpawn(x, y, z, position);
            default -> false;
        };
    }

    private boolean isValidGroundSpawn(int x, int y, int z, Vector3f position) {
        BlockType ground = world.getBlockAt(x, y - 1, z);
        if (ground == null || ground == BlockType.AIR || ground == BlockType.WATER) return false;

        BlockType head = world.getBlockAt(x, y, z);
        BlockType above = world.getBlockAt(x, y + 1, z);
        if (head != null && head != BlockType.AIR) return false;
        if (above != null && above != BlockType.AIR) return false;

        return !isOvercrowded(position);
    }

    private boolean isOvercrowded(Vector3f position) {
        return entityManager.getEntitiesInRange(position, 5.0f).size() >= 3;
    }

    // ─── Spawning primitives ──────────────────────────────────────────────────

    private Entity spawnPassiveMob(EntityType type, Vector3f position) {
        if (type == EntityType.COW) {
            String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
            return entityManager.spawnCowWithVariant(position, variant);
        }
        return entityManager.spawnEntity(type, position);
    }

    // ─── Backwards-compatible public helpers ──────────────────────────────────

    /** Spawns a herd near {@code center} for commands/testing — bypasses the spawn cycle. */
    public void spawnCowHerd(Vector3f center, int count) {
        int spawned = 0;
        for (int attempt = 0; attempt < count * 5 && spawned < count; attempt++) {
            float ox = (random.nextFloat() - 0.5f) * 16.0f;
            float oz = (random.nextFloat() - 0.5f) * 16.0f;
            int x = (int) (center.x + ox);
            int z = (int) (center.z + oz);

            Vector3f pos = findStandableColumn(x, z);
            if (pos == null || !isValidSpawnLocation(pos, EntityType.COW)) continue;

            String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
            if (entityManager.spawnCowWithVariant(pos, variant) != null) {
                spawned++;
            }
        }
    }

    /** Test/command hook — unconditional spawn, no validation. */
    public Entity forceSpawnEntity(EntityType type, Vector3f position) {
        return entityManager.spawnEntity(type, position);
    }

    public String getSpawnStats() {
        int cows = entityManager.getEntitiesByType(EntityType.COW).size();
        int chickens = entityManager.getEntitiesByType(EntityType.CHICKEN).size();
        int sheep = entityManager.getEntitiesByType(EntityType.SHEEP).size();
        int cap = computeCap(countEligibleChunks(collectAnchors()));
        return String.format("Cows: %d | Chickens: %d | Sheep: %d | Cap: %d | Next cycle: %.1fs",
                cows, chickens, sheep, cap, PASSIVE_SPAWN_INTERVAL_SECONDS - spawnTimer);
    }
}
