package com.stonebreak.mobs.cow;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.AnimationController;

/**
 * Cow mob implementation - the first living entity in Stonebreak.
 * Provides basic cow behavior including wandering, idle states, and player interaction.
 */
public class Cow extends LivingEntity {
    // Cow-specific constants from our design (defined in EntityType.COW)
    
    // Behavior properties
    private float wanderDirection;
    private float wanderTimer;
    private float idleTimer;
    private boolean isGrazing;
    private float grazingTimer;
    
    // Sound system
    private float mooTimer;
    private static final float MIN_MOO_INTERVAL = 15.0f;
    private static final float MAX_MOO_INTERVAL = 45.0f;
    
    // Milk system (basic implementation)
    private boolean canBeMilked;
    private float milkRegenTimer;
    private static final float MILK_REGEN_TIME = 300.0f; // 5 minutes
    
    // AI system
    private final CowAI cowAI;
    
    // Animation system
    private CowModel.CowAnimation currentAnimation;
    private float animationTransitionTime;
    private static final float ANIMATION_TRANSITION_DURATION = 0.5f;
    private final AnimationController animationController;
    
    /**
     * Creates a new cow at the specified position.
     */
    public Cow(World world, Vector3f position) {
        super(world, position, EntityType.COW);
        
        // Initialize cow-specific properties
        this.wanderDirection = (float)(Math.random() * 360.0);
        this.wanderTimer = 0.0f;
        this.idleTimer = 0.0f;
        this.isGrazing = false;
        this.grazingTimer = 0.0f;
        
        // Initialize sound timer
        this.mooTimer = MIN_MOO_INTERVAL + (float)(Math.random() * (MAX_MOO_INTERVAL - MIN_MOO_INTERVAL));
        
        // Initialize milk system
        this.canBeMilked = true;
        this.milkRegenTimer = 0.0f;
        
        // Initialize AI
        this.cowAI = new CowAI(this);
        
        // Initialize animation system
        this.currentAnimation = CowModel.CowAnimation.IDLE;
        this.animationTransitionTime = 0.0f;
        this.animationController = new AnimationController(this);
        
        // Set interaction range for cows
        this.interactionRange = 2.5f;
        
        // Set faster turning speed for cows
        this.turnSpeed = 180.0f; // Faster rotation for more responsive movement
    }
    
    /**
     * Updates the cow's behavior, AI, and state.
     */
    @Override
    public void update(float deltaTime) {
        // Call parent update first
        super.update(deltaTime);
        
        // Update cow-specific systems
        updateMilkSystem(deltaTime);
        updateSoundSystem(deltaTime);
        updateBehaviorTimers(deltaTime);
        
        // Update AI behavior
        if (cowAI != null) {
            cowAI.update(deltaTime);
        }
        
        // Update animation system
        updateAnimationState(deltaTime);
        animationController.updateAnimations(deltaTime);
    }
    
    /**
     * Updates the milk regeneration system.
     */
    private void updateMilkSystem(float deltaTime) {
        if (!canBeMilked) {
            milkRegenTimer += deltaTime;
            if (milkRegenTimer >= MILK_REGEN_TIME) {
                canBeMilked = true;
                milkRegenTimer = 0.0f;
            }
        }
    }
    
    /**
     * Updates the cow's mooing sound system.
     */
    private void updateSoundSystem(float deltaTime) {
        mooTimer -= deltaTime;
        if (mooTimer <= 0) {
            // Play cow moo sound (placeholder - will be implemented with actual sound system)
            playMooSound();
            
            // Reset timer with random interval
            mooTimer = MIN_MOO_INTERVAL + (float)(Math.random() * (MAX_MOO_INTERVAL - MIN_MOO_INTERVAL));
        }
    }
    
    /**
     * Updates behavior-related timers.
     */
    private void updateBehaviorTimers(float deltaTime) {
        wanderTimer += deltaTime;
        idleTimer += deltaTime;
        
        if (isGrazing) {
            grazingTimer += deltaTime;
        }
    }
    
