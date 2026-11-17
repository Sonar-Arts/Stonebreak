package com.stonebreak.mobs.entities;

import com.openmason.engine.entity.EntityFilter;
import com.openmason.engine.entity.EntityWorld;
import com.openmason.engine.entity.HandleList;
import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.player.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Central manager for all entities in one world.
 *
 * <p>The entity model itself is the engine's native one (OpenMason #114, {@code ce_*} through
 * {@link EntityWorld}): it owns the handles, the deferred spawn/despawn lifecycle (so entities can be
 * removed while the list is walked), the live order, the {@link EntityType} descriptors, the spatial
 * index behind the range/nearest/type/chunk queries and every living entity's clip clock. This class
 * keeps what is Stonebreak's: the entity objects (keyed by handle slot), the update rules (shadows,
 * riders, physics per kind, mob collisions), spawning rules, listeners and save bookkeeping.
 *
 * <p>Every native call holds the {@link EntityWorld}'s monitor, so a query or spawn from another
 * thread is serialized with the owner's commits instead of racing them.
 */
public class EntityManager {

    /** Body flags this game pushes to the native world (the engine treats them as opaque bits). */
    static final int BODY_ALIVE = 1;
    static final int BODY_LIVING = 2;
    static final int BODY_SHADOW = 4;
    static final int BODY_PERSISTENT = 8;

    private static final EntityFilter ALIVE = EntityFilter.requiring(BODY_ALIVE);

    // The native entity world and the objects behind its handles (index = EntityWorld.slotOf).
    private final EntityWorld nativeWorld;
    private Entity[] bySlot = new Entity[256];
    private final HandleList commitBuffer = new HandleList(64);
    private final HandleList queryBuffer = new HandleList(64);

    /** The entities in the world, in the native live order; replaced (never mutated) on commits. */
    private volatile List<Entity> entities = new CopyOnWriteArrayList<>();

    // World and collision system
    private final World world;
    private final EntityCollision collision;

    // Update tracking
    private float totalTime;

    // Async entity loading
    private final ExecutorService entityDeserializationExecutor;
    private final Queue<Entity> pendingEntityAdditions;
    private static final int MAX_ENTITY_ADDITIONS_PER_FRAME = 20;

