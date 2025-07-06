package com.stonebreak.mobs.cow;

import org.joml.Vector3f;
import com.stonebreak.player.Player;
import com.stonebreak.core.Game;
import com.stonebreak.blocks.BlockType;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple AI controller for cow behavior.
 * Implements basic idle, wandering, and grazing states.
 */
public class CowAI {
    // Reference to the cow this AI controls
    private final Cow cow;
    
    // Behavior states
    private CowBehaviorState currentState;
    private float stateTimer;
    private float stateChangeTimer;
    
    // Behavior timing constants
    private static final float MIN_STATE_DURATION = 3.0f;
    private static final float MAX_STATE_DURATION = 8.0f;
    private static final float WANDER_DISTANCE = 8.0f;
    private static final float MOVEMENT_SPEED_MULTIPLIER = 0.8f;
    
    // Movement constants
    private static final float JUMP_COOLDOWN = 1.0f;
    private static final float OBSTACLE_CHECK_DISTANCE = 1.0f;
    private static final float ROTATION_SPEED = 180.0f; // degrees per second
    
    // Behavior weights
    private static final float IDLE_CHANCE = 0.4f;
    private static final float WANDER_CHANCE = 0.4f;
    
    // Movement target for wandering
    private final Vector3f wanderTarget;
    private boolean hasWanderTarget;
    
    // Jump management
    private float jumpCooldownTimer;
    
    // Path tracking for visualization
    private final List<Vector3f> pathPoints;
    private static final int MAX_PATH_POINTS = 20;
    
    /**
     * Behavior states for the cow AI.
     */
    public enum CowBehaviorState {
        IDLE,           // Standing still, occasionally looking around
        WANDERING,      // Walking to random nearby locations
        GRAZING         // Head down, eating grass animation
    }
    
    /**
     * Creates a new AI controller for the specified cow.
     */
    public CowAI(Cow cow) {
        this.cow = cow;
        this.currentState = CowBehaviorState.IDLE;
        this.stateTimer = 0.0f;
        this.stateChangeTimer = getRandomStateDuration();
        this.wanderTarget = new Vector3f();
        this.hasWanderTarget = false;
        this.jumpCooldownTimer = 0.0f;
        this.pathPoints = new ArrayList<>();
    }
    
    /**
     * Updates the cow's AI behavior.
     */
    public void update(float deltaTime) {
        if (!cow.isAlive()) return;
        
        stateTimer += deltaTime;
        stateChangeTimer -= deltaTime;
        
        // Update jump cooldown
        jumpCooldownTimer -= deltaTime;
        if (jumpCooldownTimer < 0) jumpCooldownTimer = 0;
        
        // Check if it's time to change behavior state
        if (stateChangeTimer <= 0) {
            changeToRandomState();
            stateChangeTimer = getRandomStateDuration();
        }
        
        // Update current behavior
        switch (currentState) {
            case IDLE -> handleIdleBehavior();
            case WANDERING -> handleWanderBehavior(deltaTime);
            case GRAZING -> handleGrazeBehavior();
        }
        
        // Update path tracking
        updatePathTracking();
        
        // Check for player interaction (future implementation)
        checkForPlayerNearby();
    }
    
    /**
     * Handles idle behavior - cow stands still.
     */
    private void handleIdleBehavior() {
        // Stop movement
        Vector3f velocity = cow.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        cow.setVelocity(velocity);
        
        // Set cow state
        cow.startIdling();
        cow.setGrazing(false);
    }
    
    /**
     * Handles wandering behavior - cow walks to random locations.
     */
    private void handleWanderBehavior(float deltaTime) {
        cow.setGrazing(false);
        
        // Check if we need a new target
        if (!hasWanderTarget || cow.distanceTo(wanderTarget) < 1.5f) {
            generateNewWanderTarget();
        }
        
        // Move toward target
        if (hasWanderTarget) {
            moveTowardTarget(deltaTime);
        }
    }
    
    /**
     * Handles grazing behavior - cow pretends to eat grass.
     */
    private void handleGrazeBehavior() {
        // Stop movement
        Vector3f velocity = cow.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        cow.setVelocity(velocity);
        
        // Set grazing state
        cow.setGrazing(true);
    }
    
