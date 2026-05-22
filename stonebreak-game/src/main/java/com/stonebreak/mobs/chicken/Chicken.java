package com.stonebreak.mobs.chicken;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.AnimationController;

/**
 * Chicken mob implementation.
 *
 * <p>A small passive mob that wanders and idles, occasionally flapping its
 * wings. Rendered from the {@code SB_Chicken.sbe} asset; see {@code Cow} for
 * the reference SBE-driven mob pattern.
 */
public class Chicken extends LivingEntity {

    // AI system
    private final ChickenAI chickenAI;

    // Animation system — drives the clip clock for the SBE chicken renderer.
    private final AnimationController animationController;

    /**
     * Creates a new chicken at the specified position.
     */
    public Chicken(World world, Vector3f position) {
        super(world, position, EntityType.CHICKEN);

        this.chickenAI = new ChickenAI(this);
        this.animationController = new AnimationController(this);

        // Smaller interaction range than a cow; faster turning for a light mob.
        this.interactionRange = 2.0f;
        this.turnSpeed = 180.0f;
    }

    /**
     * Updates the chicken's behavior, AI, and animation state.
     */
    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);

        if (chickenAI != null) {
            chickenAI.update(deltaTime);
        }

        // Advance the animation clock; the SBE renderer samples clips from it.
        animationController.updateAnimations(deltaTime);
    }

    /**
     * Rendering is handled by EntityRenderer in EntityManager.
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.CHICKEN;
    }

    @Override
    public void onInteract(Player player) {
        // No interaction behavior yet.
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        if (chickenAI != null) {
            chickenAI.onDamaged(damage);
        }
    }

    @Override
    protected void onDeath() {
        if (chickenAI != null) {
            chickenAI.cleanup();
        }
    }

    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }

    /**
     * Makes the chicken go idle - stops horizontal movement.
     */
    public void startIdling() {
        this.velocity.set(0, velocity.y, 0);
    }

    // Jump velocity for the chicken. A value of 8.5 reaches an apex of exactly
    // one block; 12.0 reaches ~2 blocks, giving the chicken ample clearance and
    // airtime to flutter-hop cleanly onto and over ledges.
    private static final float JUMP_VELOCITY = 12.0f;

    /**
     * Makes the chicken hop by applying upward velocity.
     */
    public void jump() {
        if (isOnGround()) {
            Vector3f velocity = getVelocity();
            velocity.y = JUMP_VELOCITY;
            setVelocity(velocity);
            setOnGround(false);
        }
    }

    /**
     * Gets the chicken's AI controller.
     */
    public ChickenAI getAI() {
        return chickenAI;
    }

    /**
     * Gets the chicken's animation controller.
     */
    public AnimationController getAnimationController() {
        return animationController;
    }

    // ── Network replication of behaviour state ──────────────────────────

    @Override
    public byte getNetworkBehaviorState() {
        return chickenAI != null ? (byte) chickenAI.getCurrentState().ordinal() : -1;
    }

    @Override
    public void applyNetworkBehaviorState(byte state) {
        if (chickenAI == null || state < 0) return;
        ChickenAI.ChickenBehaviorState[] values = ChickenAI.ChickenBehaviorState.values();
        if (state < values.length) chickenAI.setState(values[state]);
    }

    @Override
    public void updateShadowAnimation(float deltaTime) {
        // Shadow chickens skip update(); advance the clip clock and the AI's
        // state timer so looping clips run and the one-shot Wingflap (timed off
        // the state timer in EntityRenderer) plays through.
        animationController.updateAnimations(deltaTime);
        if (chickenAI != null) chickenAI.advanceShadowTimers(deltaTime);
    }
}
