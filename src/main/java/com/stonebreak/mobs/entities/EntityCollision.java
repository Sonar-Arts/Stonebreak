package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.blocks.BlockType;

/**
 * Handles collision detection and resolution for entities in the world.
 * Provides methods for world collision, entity-entity collision, and physics application.
 */
public class EntityCollision {
    private World world;
    
    /**
     * Creates a new collision handler for the specified world.
     */
    public EntityCollision(World world) {
        this.world = world;
    }
    
    /**
     * Checks if an entity would collide with the world at a new position.
     * For living entities, collision starts from the bottom of their legs.
     */
    public boolean checkWorldCollision(Entity entity, Vector3f newPosition) {
        
        // Determine the bottom Y position (legs for living entities, entity base for others)
        float bottomY = newPosition.y;
        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity) entity;
            bottomY = newPosition.y - livingEntity.getLegHeight();
        }
        
        // Create bounding box for the new position starting from leg bottom
        Entity.BoundingBox newBounds = new Entity.BoundingBox(
            newPosition.x - entity.getWidth() / 2.0f,
            bottomY, // Start from bottom of legs for living entities
            newPosition.z - entity.getLength() / 2.0f,
            newPosition.x + entity.getWidth() / 2.0f,
            newPosition.y + entity.getHeight(),
            newPosition.z + entity.getLength() / 2.0f
        );
        
        // Check for solid blocks in the new position
        int minX = (int) Math.floor(newBounds.minX);
        int maxX = (int) Math.ceil(newBounds.maxX);
        int minY = (int) Math.floor(newBounds.minY);
        int maxY = (int) Math.ceil(newBounds.maxY);
        int minZ = (int) Math.floor(newBounds.minZ);
        int maxZ = (int) Math.ceil(newBounds.maxZ);
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockType blockType = world.getBlockAt(x, y, z);
                    if (blockType != null && blockType != BlockType.AIR && blockType != BlockType.WATER) {
                        return true; // Collision detected
                    }
                }
            }
        }
        
        return false; // No collision
    }
    
    /**
     * Resolves world collision for an entity by adjusting its position and velocity.
     */
    public void resolveWorldCollision(Entity entity) {
        Vector3f position = entity.getPosition();
        Vector3f velocity = entity.getVelocity();
        
        // Check collision in each axis separately for better resolution
        
        // X-axis collision
        Vector3f testPos = new Vector3f(position.x + velocity.x * 0.016f, position.y, position.z);
        if (checkWorldCollision(entity, testPos)) {
            velocity.x = 0;
        }
        
        // Y-axis collision (vertical)
        testPos.set(position.x, position.y + velocity.y * 0.016f, position.z);
        if (checkWorldCollision(entity, testPos)) {
            if (velocity.y < 0) {
                // Landing on ground
                entity.setOnGround(true);
                velocity.y = 0;
            } else {
                // Hitting ceiling
                velocity.y = 0;
            }
        } else {
            entity.setOnGround(false);
        }
        
        // Z-axis collision
        testPos.set(position.x, position.y, position.z + velocity.z * 0.016f);
        if (checkWorldCollision(entity, testPos)) {
            velocity.z = 0;
        }
        
        // Update entity velocity
        entity.setVelocity(velocity);
    }
    
    /**
     * Checks if two entities are colliding.
     */
    public boolean checkEntityCollision(Entity entity1, Entity entity2) {
        if (entity1 == entity2 || !entity1.isAlive() || !entity2.isAlive()) {
            return false;
        }
        
        Entity.BoundingBox bounds1 = entity1.getBoundingBox();
        Entity.BoundingBox bounds2 = entity2.getBoundingBox();
        
        return bounds1.intersects(bounds2);
    }
    
    /**
     * Resolves collision between two entities by pushing them apart.
     */
    public void resolveEntityCollision(Entity entity1, Entity entity2) {
        Vector3f pos1 = entity1.getPosition();
        Vector3f pos2 = entity2.getPosition();
        
        // Calculate separation vector
        Vector3f separation = new Vector3f(pos1).sub(pos2);
        separation.y = 0; // Only separate horizontally
        
        float distance = separation.length();
        if (distance < 0.1f) {
            // Entities are too close, create arbitrary separation
            separation.set((float)(Math.random() - 0.5), 0, (float)(Math.random() - 0.5));
            distance = separation.length();
        }
        
        // Normalize and scale separation
        separation.normalize();
        float minSeparation = (entity1.getWidth() + entity2.getWidth()) / 2.0f + 0.1f;
        separation.mul(minSeparation - distance);
        
        // Apply separation (each entity moves half the distance)
        Vector3f pos1New = new Vector3f(pos1).add(new Vector3f(separation).mul(0.5f));
        Vector3f pos2New = new Vector3f(pos2).sub(new Vector3f(separation).mul(0.5f));
        
        // Only update positions if the new positions are valid
        if (!checkWorldCollision(entity1, pos1New)) {
            entity1.setPosition(pos1New);
        }
        if (!checkWorldCollision(entity2, pos2New)) {
            entity2.setPosition(pos2New);
        }
    }
    
    /**
     * Gets the ground height at a specific position.
     * For living entities, this returns the height where their legs should touch.
     */
    public float getGroundHeight(float x, float z, Entity entity) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        
        // Search downward from a reasonable height to find ground
        for (int y = 255; y >= 0; y--) {
            BlockType blockType = world.getBlockAt(blockX, y, blockZ);
            if (blockType != null && blockType != BlockType.AIR && blockType != BlockType.WATER) {
                float groundSurface = y + 1.0f; // Top surface of the block
                
                // For living entities, position body so feet touch ground exactly
                if (entity instanceof LivingEntity) {
                    LivingEntity livingEntity = (LivingEntity) entity;
                    // Body position = ground surface + leg height (based on actual model leg length)
                    return groundSurface + livingEntity.getLegHeight();
                }
                
                return groundSurface; // Regular entities sit directly on ground
            }
        }
        
        // Bedrock level - also adjust for living entities
        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity) entity;
            return livingEntity.getLegHeight();
        }
        
        return 0.0f; // Bedrock level for regular entities
    }
    
    /**
     * Gets the ground height at a specific position (legacy method for non-entity calls).
     */
    public float getGroundHeight(float x, float z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        
        // Search downward from a reasonable height to find ground
        for (int y = 255; y >= 0; y--) {
            BlockType blockType = world.getBlockAt(blockX, y, blockZ);
            if (blockType != null && blockType != BlockType.AIR && blockType != BlockType.WATER) {
                return y + 1.0f; // Top surface of the block
            }
        }
        
        return 0.0f; // Bedrock level
    }
    
    /**
     * Checks if an entity is in water.
     */
    public boolean isInWater(Entity entity) {
        Vector3f position = entity.getPosition();
        
        // For living entities, check water at their leg level
        float checkY = position.y + entity.getHeight() / 2.0f;
        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity) entity;
            // Check water at the middle of the entity's legs
            checkY = position.y - livingEntity.getLegHeight() / 2.0f;
        }
        
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(checkY);
        int blockZ = (int) Math.floor(position.z);
        
        BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);
        return blockType == BlockType.WATER;
    }
    
    /**
     * Applies physics to an entity, including ground detection and water handling.
     */
    public void applyEntityPhysics(Entity entity, float deltaTime) {
        Vector3f position = entity.getPosition();
        Vector3f velocity = entity.getVelocity();
        
        // Check if entity is in water
        boolean inWater = isInWater(entity);
        entity.setInWater(inWater);
        
        // Apply gravity
        if (!entity.isOnGround() && !inWater) {
            velocity.y += Entity.GRAVITY * deltaTime;
        } else if (inWater) {
            // Buoyancy in water
            velocity.y += Entity.GRAVITY * 0.3f * deltaTime; // Reduced gravity in water
            velocity.mul(0.8f); // Water resistance
        }
        
        // Update velocity
        entity.setVelocity(velocity);
        
        // Calculate new position
        Vector3f newPosition = new Vector3f(position).add(new Vector3f(velocity).mul(deltaTime));
        
        // Check for world collision and resolve
        if (checkWorldCollision(entity, newPosition)) {
            resolveWorldCollision(entity);
        } else {
            entity.setPosition(newPosition);
        }
        
        // Update position reference after potential movement
        Vector3f currentPosition = entity.getPosition();
        
        // Ground detection - use entity-aware ground height
        float groundHeight = getGroundHeight(currentPosition.x, currentPosition.z, entity);
        if (currentPosition.y <= groundHeight && velocity.y <= 0) {
            Vector3f groundPos = new Vector3f(currentPosition.x, groundHeight, currentPosition.z);
            entity.setPosition(groundPos);
            entity.setOnGround(true);
            velocity.y = 0;
            entity.setVelocity(velocity);
        } else {
            entity.setOnGround(false);
        }
    }
    
    /**
     * Specialized physics application for living entities.
     */
    public void applyLivingEntityPhysics(LivingEntity entity, float deltaTime) {
        // Apply basic entity physics
        applyEntityPhysics(entity, deltaTime);
        
        // Additional living entity physics can be added here
        // such as breathing air bubbles in water, stamina-based movement, etc.
    }
}