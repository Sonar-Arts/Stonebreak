package com.stonebreak.mobs.cow;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.util.DropUtil;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.AnimationController;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.audio.CowSounds;

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
    
    
    // Milk system (basic implementation)
    private boolean canBeMilked;
    private float milkRegenTimer;
    private static final float MILK_REGEN_TIME = 300.0f; // 5 minutes
    
    // AI system
    private final CowAI cowAI;
    
    // Texture variant system
    private final String textureVariant;
    
    // Animation system — drives the clip clock for the SBE cow renderer.
    private final AnimationController animationController;

    // Sound system
    private final CowSounds cowSounds;
    
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
        
        
        // Initialize milk system
        this.canBeMilked = true;
        this.milkRegenTimer = 0.0f;
        
        // Initialize AI
        this.cowAI = new CowAI(this);

        // Stealth awareness: cows detect and react to a stealthed player (demo of the universal
        // AwarenessController). Investigate/pursue overrides passive wander when not UNAWARE.
        this.awareness = new AwarenessController(this);
        
        // Initialize animation system
        this.animationController = new AnimationController(this);

        // Initialize sound system
        this.cowSounds = new CowSounds(world);

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
        updateBehaviorTimers(deltaTime);

        // Stealth awareness drives movement when SUSPICIOUS/ALERTED; otherwise run passive AI.
        awareness.update(deltaTime);
        if (!awareness.drive(deltaTime)) {
            cowAI.update(deltaTime);
        }

        // Advance the animation clock; the SBE cow renderer samples clips from it.
        animationController.updateAnimations(deltaTime);
        cowSounds.updateSounds(position, velocity, isOnGround());
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
     * Updates behavior-related timers.
     */
    private void updateBehaviorTimers(float deltaTime) {
        wanderTimer += deltaTime;
        idleTimer += deltaTime;
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
        
    }
    
    /**
     * Handles damage to the cow.
     */
    @Override
    public void onDamage(float damage, DamageSource source) {
        if (source == DamageSource.PLAYER) {
            applyPlayerKnockback();
        }
        cowAI.onDamaged(damage);
    }
    
    /**
     * Handles cow death and item drops.
     */
    @Override
    protected void onDeath() {
        cowAI.cleanup();
        cowSounds.reset();
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
    }

    @Override
    public ItemStack[] getDrops() {
        if (Math.random() < 0.40) {
            return new ItemStack[] { new ItemStack(ItemType.LEATHER, 1) };
        }
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() { return 5; }
    
    /**
     * Sets the cow's grazing state.
     */
    public void setGrazing(boolean grazing) {
        this.isGrazing = grazing;
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
    
    public boolean isCanBeMilked() { return canBeMilked; }
    public void setCanBeMilked(boolean canBeMilked) { this.canBeMilked = canBeMilked; }
    public float getMilkRegenTimer() { return milkRegenTimer; }
    public void setMilkRegenTimer(float timer) { this.milkRegenTimer = timer; }

    /**
     * Gets the cow's texture variant.
     */
    public String getTextureVariant() {
        return textureVariant;
    }

    /**
     * Gets the cow's sound system.
     */
    public CowSounds getCowSounds() {
        return cowSounds;
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