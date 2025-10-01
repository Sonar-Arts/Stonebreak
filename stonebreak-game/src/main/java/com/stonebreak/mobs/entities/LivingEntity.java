package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;

/**
 * Base class for all living entities that can move, interact, and have AI behavior.
 * Extends Entity with health management, movement, and interaction capabilities.
 */
public abstract class LivingEntity extends Entity {
    // Health and damage system
    protected boolean invulnerable;
    protected float invulnerabilityTimer;
    private static final float INVULNERABILITY_DURATION = 0.5f; // 500ms after taking damage
    
    // Movement and behavior
    protected float moveSpeed;
    protected float turnSpeed;
    protected Vector3f targetDirection;
    protected boolean isMoving;
    
    // Physical properties
    protected float legHeight; // Distance from ground to bottom of body
    
    // Interaction system
    protected float interactionRange;
    protected long lastInteractionTime;
    private static final float INTERACTION_COOLDOWN = 1.0f; // 1 second between interactions
    
    // AI and behavior (to be implemented in future phases)
    protected Object ai; // Placeholder for AI system
    
    /**
     * Creates a new living entity at the specified position.
     */
    public LivingEntity(World world, Vector3f position, EntityType type) {
        super(world, position);
        
        // Set properties based on entity type
        this.maxHealth = type.getMaxHealth();
        this.health = maxHealth;
        this.moveSpeed = type.getMoveSpeed();
        this.turnSpeed = 90.0f; // Default turn speed in degrees per second
        this.width = type.getWidth();
        this.height = type.getHeight();
        this.length = type.getLength();
        this.legHeight = type.getLegHeight();
        
        // Initialize state
        this.invulnerable = false;
        this.invulnerabilityTimer = 0.0f;
        this.targetDirection = new Vector3f(0, 0, 0);
        this.isMoving = false;
        this.interactionRange = 3.0f;
        this.lastInteractionTime = 0;
    }
    
    /**
     * Updates the living entity's state, including invulnerability and movement.
     */
    @Override
    public void update(float deltaTime) {
        // Update invulnerability timer
        if (invulnerable) {
            invulnerabilityTimer -= deltaTime;
            if (invulnerabilityTimer <= 0) {
                invulnerable = false;
                invulnerabilityTimer = 0;
            }
        }

        // Check if entity is in water and apply flow forces
        if (isInWater()) {
            WaterFlowPhysics.applyWaterFlowForce(world, position, velocity,
                deltaTime, width, height);
        }

        // Update movement state
        isMoving = velocity.length() > 0.1f;

        // Apply basic physics
        applyPhysics(deltaTime);

        // Update AI behavior (to be implemented in future phases)
        updateAI(deltaTime);
    }
    
    /**
     * Placeholder for AI updates. Will be implemented in Phase 3.
     */
    protected void updateAI(float deltaTime) {
        // Override in subclasses
    }
    
    /**
     * Handles damage to the living entity with invulnerability frames.
     */
    @Override
    public void damage(float amount) {
        if (!alive || invulnerable) return;
        
        // Apply damage
        super.damage(amount);
        
        // Trigger invulnerability
        invulnerable = true;
        invulnerabilityTimer = INVULNERABILITY_DURATION;
        
        // Call damage callback
        onDamage(amount, DamageSource.UNKNOWN);
    }
    
    /**
     * Moves the entity toward a target position.
     */
    public void moveToward(Vector3f target, float deltaTime) {
        if (!alive) return;
        
        Vector3f direction = new Vector3f(target).sub(position).normalize();
        direction.y = 0; // Don't move vertically through movement
        
        // Apply movement velocity
        Vector3f movement = new Vector3f(direction).mul(moveSpeed * deltaTime);
        velocity.x = movement.x;
        velocity.z = movement.z;
        
        // Face the movement direction
        faceDirection(direction, deltaTime);
    }
    
    /**
     * Makes the entity face a specific direction.
     */
    public void faceDirection(Vector3f direction, float deltaTime) {
        if (direction.length() < 0.1f) return;
        
        // Calculate target rotation
        // Note: We use -direction.z because the model's front faces negative Z
        float targetYaw = (float) Math.atan2(direction.x, -direction.z);
        float currentYaw = (float) Math.toRadians(rotation.y);
        
        // Smoothly rotate toward target
        float yawDiff = targetYaw - currentYaw;
        
        // Normalize angle difference to -PI to PI
        while (yawDiff > Math.PI) yawDiff -= 2 * Math.PI;
        while (yawDiff < -Math.PI) yawDiff += 2 * Math.PI;
        
        // Apply rotation speed limit
        float maxRotation = (float) Math.toRadians(turnSpeed * deltaTime);
        if (Math.abs(yawDiff) > maxRotation) {
            yawDiff = Math.signum(yawDiff) * maxRotation;
        }
        
        rotation.y = (float) Math.toDegrees(currentYaw + yawDiff);
    }
    