    /**
     * Updates the cow's animation state based on behavior.
     */
    private void updateAnimationState(float deltaTime) {
        // Determine target animation based on AI state
        CowModel.CowAnimation targetAnimation = getAnimationForBehavior(cowAI.getCurrentState());
        
        // Check if animation should change
        if (targetAnimation != currentAnimation) {
            // Start animation transition
            currentAnimation = targetAnimation;
            animationTransitionTime = 0.0f;
        }
        
        // Update transition timer
        if (animationTransitionTime < ANIMATION_TRANSITION_DURATION) {
            animationTransitionTime += deltaTime;
        }
    }
    
    /**
     * Gets the appropriate animation for a cow behavior state.
     */
    private CowModel.CowAnimation getAnimationForBehavior(CowAI.CowBehaviorState behaviorState) {
        switch (behaviorState) {
            case WANDERING:
                return CowModel.CowAnimation.WALKING;
            case GRAZING:
                return CowModel.CowAnimation.GRAZING;
            case IDLE:
            default:
                return CowModel.CowAnimation.IDLE;
        }
    }
    
    /**
     * Gets the current animation with transition blending.
     */
    public CowModel.CowAnimation getCurrentAnimation() {
        // If we're still transitioning, we might want to blend animations
        // For simplicity, we'll just return the current animation
        return currentAnimation;
    }
    
    /**
     * Gets the animation transition progress (0.0 to 1.0).
     */
    public float getAnimationTransitionProgress() {
        return Math.min(animationTransitionTime / ANIMATION_TRANSITION_DURATION, 1.0f);
    }
    
    /**
     * Checks if the cow is currently transitioning between animations.
     */
    public boolean isAnimationTransitioning() {
        return animationTransitionTime < ANIMATION_TRANSITION_DURATION;
    }
    
    /**
     * Plays a cow moo sound (placeholder implementation).
     */
    private void playMooSound() {
        // System.out.println("Cow moos at " + getPosition());
    }
    
    /**
     * Renders the cow using a basic model (placeholder implementation).
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer in EntityManager
    }
    
    /**
     * Gets the cow's entity type.
     */
    @Override
    public EntityType getType() {
        return EntityType.COW;
    }
    
    /**
     * Handles player interaction with the cow.
     */
    @Override
    public void onInteract(Player player) {
        if (!isAlive()) return;
        
        // Basic interaction - petting the cow
        
        // Play happy cow sound
        playMooSound();
    }
    
    /**
     * Handles damage to the cow.
     */
    @Override
    public void onDamage(float damage, DamageSource source) {
        // Add visual damage indicators or particles here
    }
    
    /**
     * Handles cow death and item drops.
     */
    @Override
    protected void onDeath() {
        // Cleanup AI state and references
        if (cowAI != null) {
            cowAI.cleanup();
        }
    }
    
