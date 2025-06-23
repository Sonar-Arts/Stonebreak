package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.World;
import com.stonebreak.Renderer;
import com.stonebreak.Player;

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
                if (entity instanceof LivingEntity) {
                    collision.applyLivingEntityPhysics((LivingEntity) entity, deltaTime);
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
            synchronized (entitiesToAdd) {
                entitiesToAdd.add(entity);
            }
        }
        return entity;
    }
    
    /**
     * Creates an entity instance based on the entity type.
     * This method will be expanded in future phases as new entity types are added.
     */
    private Entity createEntity(EntityType type, Vector3f position) {
        switch (type) {
            case COW:
                return new com.stonebreak.mobs.cow.Cow(world, position);
                
            default:
                System.err.println("Unknown entity type: " + type);
                return null;
        }
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
            if (entity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) entity;
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
            if (entity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) entity;
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
        // Remove all dead entities
        Iterator<Entity> iterator = entities.iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (!entity.isAlive()) {
                iterator.remove();
            }
        }
        
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
    
    // Getters
    public World getWorld() { return world; }
    public EntityCollision getCollision() { return collision; }
    public List<Entity> getAllEntities() { return new ArrayList<>(entities); }
    public float getTotalTime() { return totalTime; }
}