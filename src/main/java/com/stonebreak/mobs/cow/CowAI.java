package com.stonebreak.mobs.cow;

import org.joml.Vector3f;
import com.stonebreak.player.Player;
import com.stonebreak.core.Game;

/**
 * Simple AI controller for cow behavior.
 * Implements basic idle, wandering, and grazing states for Phase 2.
 * Will be expanded with more sophisticated behaviors in Phase 3.
 */
public class CowAI {
    // Reference to the cow this AI controls
    private Cow cow;
    
    // Behavior states
    private CowBehaviorState currentState;
    private float stateTimer;
    private float stateChangeTimer;
    
    // Behavior timing constants
    private static final float MIN_STATE_DURATION = 3.0f;
    private static final float MAX_STATE_DURATION = 8.0f;
    private static final float WANDER_DISTANCE = 5.0f;
    private static final float MOVEMENT_SPEED_MULTIPLIER = 0.8f;
    
    // Behavior weights (simple implementation for Phase 2)
    private static final float IDLE_CHANCE = 0.4f;
    private static final float WANDER_CHANCE = 0.4f;
    
    // Movement target for wandering
    private Vector3f wanderTarget;
    private boolean hasWanderTarget;
    
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
    }
    
    /**
     * Updates the cow's AI behavior.
     */
    public void update(float deltaTime) {
        if (!cow.isAlive()) return;
        
        stateTimer += deltaTime;
        stateChangeTimer -= deltaTime;
        
        // Check if it's time to change behavior state
        if (stateChangeTimer <= 0) {
            changeToRandomState();
            stateChangeTimer = getRandomStateDuration();
        }
        
        // Update current behavior
        switch (currentState) {
            case IDLE:
                handleIdleBehavior(deltaTime);
                break;
            case WANDERING:
                handleWanderBehavior(deltaTime);
                break;
            case GRAZING:
                handleGrazeBehavior(deltaTime);
                break;
        }
        
        // Check for player interaction (future implementation)
        checkForPlayerNearby();
    }
    
    /**
     * Handles idle behavior - cow stands still.
     */
    private void handleIdleBehavior(float deltaTime) {
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
        
        // Get or create wander target
        if (!hasWanderTarget || cow.distanceTo(wanderTarget) < 1.0f) {
            generateNewWanderTarget();
        }
        
        // Move toward target
        if (hasWanderTarget) {
            Vector3f cowPos = cow.getPosition();
            Vector3f direction = new Vector3f(wanderTarget).sub(cowPos).normalize();
            direction.y = 0; // Don't move vertically through AI
            
            // Apply movement
            Vector3f velocity = cow.getVelocity();
            velocity.x = direction.x * cow.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            velocity.z = direction.z * cow.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            cow.setVelocity(velocity);
            
            // Face movement direction
            cow.faceDirection(direction, deltaTime);
        }
    }
    
    /**
     * Handles grazing behavior - cow pretends to eat grass.
     */
    private void handleGrazeBehavior(float deltaTime) {
        // Stop movement
        Vector3f velocity = cow.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        cow.setVelocity(velocity);
        
        // Set grazing state
        cow.setGrazing(true);
    }
    
    /**
     * Generates a new random wander target near the cow.
     */
    private void generateNewWanderTarget() {
        Vector3f cowPos = cow.getPosition();
        
        // Try multiple times to find a valid target position
        for (int attempts = 0; attempts < 10; attempts++) {
            // Generate random direction and distance
            float angle = (float)(Math.random() * 2 * Math.PI);
            float distance = 2.0f + (float)(Math.random() * WANDER_DISTANCE);
            
            // Calculate target position
            Vector3f targetPos = new Vector3f(
                cowPos.x + (float)Math.cos(angle) * distance,
                cowPos.y, // Keep same Y level for now
                cowPos.z + (float)Math.sin(angle) * distance
            );
            
            // Check if the target position is valid (no collision with blocks and avoids flowers)
            if (cow.canMoveToAvoidingFlowers(targetPos)) {
                wanderTarget.set(targetPos);
                hasWanderTarget = true;
                return;
            }
        }
        
        // If no valid target found after 10 attempts, clear target
        hasWanderTarget = false;
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
                // Player is very close - maybe look at them
            }
        }
    }
    
    /**
     * Called when the cow takes damage (for future flee behavior).
     */
    public void onDamaged(float damage) {
        setState(CowBehaviorState.IDLE);
        stateChangeTimer = 2.0f; // Stay in current state for 2 seconds
    }
    
    /**
     * Called when a player gets nearby (for future interaction).
     */
    public void onPlayerNearby(Player player, float distance) {
        // Placeholder for now
    }
    
    // Getters
    public CowBehaviorState getCurrentState() { return currentState; }
    public float getStateTimer() { return stateTimer; }
    public Vector3f getWanderTarget() { return hasWanderTarget ? new Vector3f(wanderTarget) : null; }
    public boolean hasWanderTarget() { return hasWanderTarget; }
    
    // Setters for external control (useful for future complex behaviors)
    public void setWanderTarget(Vector3f target) {
        this.wanderTarget.set(target);
        this.hasWanderTarget = true;
    }
    
    public void clearWanderTarget() {
        this.hasWanderTarget = false;
    }
}