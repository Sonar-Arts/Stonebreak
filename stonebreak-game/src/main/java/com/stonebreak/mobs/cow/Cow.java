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
    
    // Texture variant system
    private final String textureVariant;
    
    // Animation system
    private String currentAnimation;
    private float animationTransitionTime;
    private static final float ANIMATION_TRANSITION_DURATION = 0.5f;
    private final AnimationController animationController;
    
    /**
     * Creates a new cow at the specified position with default texture variant.
     */
    public Cow(World world, Vector3f position) {
        this(world, position, "default");
    }
    
    /**
     * Creates a new cow at the specified position with a specific texture variant.
     */
    public Cow(World world, Vector3f position, String textureVariant) {
        super(world, position, EntityType.COW);
        
        // Initialize texture variant
        this.textureVariant = textureVariant != null ? textureVariant : "default";
        
        // Initialize cow-specific properties
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
        this.currentAnimation = "IDLE";
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
        String targetAnimation = getAnimationForBehavior(cowAI.getCurrentState());
        
        // Check if animation should change
        if (!targetAnimation.equals(currentAnimation)) {
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
    private String getAnimationForBehavior(CowAI.CowBehaviorState behaviorState) {
        return switch (behaviorState) {
            case WANDERING -> "WALKING";
            case GRAZING -> "GRAZING";
            case IDLE -> "IDLE";
        };
    }
    
    /**
     * Gets the current animation with transition blending.
     */
    public String getCurrentAnimation() {
        // If we're still transitioning, we might want to blend animations
        // For simplicity, we'll just return the current animation
        return currentAnimation;
    }
    
    
    
    /**
     * Plays a cow moo sound.
     */
    private void playMooSound() {
        // TODO: Add cow moo sound file and implement proper sound playback
        // For now, using grass walk sound as placeholder
        try {
            var game = com.stonebreak.core.Game.getInstance();
            if (game != null) {
                var soundSystem = com.stonebreak.core.Game.getSoundSystem();
                if (soundSystem != null) {
                    soundSystem.playSoundWithVariation("grasswalk", 0.2f);
                }
            }
        } catch (Exception e) {
            // Silently handle sound system errors
        }
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
     * Makes the cow go idle.
     */
    public void startIdling() {
        this.idleTimer = 0.0f;
        this.velocity.set(0, velocity.y, 0); // Stop horizontal movement
    }
    
    
    /**
     * Gets the cow's AI controller.
     */
    public CowAI getAI() {
        return cowAI;
    }
    
    /**
     * Gets the cow's animation controller.
     */
    public AnimationController getAnimationController() {
        return animationController;
    }
    
    /**
     * Gets the cow's texture variant.
     */
    public String getTextureVariant() {
        return textureVariant;
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
    
    
    
    
}