    /**
     * Generates a new random wander target.
     */
    private void generateNewWanderTarget() {
        Vector3f cowPos = cow.getPosition();
        
        // Try to find a valid target position
        for (int attempts = 0; attempts < 10; attempts++) {
            float angle = (float)(Math.random() * 2 * Math.PI);
            float distance = 3.0f + (float)(Math.random() * (WANDER_DISTANCE - 3.0f));
            
            float targetX = cowPos.x + (float)Math.cos(angle) * distance;
            float targetZ = cowPos.z + (float)Math.sin(angle) * distance;
            
            // Snap to block center coordinates
            int blockX = (int)Math.floor(targetX);
            int blockZ = (int)Math.floor(targetZ);
            float centerX = blockX + 0.5f;
            float centerZ = blockZ + 0.5f;
            
            // Find ground level at target position
            float groundY = findGroundLevel(centerX, centerZ, cowPos.y);
            
            if (groundY != Float.NEGATIVE_INFINITY) {
                wanderTarget.set(centerX, groundY, centerZ);
                hasWanderTarget = true;
                return;
            }
        }
        
        // No valid target found
        hasWanderTarget = false;
    }
    
    /**
     * Finds the ground level at a given position.
     */
    private float findGroundLevel(float x, float z, float startY) {
        int blockX = (int)Math.floor(x);
        int blockZ = (int)Math.floor(z);
        int startBlockY = (int)Math.floor(startY);
        
        // Search downward for ground
        for (int y = startBlockY + 5; y >= startBlockY - 10; y--) {
            BlockType block = cow.getWorld().getBlockAt(blockX, y, blockZ);
            BlockType blockAbove = cow.getWorld().getBlockAt(blockX, y + 1, blockZ);
            
            // Found ground if current block is solid and above is air
            if (block != null && block.isSolid() && 
                (blockAbove == null || !blockAbove.isSolid())) {
                return y + 1.0f;
            }
        }
        
        return Float.NEGATIVE_INFINITY;
    }
    
    /**
     * Moves the cow toward the wander target.
     */
    private void moveTowardTarget(float deltaTime) {
        Vector3f cowPos = cow.getPosition();
        Vector3f direction = new Vector3f(wanderTarget).sub(cowPos);
        direction.y = 0; // Only horizontal movement
        
        if (direction.length() > 0.1f) {
            direction.normalize();
            
            // Update rotation
            float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x, direction.z)) + 180.0f;
            updateCowRotation(targetYaw, deltaTime);
            
            // Check for obstacles ahead
            if (checkForObstacleAhead(direction)) {
                // Try to jump if on ground and cooldown is ready
                if (cow.isOnGround() && jumpCooldownTimer <= 0) {
                    cow.jump();
                    jumpCooldownTimer = JUMP_COOLDOWN;
                }
            }
            