    /**
     * Checks if the entity can interact with a player.
     */
    public boolean canInteractWith(Player player) {
        if (!alive || player == null) return false;
        
        float distance = position.distance(player.getPosition());
        long currentTime = System.currentTimeMillis();
        
        return distance <= interactionRange && 
               (currentTime - lastInteractionTime) >= (INTERACTION_COOLDOWN * 1000);
    }
    
    /**
     * Handles interaction with a player.
     */
    public void interact(Player player) {
        if (!canInteractWith(player)) return;
        
        lastInteractionTime = System.currentTimeMillis();
        onInteract(player);
    }
    
    /**
     * Gets a random direction for wandering behavior.
     */
    protected Vector3f getRandomDirection() {
        float angle = (float) (Math.random() * 2 * Math.PI);
        return new Vector3f(
            (float) Math.sin(angle),
            0,
            (float) Math.cos(angle)
        );
    }
    
    /**
     * Checks if the entity can move to a specific position.
     * This method performs collision detection starting from the very bottom of the entity's legs.
     */
    public boolean canMoveTo(Vector3f targetPosition) {
        // Create bounding box starting from the bottom of the legs
        // targetPosition.y represents the bottom of the body, so subtract legHeight to get leg bottom
        float legBottomY = targetPosition.y - legHeight;
        BoundingBox targetBounds = new BoundingBox(
            targetPosition.x - width / 2.0f,
            legBottomY, // Start from bottom of legs
            targetPosition.z - length / 2.0f,
            targetPosition.x + width / 2.0f,
            targetPosition.y + height, // Extend to full height
            targetPosition.z + length / 2.0f
        );
        
        // Check for solid blocks in the target area
        int minX = (int) Math.floor(targetBounds.minX);
        int maxX = (int) Math.ceil(targetBounds.maxX);
        int minY = (int) Math.floor(targetBounds.minY);
        int maxY = (int) Math.ceil(targetBounds.maxY);
        int minZ = (int) Math.floor(targetBounds.minZ);
        int maxZ = (int) Math.ceil(targetBounds.maxZ);
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z) != null && 
                        world.getBlockAt(x, y, z).isSolid()) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Checks if the entity can move to a specific position while avoiding flowers.
     * This method is specifically for cows and other passive mobs that should avoid trampling flowers.
     */
    public boolean canMoveToAvoidingFlowers(Vector3f targetPosition) {
        // First do basic collision check
        if (!canMoveTo(targetPosition)) {
            return false;
        }
        
        // Check for flowers at ground level to avoid trampling them
        // Use bottom of legs position for ground checking
        float legBottomY = targetPosition.y - legHeight;
        int groundX = (int) Math.floor(targetPosition.x);
        int groundY = (int) Math.floor(legBottomY);
        int groundZ = (int) Math.floor(targetPosition.z);
        
        // Check current ground block and surrounding area
        for (int x = groundX - 1; x <= groundX + 1; x++) {
            for (int z = groundZ - 1; z <= groundZ + 1; z++) {
                // Check at ground level and one block up (where flowers typically are)
                for (int y = groundY; y <= groundY + 1; y++) {
                    var blockType = world.getBlockAt(x, y, z);
                    if (blockType != null && isFlower(blockType)) {
                        return false; // Avoid trampling flowers
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Helper method to identify flower blocks.
     */
    private boolean isFlower(BlockType blockType) {
        return blockType == BlockType.ROSE || 
               blockType == BlockType.DANDELION;
    }
    
    // Abstract methods that must be implemented by subclasses
    
    /**
     * Called when the player interacts with this entity.
     */
    public abstract void onInteract(Player player);
    
    /**
     * Called when the entity takes damage.
     */
    public abstract void onDamage(float damage, DamageSource source);
    
    /**
     * Called when the entity dies.
     */
    @Override
    protected abstract void onDeath();
    
    /**
     * Gets the items this entity should drop when it dies.
     */
    public abstract ItemStack[] getDrops();
    
    // Getters
    public float getMoveSpeed() { return moveSpeed; }
    public float getTurnSpeed() { return turnSpeed; }
    public boolean isInvulnerable() { return invulnerable; }
    public float getInvulnerabilityTimer() { return invulnerabilityTimer; }
    public boolean isMoving() { return isMoving; }
    public float getInteractionRange() { return interactionRange; }
    public Vector3f getTargetDirection() { return new Vector3f(targetDirection); }
    public float getLegHeight() { return legHeight; }
    
    // Setters
    public void setMoveSpeed(float moveSpeed) { this.moveSpeed = moveSpeed; }
    public void setTurnSpeed(float turnSpeed) { this.turnSpeed = turnSpeed; }
    public void setInteractionRange(float range) { this.interactionRange = range; }
    public void setTargetDirection(Vector3f direction) { this.targetDirection.set(direction); }
    
    /**
     * Enumeration of damage sources for damage handling.
     */
    public enum DamageSource {
        UNKNOWN,
        PLAYER,
        ENVIRONMENT,
        FALL,
        DROWNING,
        FIRE,
        EXPLOSION
    }
}