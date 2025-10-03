package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central manager for all entities in the game world.
 * Handles entity lifecycle, updates, rendering, and collision detection.
 */
public class EntityManager {
    // Entity storage
    private final List<Entity> entities;
    private final List<Entity> entitiesToAdd;
    private final List<Entity> entitiesToRemove;
    
    // World and collision system
    private final World world;
    private final EntityCollision collision;
    
    // Update tracking
    private float totalTime;
    
    /**
     * Creates a new entity manager for the specified world.
     */
    public EntityManager(World world) {
        this.world = world;
        this.entities = new CopyOnWriteArrayList<>(); // Thread-safe for concurrent access
        this.entitiesToAdd = new ArrayList<>();
        this.entitiesToRemove = new ArrayList<>();
        this.collision = new EntityCollision(world);
        this.totalTime = 0.0f;
    }
    
    /**
     * Updates all entities and handles entity management.
     */
    public void update(float deltaTime) {
        totalTime += deltaTime;
        
        // Add new entities
        synchronized (entitiesToAdd) {
            if (!entitiesToAdd.isEmpty()) {
                entities.addAll(entitiesToAdd);
                entitiesToAdd.clear();
            }
        }
        
        // Update all entities
        for (Entity entity : entities) {
            if (entity.isAlive()) {
                // Update entity
                entity.update(deltaTime);
                
                // Apply physics and collision
                if (entity instanceof LivingEntity livingEntity) {
                    collision.applyLivingEntityPhysics(livingEntity, deltaTime);
                } else {
                    collision.applyEntityPhysics(entity, deltaTime);
                }
            } else {
                // Mark dead entities for removal
                synchronized (entitiesToRemove) {
                    entitiesToRemove.add(entity);
                }
            }
        }
        
        // Handle entity-entity collisions
        handleEntityCollisions();
        
        // Remove dead entities
        synchronized (entitiesToRemove) {
            if (!entitiesToRemove.isEmpty()) {
                entities.removeAll(entitiesToRemove);
                entitiesToRemove.clear();
            }
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
     * Spawns a new entity of the specified type at the given position.
     */
    public Entity spawnEntity(EntityType type, Vector3f position) {
        Entity entity = createEntity(type, position);
        if (entity != null) {
            // Check if entity is spawning inside a block and push to open space
            Vector3f safePosition = findSafeSpawnPosition(position, entity);
            entity.setPosition(safePosition);
            
            synchronized (entitiesToAdd) {
                entitiesToAdd.add(entity);
            }
        }
        return entity;
    }
    
    /**
     * Spawns a cow entity with a specific texture variant.
     */
    public Entity spawnCowWithVariant(Vector3f position, String textureVariant) {
        Entity entity = new com.stonebreak.mobs.cow.Cow(world, position, textureVariant);
        if (entity != null) {
            // Check if entity is spawning inside a block and push to open space
            Vector3f safePosition = findSafeSpawnPosition(position, entity);
            entity.setPosition(safePosition);
            
            synchronized (entitiesToAdd) {
                entitiesToAdd.add(entity);
            }
        }
        return entity;
    }
    
    /**
     * Adds an existing entity to the manager.
     * This is used for entities created outside the spawn system, like drops.
     */
    public void addEntity(Entity entity) {
        if (entity != null) {
            synchronized (entitiesToAdd) {
                entitiesToAdd.add(entity);
            }
        }
    }
    
    /**
     * Creates an entity instance based on the entity type.
     * This method will be expanded in future phases as new entity types are added.
     */
    private Entity createEntity(EntityType type, Vector3f position) {
        return switch (type) {
            case COW -> {
                // Select random texture variant for fallback cow creation
                String[] variants = {"default", "angus", "highland"};
                String textureVariant = variants[(int)(Math.random() * variants.length)];
                yield new com.stonebreak.mobs.cow.Cow(world, position, textureVariant);
            }
            case ZOMBIE -> new com.stonebreak.mobs.zombie.Zombie(world, position);
            default -> {
                System.err.println("Unknown entity type: " + type);
                yield null;
            }
        };
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
        synchronized (entitiesToRemove) {
            entitiesToRemove.add(entity);
        }
    }
    
    /**
     * Gets all entities within a specified range of a center position.
     */
    public List<Entity> getEntitiesInRange(Vector3f center, float radius) {
        List<Entity> nearbyEntities = new ArrayList<>();
        float radiusSquared = radius * radius;
        
        for (Entity entity : entities) {
            if (entity.isAlive()) {
                float distanceSquared = entity.getPosition().distanceSquared(center);
                if (distanceSquared <= radiusSquared) {
                    nearbyEntities.add(entity);
                }
            }
        }
        
        return nearbyEntities;
    }
    
    /**
     * Gets the nearest entity of a specific type to a position.
     */
    public Entity getNearestEntity(Vector3f position, EntityType type) {
        Entity nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (entity.isAlive() && entity.getType() == type) {
                float distance = entity.getPosition().distance(position);
                if (distance < nearestDistance) {
                    nearest = entity;
                    nearestDistance = distance;
                }
            }
        }
        
        return nearest;
    }
    
    /**
     * Gets all entities of a specific type.
     */
    public List<Entity> getEntitiesByType(EntityType type) {
        List<Entity> entitiesOfType = new ArrayList<>();
        
        for (Entity entity : entities) {
            if (entity.isAlive() && entity.getType() == type) {
                entitiesOfType.add(entity);
            }
        }
        
        return entitiesOfType;
    }
    
    /**
     * Gets all living entities that can interact with players.
     */
    public List<LivingEntity> getLivingEntities() {
        List<LivingEntity> livingEntities = new ArrayList<>();
        
        for (Entity entity : entities) {
            if (entity.isAlive() && entity instanceof LivingEntity) {
                livingEntities.add((LivingEntity) entity);
            }
        }
        
        return livingEntities;
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
    
    /**
     * Handles collisions between entities.
     */
    private void handleEntityCollisions() {
        List<Entity> livingEntities = new ArrayList<>(entities);
        
        for (int i = 0; i < livingEntities.size(); i++) {
            for (int j = i + 1; j < livingEntities.size(); j++) {
                Entity entity1 = livingEntities.get(i);
                Entity entity2 = livingEntities.get(j);
                
                if (collision.checkEntityCollision(entity1, entity2)) {
                    collision.resolveEntityCollision(entity1, entity2);
                }
            }
        }
    }
    
    /**
     * Cleans up dead entities and performs maintenance.
     */
    public void cleanup() {
        // Remove all dead entities (CopyOnWriteArrayList doesn't support iterator.remove())
        entities.removeIf(entity -> !entity.isAlive());
        
        // Clear pending lists
        synchronized (entitiesToAdd) {
            entitiesToAdd.clear();
        }
        synchronized (entitiesToRemove) {
            entitiesToRemove.clear();
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
        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 16;
        int chunkMinZ = chunkZ * 16; 
        int chunkMaxZ = chunkMinZ + 16;
        
        int removedCount = 0;
        
        synchronized (entitiesToRemove) {
            for (Entity entity : entities) {
                if (entity.isAlive()) {
                    Vector3f pos = entity.getPosition();
                    // Check if entity is within the chunk bounds
                    if (pos.x >= chunkMinX && pos.x < chunkMaxX && 
                        pos.z >= chunkMinZ && pos.z < chunkMaxZ) {
                        entitiesToRemove.add(entity);
                        removedCount++;
                    }
                }
            }
        }
        
        if (removedCount > 0) {
            System.out.println("Unloaded " + removedCount + " entities from chunk (" + chunkX + ", " + chunkZ + ")");
        }
    }

    /**
     * Gets debug information about the entity manager.
     */
    public String getDebugInfo() {
        return String.format("Entities: %d alive, %d total, %d pending add, %d pending remove",
                getLivingEntityCount(), getEntityCount(), 
                entitiesToAdd.size(), entitiesToRemove.size());
    }
    
    /**
     * Clears all cow path data for debug visualization.
     */
    public void clearAllCowPaths() {
        List<Entity> cowEntities = getEntitiesByType(EntityType.COW);
        for (Entity entity : cowEntities) {
            if (entity instanceof com.stonebreak.mobs.cow.Cow) {
                com.stonebreak.mobs.cow.Cow cow = (com.stonebreak.mobs.cow.Cow) entity;
                com.stonebreak.mobs.cow.CowAI cowAI = cow.getAI();
                if (cowAI != null) {
                    cowAI.clearDebugPaths();
                }
            }
        }
    }
    
    // Getters
    public World getWorld() { return world; }
    public EntityCollision getCollision() { return collision; }
    public List<Entity> getAllEntities() { return new ArrayList<>(entities); }
    public float getTotalTime() { return totalTime; }
}