    /** Lifecycle listener — used by the network sync layer to replicate entities. */
    public interface Listener {
        default void onEntityAdded(Entity entity)   {}
        default void onEntityRemoved(Entity entity) {}
    }
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * Creates a new entity manager for the specified world.
     *
     * @throws IllegalStateException when the engine's native entity model is unavailable (the
     *         Cenda library is missing or fails its handshake); there is no Java fallback
     */
    public EntityManager(World world) {
        this.world = world;
        this.nativeWorld = EntityWorld.create(8.0f);
        for (EntityType type : EntityType.values()) {
            nativeWorld.defineType(type.ordinal(), type.descriptor());
        }
        this.collision = new EntityCollision(world);
        this.totalTime = 0.0f;

        // Initialize async entity loading
        this.pendingEntityAdditions = new ConcurrentLinkedQueue<>();
        this.entityDeserializationExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "EntityDeserializer");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * Updates all entities and handles entity management.
     */
    public void update(float deltaTime) {
        totalTime += deltaTime;

        // Process pending async entity additions (batched)
        processPendingEntityAdditions();

        // Add new entities
        for (Entity e : commitSpawns()) fireAdded(e);

        // Update all entities
        for (Entity entity : entities) {
            if (entity.isAlive()) {
                // Network shadows are driven by inbound state packets — skip local
                // AI/physics. Apply network interpolation each frame so motion is
                // smooth between snapshots, and advance client-only visual clocks so
                // animations play (the discrete animation state arrives via EntityAnimS2C).
                if (entity.isNetworkShadow()) {
                    // A boat the local player rows is stepped by the player instead
                    // (PlayerRiding); seated shadows still interpolate (a remote player's look
                    // yaw rides it) and RiderSystem below puts them back on their seats.
                    if (entity.getInterpolator() != null && !entity.isLocallyControlled()) {
                        entity.getInterpolator().apply(entity);
                    }
                    entity.updateClientVisuals(deltaTime);
                    continue;
                }

                entity.update(deltaTime);

                if (entity.getPosition().y < -10) {
                    entity.setAlive(false);
                    continue;
                }

                // Apply physics and collision
                // Self-propelled projectiles (e.g. the fire bolt) move themselves
                // in update() and must skip the external physics step.
                if (entity.isSelfPropelled()) {
                    // self-managed projectile, no external physics
                } else if (entity.isRiding()) {
                    // seated: RiderSystem carries it to its seat below
                } else if (entity instanceof BlockDrop || entity instanceof ItemDrop) {
                    // Drops fully self-manage physics (custom gravity/bounce/ground snap in
                    // update()). The external step double-integrated their velocity AND its
                    // checkGroundBeneath treats position.y as the entity's bottom while drops
                    // treat it as their center — it knocked resting drops off the ground every
                    // tick, leaving them oscillating ~3 cm forever at the server's 20 Hz
                    // (replicated to every client as visible jitter).
                } else if (entity.getType() == EntityType.BOBBER) {
                    if (!((FishingBobber) entity).isSettled()) {
                        collision.applyEntityPhysics(entity, deltaTime);
                    }
                } else if (entity instanceof LivingEntity livingEntity) {
                    collision.applyLivingEntityPhysics(livingEntity, deltaTime);
                } else {
                    collision.applyEntityPhysics(entity, deltaTime);
                }
            } else {
                // Dead entities leave at the end of this update (removal is deferred natively).
                removeEntity(entity);
            }
        }

        // Path searches the mobs asked for this tick (adopted next tick), on this thread, bounded.
        if (world != null && !world.isRenderOnly()) {
            com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfinding = world.pathfindingIfStarted();
            if (pathfinding != null && !pathfinding.isClosed()) {
                pathfinding.step();
            }
        }

        // Seats: mobs climb into boats, riders are carried to their seats.
        if (world != null && world.isRenderOnly()) {
            com.stonebreak.vehicles.RiderSystem.updateClient(entities,
                    com.stonebreak.network.MultiplayerSession::entityShadow,
                    com.stonebreak.network.MultiplayerSession.localPlayerId());
        } else {
            com.stonebreak.vehicles.RiderSystem.updateServer(entities);
        }

        // Handle entity-entity collisions
        handleEntityCollisions();

        // Remove dead entities
        for (Entity e : commitDespawns()) fireRemoved(e);
    }

    // ---- native entity world -------------------------------------------------------------------

    private static int bodyFlags(Entity e) {
        int flags = 0;
        if (e.isAlive()) flags |= BODY_ALIVE;
        if (e instanceof LivingEntity) flags |= BODY_LIVING;
        if (e.isNetworkShadow()) flags |= BODY_SHADOW;
        if (e.isPersistent()) flags |= BODY_PERSISTENT;
        return flags;
    }

    /** The entity behind a handle of this world, or null. Caller holds the native monitor. */
    private Entity entityAt(long handle) {
        int slot = EntityWorld.slotOf(handle);
        return slot >= 0 && slot < bySlot.length ? bySlot[slot] : null;
    }

    /** Brings queued entities into the world; returns them in commit order. */
    private List<Entity> commitSpawns() {
        synchronized (nativeWorld) {
            nativeWorld.commitSpawns(commitBuffer);
            if (commitBuffer.isEmpty()) {
                return List.of();
            }
            List<Entity> added = new ArrayList<>(commitBuffer.size());
            for (int i = 0; i < commitBuffer.size(); i++) {
                long handle = commitBuffer.get(i);
                Entity e = entityAt(handle);
                if (e == null) continue;
                if (e instanceof LivingEntity living) {
                    living.getClipClock().attach(nativeWorld, handle);
                }
                added.add(e);
            }
            refreshLiveList();
            return added;
        }
    }

    /** Takes despawned entities out of the world; returns them in despawn order. */
    private List<Entity> commitDespawns() {
        synchronized (nativeWorld) {
            nativeWorld.commitDespawns(commitBuffer);
            if (commitBuffer.isEmpty()) {
                return List.of();
            }
            List<Entity> removed = new ArrayList<>(commitBuffer.size());
            for (int i = 0; i < commitBuffer.size(); i++) {
                long handle = commitBuffer.get(i);
                Entity e = entityAt(handle);
                if (e == null) continue;
                // A retired handle still has its clock until the next despawn commit: copy it out.
                if (e instanceof LivingEntity living) {
                    living.getClipClock().detach();
                }
                bySlot[EntityWorld.slotOf(handle)] = null;
                e.setEntityHandle(null, 0L);
                removed.add(e);
            }
            refreshLiveList();
            return removed;
        }
    }

    /** Rebuilds {@link #entities} from the native live order. Caller holds the native monitor. */
    private void refreshLiveList() {
        nativeWorld.live(queryBuffer);
        Entity[] live = new Entity[queryBuffer.size()];
        int n = 0;
        for (int i = 0; i < queryBuffer.size(); i++) {
            Entity e = entityAt(queryBuffer.get(i));
            if (e != null) live[n++] = e;
        }
        entities = new CopyOnWriteArrayList<>(n == live.length ? live : Arrays.copyOf(live, n));
    }

    /** Pushes every live entity's body (position, yaw, size, flags). Caller holds the native monitor. */
    private void syncBodies() {
        int i = 0;
        for (Entity e : entities) {
            long handle = e.getEntityHandle();
            if (handle == 0L || e.getEntityWorld() != nativeWorld) continue;
            float leg = e instanceof LivingEntity living ? living.getLegHeight() : 0.0f;
            Vector3f p = e.position;
            nativeWorld.body(i++, handle, p.x, p.y, p.z, e.rotation.y, e.width, e.height, e.length, leg, bodyFlags(e));
        }
        nativeWorld.syncBodies(i);
    }

    /** The entities behind {@link #queryBuffer}, in its (live) order. Caller holds the native monitor. */
    private <T extends Entity> List<T> queryResults(Class<T> type) {
        List<T> out = new ArrayList<>(queryBuffer.size());
        for (int i = 0; i < queryBuffer.size(); i++) {
            Entity e = entityAt(queryBuffer.get(i));
            if (type.isInstance(e)) out.add(type.cast(e));
        }
        return out;
    }

    private void fireAdded(Entity e) {
        for (Listener l : listeners) {
            try { l.onEntityAdded(e); } catch (Exception ex) {
                System.err.println("[EntityManager] Listener.onEntityAdded threw: " + ex);
            }
        }
    }
    private void fireRemoved(Entity e) {
        // A persistable entity leaving the world (death/despawn) changes its chunk's saved contents
        // but not its blocks; mark that chunk dirty so the next save rewrites it WITHOUT the entity
        // instead of leaving a stale snapshot that would resurrect it on reload. No-op on the client
        // world (no SaveService); shadows/transient entities aren't persisted.
        if (world != null && !e.isNetworkShadow() && e.isPersistent()) {
            Vector3f p = e.getPosition();
            int cx = Math.floorDiv((int) Math.floor(p.x), 16);
            int cz = Math.floorDiv((int) Math.floor(p.z), 16);
            var chunk = world.getChunkIfLoaded(cx, cz);
            if (chunk != null) {
                chunk.markDirty();
            }
        }
        for (Listener l : listeners) {
            try { l.onEntityRemoved(e); } catch (Exception ex) {
                System.err.println("[EntityManager] Listener.onEntityRemoved threw: " + ex);
            }
        }
        try { e.onRemovedFromWorld(); } catch (Exception ex) {
            System.err.println("[EntityManager] onRemovedFromWorld threw: " + ex);
        }
    }

    /**
     * Renders all entities using the provided renderer.
     */
    public void renderAllEntities(Renderer renderer) {
        for (Entity entity : entities) {
            if (entity.isAlive()) {
                entity.render(renderer);
            }
        }
    }
    
    /**
     * Spawns a new entity of the specified type at the given position, or
     * returns null when the target chunk isn't loaded yet.
     */
    public Entity spawnEntity(EntityType type, Vector3f position) {
        if (!isChunkReadyForSpawn(position)) {
            return null;
        }
        Entity entity = createEntity(type, position);
        if (entity != null) {
            // Check if entity is spawning inside a block and push to open space
            Vector3f safePosition = findSafeSpawnPosition(position, entity);
            entity.setPosition(safePosition);

            queue(entity);
        }
        return entity;
    }

    /**
     * Spawns a cow entity with a specific texture variant, or returns null
     * when the target chunk isn't loaded yet.
     */
    public Entity spawnCowWithVariant(Vector3f position, String textureVariant) {
        if (!isChunkReadyForSpawn(position)) {
            return null;
        }
        Entity entity = new com.stonebreak.mobs.cow.Cow(world, position, textureVariant);
        if (entity != null) {
            // Check if entity is spawning inside a block and push to open space
            Vector3f safePosition = findSafeSpawnPosition(position, entity);
            entity.setPosition(safePosition);

            queue(entity);
        }
        return entity;
    }

    /**
     * Mobs may only spawn into chunks that are resident and fully generated —
     * placing one on missing or half-baked terrain drops it through the world.
     * This gates every spawn entry point (spawner cycle, commands, herd/test
     * helpers); entities restored with their chunk or replicated as network
     * shadows go through {@link #addEntity} and are exempt.
     */
    private boolean isChunkReadyForSpawn(Vector3f position) {
        var chunk = world.getChunkIfLoaded(
                Math.floorDiv((int) Math.floor(position.x), 16),
                Math.floorDiv((int) Math.floor(position.z), 16));
        return chunk != null && chunk.areFeaturesPopulated();
    }

    /**
     * Adds an existing entity to the manager.
     * This is used for entities created outside the spawn system, like drops.
     */
    public void addEntity(Entity entity) {
        queue(entity);
    }

    /**
     * Queues an entity into the native world (it joins at the next update). The spawn helpers use
     * this directly, as they always queued without going through {@link #addEntity}.
     */
    private void queue(Entity entity) {
        if (entity == null) {
            return;
        }
        synchronized (nativeWorld) {
            // Already queued or in this world: adding twice must not list it twice.
            if (entity.getEntityWorld() == nativeWorld
                    && nativeWorld.state(entity.getEntityHandle()) != EntityWorld.STATE_NONE) {
                return;
            }
            Vector3f p = entity.position;
            long handle = nativeWorld.spawn(entity.getType().ordinal(), bodyFlags(entity),
                    p.x, p.y, p.z, entity.rotation.y);
            if (handle == 0L) {
                throw new IllegalStateException("native entity world refused " + entity.getType());
            }
            int slot = EntityWorld.slotOf(handle);
            if (slot >= bySlot.length) {
                bySlot = Arrays.copyOf(bySlot, Math.max(bySlot.length * 2, slot + 1));
            }
            bySlot[slot] = entity;
            entity.setEntityHandle(nativeWorld, handle);
        }
    }
    
    /**
     * Creates an entity instance based on the entity type.
     * This method will be expanded in future phases as new entity types are added.
     */
    private Entity createEntity(EntityType type, Vector3f position) {
        return switch (type) {
            case COW -> {
                String[] variants = type.getTextureVariants();
                String textureVariant = variants[(int)(Math.random() * variants.length)];
                yield new com.stonebreak.mobs.cow.Cow(world, position, textureVariant);
            }
            case CHICKEN -> new com.stonebreak.mobs.chicken.Chicken(world, position);
            case GOOSE -> new com.stonebreak.mobs.goose.Goose(world, position);
            case SHEEP -> new com.stonebreak.mobs.sheep.Sheep(world, position);
            case GOBLIN -> new com.stonebreak.mobs.goblin.Goblin(world, position);
            // Sheep always spawn with wool (the "default" fallback): unlike cows, a
            // random variant pick here would spawn ~half of them already sheared
            // now that "Sheared" is a registered appearance variant.
            // Projectiles (FIRE_BOLT, ARROW, BOBBER) require a launch direction and
            // are spawned via their dedicated spawn* methods, not this generic path.
            default -> {
                System.err.println("Unknown entity type: " + type);
                yield null;
            }
        };
    }

    /**
     * Spawns a fire bolt projectile aimed in the given direction.
     */
    public FireBolt spawnFireBolt(Vector3f position, Vector3f direction) {
        FireBolt bolt = new FireBolt(world, position, direction);
        queue(bolt);
        return bolt;
    }

    /**
     * Spawns an arrow projectile with the given initial velocity.
     */
    public Arrow spawnArrow(Vector3f position, Vector3f velocity) {
        Arrow arrow = new Arrow(world, position, velocity);
        queue(arrow);
        return arrow;
    }

    /**
     * Spawns a Null Spike projectile aimed in the given direction. Damage, Spellmark
     * duration, pierce, and burst damage are precomputed by the casting ability.
     */
    public NullSpikeProjectile spawnNullSpike(Vector3f position, Vector3f direction,
            float damagePerHit, float spellmarkDuration, boolean pierce, float burstDamage) {
        NullSpikeProjectile spike = new NullSpikeProjectile(world, position, direction,
            damagePerHit, spellmarkDuration, pierce, burstDamage);
        queue(spike);
        return spike;
    }

    /**
     * Spawns a Leyline Breach zone at a ground position. Radius, pull force, pulse
     * damage, and duration are precomputed by the casting ability.
     */
    public LeylineBreachZone spawnLeylineBreachZone(Vector3f position, float radius,
            float pullForce, float pulseDamage, float duration, boolean overloaded) {
        LeylineBreachZone zone = new LeylineBreachZone(world, position, radius,
            pullForce, pulseDamage, duration, overloaded);
        queue(zone);
        return zone;
    }

    /**
     * Spawns a Rogue caltrop cluster at a ground position that flat-foots the first creature to step
     * on it, expiring after {@code duration} seconds if untriggered.
     */
    public CaltropCluster spawnCaltropCluster(Vector3f position, float duration) {
        CaltropCluster cluster = new CaltropCluster(world, position, duration);
        queue(cluster);
        return cluster;
    }

    /**
     * Places a dinghy with its bow toward {@code yawDeg}, or returns null when the target
     * chunk isn't loaded yet.
     */
    public com.stonebreak.vehicles.Dinghy spawnDinghy(Vector3f position, float yawDeg) {
        if (!isChunkReadyForSpawn(position)) {
            return null;
        }
        com.stonebreak.vehicles.Dinghy dinghy = new com.stonebreak.vehicles.Dinghy(world, position, yawDeg);
        queue(dinghy);
        return dinghy;
    }

    /**
     * Spawns a fishing bobber launched in the given direction.
     */
    public FishingBobber spawnBobber(Vector3f position, Vector3f direction) {
        FishingBobber bobber = new FishingBobber(world, position, direction);
        queue(bobber);
        return bobber;
    }
    
    /**
     * Finds a safe spawn position for an entity, moving it out of blocks if necessary.
     */
    private Vector3f findSafeSpawnPosition(Vector3f originalPosition, Entity entity) {
        Vector3f safePosition = new Vector3f(originalPosition);
        
        // Check if the entity is inside a block
        if (!isPositionSafe(safePosition, entity)) {
            // Try to find a safe position by searching in expanding radius
            Vector3f foundPosition = searchForSafePosition(safePosition, entity);
            if (foundPosition != null) {
                return foundPosition;
            }
            
            // If no safe position found, try pushing up
            safePosition = pushEntityUp(safePosition, entity);
        }
        
        return safePosition;
    }
    
    /**
     * Checks if a position is safe for an entity (not inside blocks).
     */
    private boolean isPositionSafe(Vector3f position, Entity entity) {
        float entityHeight = entity.getHeight();
        float entityWidth = entity.getWidth();
        float entityLength = entity.getLength();
        
        // Add small buffer to prevent edge collision issues
        float buffer = 0.1f;
        float halfWidth = (entityWidth + buffer) / 2;
        float halfLength = (entityLength + buffer) / 2;
        
        // Check the entity's bounding box with buffer
        int minX = (int) Math.floor(position.x - halfWidth);
        int maxX = (int) Math.ceil(position.x + halfWidth);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.ceil(position.y + entityHeight);
        int minZ = (int) Math.floor(position.z - halfLength);
        int maxZ = (int) Math.ceil(position.z + halfLength);
        
        // Check all blocks the entity would occupy
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var block = world.getBlockAt(x, y, z);
                    if (block != null && block != com.stonebreak.blocks.BlockType.AIR) {
                        return false;
                    }
                }
            }
        }
        
        // For living entities, also check leg space
        if (entity instanceof com.stonebreak.mobs.entities.LivingEntity livingEntity) {
            float legHeight = livingEntity.getLegHeight();
            int legMinY = (int) Math.floor(position.y - legHeight);
            int legMaxY = (int) Math.floor(position.y);
            
            for (int x = minX; x <= maxX; x++) {
                for (int y = legMinY; y <= legMaxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        var block = world.getBlockAt(x, y, z);
                        if (block != null && block != com.stonebreak.blocks.BlockType.AIR) {
                            return false;
                        }
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Searches for a safe position around the original position.
     */
    private Vector3f searchForSafePosition(Vector3f originalPosition, Entity entity) {
        int maxRadius = 5; // Search within 5 blocks
        
        for (int radius = 1; radius <= maxRadius; radius++) {
            // Check positions in a cube around the original position
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Only check positions on the current radius boundary
                        if (Math.abs(dx) == radius || Math.abs(dy) == radius || Math.abs(dz) == radius) {
                            Vector3f testPosition = new Vector3f(
                                originalPosition.x + dx,
                                originalPosition.y + dy,
                                originalPosition.z + dz
                            );
                            
                            if (isPositionSafe(testPosition, entity)) {
                                return testPosition;
                            }
                        }
                    }
                }
            }
        }
        
        return null; // No safe position found
    }
    
    /**
     * Pushes an entity up until it finds a safe position.
     */
    private Vector3f pushEntityUp(Vector3f position, Entity entity) {
        Vector3f newPosition = new Vector3f(position);
        int maxPushUp = 10; // Don't push more than 10 blocks up
        
        for (int i = 0; i < maxPushUp; i++) {
            newPosition.y += 1.0f;
            if (isPositionSafe(newPosition, entity)) {
                return newPosition;
            }
        }
        
        // If we can't find a safe position by pushing up, return original position
        return position;
    }
    
    /**
     * Removes an entity from the world.
     */
    public void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        synchronized (nativeWorld) {
            if (entity.getEntityWorld() != nativeWorld) {
                return;
            }
            long handle = entity.getEntityHandle();
            // A live entity leaves at the end of the update; a queued one is cancelled outright.
            if (nativeWorld.despawn(handle) && nativeWorld.state(handle) == EntityWorld.STATE_NONE) {
                bySlot[EntityWorld.slotOf(handle)] = null;
                entity.setEntityHandle(null, 0L);
            }
        }
    }
    
    /**
     * Gets all living (alive) entities whose position lies within {@code radius} of the center, in
     * live order. Answered by the native spatial index from the bodies as they are right now.
     */
    public List<Entity> getEntitiesInRange(Vector3f center, float radius) {
        synchronized (nativeWorld) {
            syncBodies();
            nativeWorld.queryRadius(center.x, center.y, center.z, radius, ALIVE, queryBuffer);
            return queryResults(Entity.class);
        }
    }

    /**
     * Gets the nearest alive entity of a specific type to a position (ties: the one that entered
     * the world first), or null.
     */
    public Entity getNearestEntity(Vector3f position, EntityType type) {
        synchronized (nativeWorld) {
            syncBodies();
            long handle = nativeWorld.queryNearest(position.x, position.y, position.z,
                    Float.POSITIVE_INFINITY, ALIVE.withType(type.ordinal()));
            return handle == 0L ? null : entityAt(handle);
        }
    }

    /**
     * Gets all alive entities of a specific type.
     */
    public List<Entity> getEntitiesByType(EntityType type) {
        synchronized (nativeWorld) {
            syncBodies();
            nativeWorld.queryAll(ALIVE.withType(type.ordinal()), queryBuffer);
            return queryResults(Entity.class);
        }
    }

    /**
     * Gets all alive living entities.
     */
    public List<LivingEntity> getLivingEntities() {
        synchronized (nativeWorld) {
            syncBodies();
            nativeWorld.queryAll(EntityFilter.requiring(BODY_ALIVE | BODY_LIVING), queryBuffer);
            return queryResults(LivingEntity.class);
        }
    }

    /**
     * Handles interactions between a player and nearby entities.
     */
    public void handlePlayerInteractions(Player player) {
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity livingEntity) {
                if (livingEntity.canInteractWith(player)) {
                    // Player is close enough to interact
                    // Actual interaction will be triggered by player input
                    // This method just checks for interaction possibilities
                }
            }
        }
    }
    
    /**
     * Gets the nearest interactable entity to the player.
     */
    public LivingEntity getNearestInteractableEntity(Player player) {
        Vector3f playerPos = player.getPosition();
        LivingEntity nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity livingEntity) {
                if (livingEntity.canInteractWith(player)) {
                    float distance = entity.getPosition().distance(playerPos);
                    if (distance < nearestDistance) {
                        nearest = livingEntity;
                        nearestDistance = distance;
                    }
                }
            }
        }
        
        return nearest;
    }
    
    /** Reused per update: the bodies {@link #handleEntityCollisions} separates. */
    private final List<LivingEntity> collisionBodies = new ArrayList<>();

    /**
     * Pushes overlapping simulated mobs apart. Only {@link LivingEntity} bodies take part:
     * drops, projectiles, caltrops and ability zones are not bodies (a pulled mob used to drag
     * a Leyline Breach zone off its cast point, and mobs shoved drops around). Network shadows
     * are skipped too — the interpolator owns their position, so a render-only world does no
     * pair work at all.
     */
    private void handleEntityCollisions() {
        List<LivingEntity> bodies = collisionBodies;
        bodies.clear();
        for (Entity e : entities) {
            if (e instanceof LivingEntity living && e.isAlive() && !e.isNetworkShadow() && !e.isRiding()) {
                bodies.add(living);
            }
        }
        int n = bodies.size();
        for (int i = 0; i < n; i++) {
            LivingEntity a = bodies.get(i);
            Vector3f pa = a.getPosition();
            for (int j = i + 1; j < n; j++) {
                LivingEntity b = bodies.get(j);
                Vector3f pb = b.getPosition();
                // Cheap reject before building bounding boxes: horizontally farther apart
                // than their combined half-extents (box is width on x, length on z) can't overlap.
                float reach = (Math.max(a.getWidth(), a.getLength()) + Math.max(b.getWidth(), b.getLength())) * 0.5f;
                if (Math.abs(pa.x - pb.x) > reach || Math.abs(pa.z - pb.z) > reach) continue;
                if (collision.checkEntityCollision(a, b)) {
                    collision.resolveEntityCollision(a, b);
                }
            }
        }
        bodies.clear();
    }

    /**
     * Cleans up dead entities and performs maintenance.
     */
    public void cleanup() {
        // Shutdown entity deserialization executor
        entityDeserializationExecutor.shutdown();
        try {
            if (!entityDeserializationExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                entityDeserializationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            entityDeserializationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Clear pending additions
        pendingEntityAdditions.clear();

        // Drop queued spawns, take dead entities (and any queued removals) out of the world.
        // Listeners are not told: the world is going away.
        synchronized (nativeWorld) {
            for (long handle : nativeWorld.pending()) {
                Entity e = entityAt(handle);
                if (e != null) {
                    removeEntity(e);
                } else {
                    nativeWorld.despawn(handle);
                }
            }
            for (Entity entity : entities) {
                if (!entity.isAlive()) {
                    removeEntity(entity);
                }
            }
            commitDespawns();
        }
    }
    
    /**
     * Gets the total number of entities currently managed.
     */
    public int getEntityCount() {
        return entities.size();
    }
    
    /**
     * Gets the total number of living entities.
     */
    public int getLivingEntityCount() {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.isAlive()) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Removes all entities within a specific chunk when the chunk is unloaded.
     */
    public void removeEntitiesInChunk(int chunkX, int chunkZ) {
        synchronized (nativeWorld) {
            // Network-driven entities (remote players + replicated shadows) are owned by the
            // host, not the local chunk. Removing them on chunk unload silently desyncs them —
            // the host never re-broadcasts the spawn, so they vanish forever for the local viewer.
            // Logging removed for performance - entity unloading happens frequently.
            for (Entity entity : inChunk(chunkX, chunkZ, new EntityFilter(-1, BODY_ALIVE, BODY_SHADOW, 0L))) {
                if (entity.getType() != EntityType.REMOTE_PLAYER) {
                    removeEntity(entity);
                }
            }
        }
    }

    /**
     * Entities whose position lies in chunk column ({@code chunkX}, {@code chunkZ}) — half-open on
     * x/z, any height — in live order, from the native index. Caller holds the native monitor.
     */
    private List<Entity> inChunk(int chunkX, int chunkZ, EntityFilter filter) {
        float minX = chunkX * 16;
        float minZ = chunkZ * 16;
        syncBodies();
        nativeWorld.queryBox(minX, Float.NEGATIVE_INFINITY, minZ, minX + 16, Float.POSITIVE_INFINITY, minZ + 16,
                filter, queryBuffer);
        return queryResults(Entity.class);
    }

    /**
     * Gets debug information about the entity manager.
     */
    public String getDebugInfo() {
        int pending;
        int removing;
        synchronized (nativeWorld) {
            pending = nativeWorld.pendingCount();
            removing = nativeWorld.removingCount();
        }
        return String.format("Entities: %d alive, %d total, %d pending add, %d pending remove",
                getLivingEntityCount(), getEntityCount(), pending, removing);
    }
    
    /**
     * Gets all entities in a specific chunk as serialized EntityData.
     * Used for saving chunk entity state.
     */
    public List<com.stonebreak.world.save.model.EntityData> getEntitiesInChunk(int chunkX, int chunkZ) {
        List<Entity> inChunk;
        synchronized (nativeWorld) {
            inChunk = inChunk(chunkX, chunkZ, ALIVE);
        }
        List<com.stonebreak.world.save.model.EntityData> entityDataList = new ArrayList<>();
        for (Entity entity : inChunk) {
            com.stonebreak.world.save.model.EntityData entityData =
                com.stonebreak.world.save.serialization.EntitySerializer.serialize(entity);
            if (entityData != null) {
                entityDataList.add(entityData);
            }
        }
        return entityDataList;
    }

    /**
     * Loads entities from EntityData list asynchronously and queues them for addition.
     * Used when loading chunk entity state from save data.
     * Entities are deserialized on background thread and added in batches during update().
     */
    public void loadEntitiesForChunk(List<com.stonebreak.world.save.model.EntityData> entityDataList, int chunkX, int chunkZ) {
        if (entityDataList == null || entityDataList.isEmpty()) {
            return;
        }

        // Submit deserialization to background thread
        entityDeserializationExecutor.submit(() -> {
            try {
                for (com.stonebreak.world.save.model.EntityData entityData : entityDataList) {
                    Entity entity = com.stonebreak.world.save.serialization.EntitySerializer.deserialize(entityData, world);
                    if (entity != null) {
                        // Queue for batched addition on main thread
                        pendingEntityAdditions.offer(entity);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error deserializing entities for chunk (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            }
        });
    }

    /**
     * Processes pending entity additions in batches to prevent frame time spikes.
     * Called during update() on the main thread.
     */
    private void processPendingEntityAdditions() {
        int addedThisFrame = 0;

        Entity entity;
        while (addedThisFrame < MAX_ENTITY_ADDITIONS_PER_FRAME && (entity = pendingEntityAdditions.poll()) != null) {
            queue(entity);
            addedThisFrame++;
        }

        // If there are more pending, they'll be added next frame
    }

    // Getters
    public World getWorld() { return world; }
    public EntityCollision getCollision() { return collision; }
    public List<Entity> getAllEntities() { return new ArrayList<>(entities); }
    /** Snapshot of live entities + queued additions. Needed when callers
     *  inspect what was just queued via {@link #addEntity} before the next
     *  update tick promotes it into {@code entities}. */
    public List<Entity> getAllEntitiesIncludingPending() {
        List<Entity> out = new ArrayList<>(entities);
        synchronized (nativeWorld) {
            for (long handle : nativeWorld.pending()) {
                Entity e = entityAt(handle);
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    /** Chunks that held a persistable entity at the previous save, so emptied chunks are re-saved. */
    private final Set<Long> chunksWithEntitiesLastSave = new HashSet<>();

    /**
     * Marks chunks data-dirty so the next save (autosave or exit flush) captures current entity
     * state. Entities live in this manager, not in block data, so a chunk whose only change is the
     * mobs/drops in it would otherwise stay clean and be skipped by the dirty-gated save.
     *
     * <p>Re-dirties chunks that <i>currently</i> hold a persistable entity (so additions and moved-in
     * mobs are written with up-to-date positions) AND chunks that held one at the last save but no
     * longer do (so a mob that <i>wandered out</i> is removed from that chunk's on-disk snapshot,
     * preventing a stale snapshot from duplicating it on reload). Death/despawn removals are handled
     * separately by {@link #fireRemoved} marking the chunk dirty. Bounded by the mob cap; intended
     * for the authoritative server world.
     */
    public void markChunksWithLiveEntitiesDirty() {
        if (world == null) {
            return;
        }
        Set<Long> current = new HashSet<>();
        for (Entity e : entities) {
            if (!e.isAlive() || e.isNetworkShadow() || !e.isPersistent()) {
                continue;
            }
            Vector3f p = e.getPosition();
            int cx = Math.floorDiv((int) Math.floor(p.x), 16);
            int cz = Math.floorDiv((int) Math.floor(p.z), 16);
            current.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
        }

        // Re-dirty current occupants + chunks that were occupied last save (or seeded at load) but
        // are now empty, then remember the new occupied set. Guarded so a concurrent load-seed and
        // the autosave/flush don't corrupt the set.
        Set<Long> toDirty = new HashSet<>(current);
        synchronized (chunksWithEntitiesLastSave) {
            toDirty.addAll(chunksWithEntitiesLastSave);
            chunksWithEntitiesLastSave.clear();
            chunksWithEntitiesLastSave.addAll(current);
        }
        for (long key : toDirty) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xFFFFFFFFL);
            var chunk = world.getChunkIfLoaded(cx, cz);
            if (chunk != null) {
                chunk.markDirty();
            }
        }
    }
    /**
     * True when unloading chunk ({@code cx}, {@code cz}) must save it for its entities even if
     * its blocks are clean: a persistent entity stands in it now (the unload removes it), or did
     * at the last save (the file would bring it back while it now lives elsewhere).
     */
    public boolean hasEntityStateToSave(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        synchronized (chunksWithEntitiesLastSave) {
            if (chunksWithEntitiesLastSave.contains(key)) {
                return true;
            }
        }
        for (Entity e : entities) {
            if (!e.isAlive() || e.isNetworkShadow() || !e.isPersistent()) {
                continue;
            }
            Vector3f p = e.getPosition();
            if (Math.floorDiv((int) Math.floor(p.x), 16) == cx
                    && Math.floorDiv((int) Math.floor(p.z), 16) == cz) {
                return true;
            }
        }
        return false;
    }

    public float getTotalTime() { return totalTime; }
}