            // Apply movement
            Vector3f velocity = cow.getVelocity();
            velocity.x = direction.x * cow.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            velocity.z = direction.z * cow.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            cow.setVelocity(velocity);
        }
    }
    
    /**
     * Checks for obstacles in the movement direction.
     */
    private boolean checkForObstacleAhead(Vector3f direction) {
        Vector3f cowPos = cow.getPosition();
        
        // Check position ahead
        float checkX = cowPos.x + direction.x * OBSTACLE_CHECK_DISTANCE;
        float checkZ = cowPos.z + direction.z * OBSTACLE_CHECK_DISTANCE;
        
        int blockX = (int)Math.floor(checkX);
        int blockY = (int)Math.floor(cowPos.y);
        int blockZ = (int)Math.floor(checkZ);
        
        // Check if there's a block at foot level
        BlockType footBlock = cow.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (footBlock != null && footBlock.isSolid()) {
            // Check if we can jump over it (clear space above)
            BlockType blockAbove1 = cow.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
            BlockType blockAbove2 = cow.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
            
            // Can jump if there's at least 2 blocks of clearance above the obstacle
            return (blockAbove1 == null || !blockAbove1.isSolid()) && 
                   (blockAbove2 == null || !blockAbove2.isSolid());
        }
        
        // Also check if we need to jump up to a higher block
        BlockType upperBlock = cow.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
        if (upperBlock != null && upperBlock.isSolid()) {
            // There's a block above, check if we have room to jump
            BlockType blockAbove2 = cow.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
            BlockType blockAbove3 = cow.getWorld().getBlockAt(blockX, blockY + 3, blockZ);
            
            return (blockAbove2 == null || !blockAbove2.isSolid()) && 
                   (blockAbove3 == null || !blockAbove3.isSolid());
        }
        
        return false;
    }
    
    /**
     * Updates the cow's rotation smoothly.
     */
    private void updateCowRotation(float targetYaw, float deltaTime) {
        Vector3f currentRotation = cow.getRotation();
        float currentYaw = currentRotation.y;
        
        // Calculate shortest rotation path
        float deltaYaw = targetYaw - currentYaw;
        while (deltaYaw > 180.0f) deltaYaw -= 360.0f;
        while (deltaYaw < -180.0f) deltaYaw += 360.0f;
        
        // Apply rotation smoothly
        float maxRotation = ROTATION_SPEED * deltaTime;
        if (Math.abs(deltaYaw) > maxRotation) {
            deltaYaw = Math.signum(deltaYaw) * maxRotation;
        }
        
        cow.setRotation(new Vector3f(currentRotation.x, currentYaw + deltaYaw, currentRotation.z));
    }
    
    /**
     * Changes to a random behavior state based on weights.
     */
    private void changeToRandomState() {
        float random = (float)Math.random();
        
        CowBehaviorState newState;
        if (random < IDLE_CHANCE) {
            newState = CowBehaviorState.IDLE;
        } else if (random < IDLE_CHANCE + WANDER_CHANCE) {
            newState = CowBehaviorState.WANDERING;
        } else {
            newState = CowBehaviorState.GRAZING;
        }
        
        // Don't immediately switch to the same state
        if (newState == currentState && Math.random() < 0.5) {
            // 50% chance to pick a different state
            CowBehaviorState[] states = CowBehaviorState.values();
            do {
                newState = states[(int)(Math.random() * states.length)];
            } while (newState == currentState);
        }
        
        setState(newState);
    }
    
    /**
     * Sets the current behavior state.
     */
    public void setState(CowBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0.0f;
            
            // Reset target when changing to wander state
            if (newState == CowBehaviorState.WANDERING) {
                hasWanderTarget = false;
            }
        }
    }
    
    /**
     * Gets a random state duration between min and max.
     */
    private float getRandomStateDuration() {
        return MIN_STATE_DURATION + (float)(Math.random() * (MAX_STATE_DURATION - MIN_STATE_DURATION));
    }
    
    /**
     * Checks for nearby players (placeholder for future interaction system).
     */
    private void checkForPlayerNearby() {
        Player player = Game.getPlayer();
        if (player != null) {
            float distance = cow.distanceTo(player.getPosition());
            
            if (distance < 2.0f) {
                // Player is very close - AI could react here in the future
                // For now, just acknowledge the proximity but don't change behavior
            }
        }
    }
    
    /**
     * Called when the cow takes damage (for future flee behavior).
     */
    public void onDamaged(float damage) {
        // For now, just change to idle state regardless of damage amount
        // In the future, damage amount could affect flee behavior intensity
        setState(CowBehaviorState.IDLE);
        stateChangeTimer = 2.0f; // Stay in current state for 2 seconds
    }
    
    /**
     * Called when a player gets nearby (for future interaction).
     */
    public void onPlayerNearby(Player player, float distance) {
        // For now, just acknowledge the player presence
        // In the future, this could trigger different behaviors based on distance
        // For example: flee if too close, or look at player if at medium distance
    }
    
    // Getters
    public CowBehaviorState getCurrentState() { return currentState; }
    public float getStateTimer() { return stateTimer; }
    public Vector3f getWanderTarget() { return hasWanderTarget ? new Vector3f(wanderTarget) : null; }
    public boolean hasWanderTarget() { return hasWanderTarget; }
    
    // Setters for external control
    public void setWanderTarget(Vector3f target) {
        this.wanderTarget.set(target);
        this.hasWanderTarget = true;
    }
    
    public void clearWanderTarget() {
        this.hasWanderTarget = false;
    }
    
    /**
     * Updates path tracking for visualization.
     */
    private void updatePathTracking() {
        // Only track paths when debug overlay is visible
        if (com.stonebreak.core.Game.getDebugOverlay() == null || 
            !com.stonebreak.core.Game.getDebugOverlay().isVisible()) {
            // Clear existing paths when debug is off
            if (!pathPoints.isEmpty()) {
                pathPoints.clear();
            }
            return;
        }
        
        Vector3f currentPos = cow.getPosition();
        
        // Add current position if we're moving or if path is empty
        if (currentState == CowBehaviorState.WANDERING || pathPoints.isEmpty()) {
            // Check if we should add a new point (not too close to last point)
            if (pathPoints.isEmpty() || pathPoints.get(pathPoints.size() - 1).distance(currentPos) > 0.5f) {
                pathPoints.add(new Vector3f(currentPos));
                
                // Keep only recent path points
                if (pathPoints.size() > MAX_PATH_POINTS) {
                    pathPoints.remove(0);
                }
            }
        } else if (currentState == CowBehaviorState.IDLE) {
            // Clear path when idle for too long
            if (stateTimer > 3.0f) {
                pathPoints.clear();
            }
        }
    }
    
    /**
     * Gets the current path points for visualization.
     */
    public List<Vector3f> getPathPoints() {
        return new ArrayList<>(pathPoints);
    }
    
    /**
     * Clears debug path visualization data.
     */
    public void clearDebugPaths() {
        pathPoints.clear();
    }
    
    /**
     * Cleans up AI resources when the cow is removed.
     */
    public void cleanup() {
        // Clear any references
        hasWanderTarget = false;
        pathPoints.clear();
    }
}