    /**
     * Gets the items this cow drops when it dies.
     */
    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }
    
    /**
     * Sets the cow's grazing state.
     */
    public void setGrazing(boolean grazing) {
        this.isGrazing = grazing;
        if (grazing) {
            this.grazingTimer = 0.0f;
        }
    }
    
    /**
     * Makes the cow start wandering in a specific direction.
     */
    public void startWandering(float direction) {
        this.wanderDirection = direction;
        this.wanderTimer = 0.0f;
    }
    
    /**
     * Makes the cow go idle.
     */
    public void startIdling() {
        this.idleTimer = 0.0f;
        this.velocity.set(0, velocity.y, 0); // Stop horizontal movement
    }
    
    /**
     * Checks if the cow can be milked.
     */
    public boolean canBeMilked() {
        return canBeMilked && isAlive();
    }
    
    /**
     * Milks the cow, setting canBeMilked to false.
     */
    public void milk() {
        if (canBeMilked()) {
            canBeMilked = false;
            milkRegenTimer = 0.0f;
        }
    }
    
    /**
     * Makes the cow jump by applying upward velocity.
     * Jump height is calibrated to clear one block like the player.
     */
    public void jump() {
        if (isOnGround()) {
            Vector3f velocity = getVelocity();
            // Jump velocity matches player's JUMP_FORCE exactly for consistent one-block jumps
            velocity.y = 8.5f; // Same as player's JUMP_FORCE for proper one-block height
            setVelocity(velocity);
            // Immediately set onGround to false to prevent multiple jumps (same as player)
            setOnGround(false);
        }
    }
    
    /**
     * Checks if the cow should jump to reach a target position.
     * Returns true if there's a single block obstacle that can be jumped over.
     */
    public boolean shouldJumpToReach(Vector3f targetPosition) {
        Vector3f currentPos = getPosition();
        
        // Check if there's a block directly in front that we can jump over
        Vector3f direction = new Vector3f(targetPosition).sub(currentPos).normalize();
        
        // Check multiple positions in front to be more thorough
        for (int i = 1; i <= 2; i++) {
            Vector3f testPos = new Vector3f(currentPos).add(direction.x * i, 0, direction.z * i);
            int blockX = (int) Math.floor(testPos.x);
            int blockY = (int) Math.floor(currentPos.y);
            int blockZ = (int) Math.floor(testPos.z);
            
            // Check if there's a solid block at ground level
            var groundBlock = world.getBlockAt(blockX, blockY, blockZ);
            if (groundBlock != null && groundBlock.isSolid()) {
                // Check if there's a solid block one level up (would be too high to jump)
                var upperBlock = world.getBlockAt(blockX, blockY + 1, blockZ);
                if (upperBlock != null && upperBlock.isSolid()) {
                    return false; // Can't jump over a 2-block high obstacle
                }
                
                // Check if there's space to land on the other side
                Vector3f landingPos = new Vector3f(currentPos).add(direction.x * 2.5f, 0, direction.z * 2.5f);
                if (canMoveTo(landingPos)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Enhanced collision detection specifically for cow movement.
     * Checks for solid blocks in the intended path and prevents movement into walls.
     */
    public boolean canMoveInDirection(Vector3f direction, float distance) {
        Vector3f currentPos = getPosition();
        Vector3f targetPos = new Vector3f(currentPos).add(
            direction.x * distance,
            0,
            direction.z * distance
        );
        
        // Use the standard collision detection method
        return canMoveTo(targetPos);
    }
    
    /**
     * Checks if the cow is currently walking against a wall.
     */
    public boolean isWalkingAgainstWall() {
        Vector3f currentPos = getPosition();
        Vector3f velocity = getVelocity();
        
        // If not moving, not walking against a wall
        if (velocity.length() < 0.1f) {
            return false;
        }
        
        // Check if there's a wall in the direction of movement
        Vector3f movementDirection = new Vector3f(velocity.x, 0, velocity.z).normalize();
        Vector3f checkPos = new Vector3f(currentPos).add(
            movementDirection.x * 1.0f,
            0,
            movementDirection.z * 1.0f
        );
        
        return !canMoveTo(checkPos);
    }
    
    // Getters for AI and behavior system
    public float getWanderDirection() { return wanderDirection; }
    public float getWanderTimer() { return wanderTimer; }
    public float getIdleTimer() { return idleTimer; }
    public boolean isGrazing() { return isGrazing; }
    public float getGrazingTimer() { return grazingTimer; }
    public CowAI getAI() { return cowAI; }
    public AnimationController getAnimationController() { return animationController; }
    public World getWorld() { return world; }
    
    // Setters
    public void setWanderDirection(float direction) { this.wanderDirection = direction; }
    public void setCanBeMilked(boolean canBeMilked) { this.canBeMilked = canBeMilked; }
}