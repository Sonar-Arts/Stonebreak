package com.stonebreak.mobs.goose;

import org.joml.Vector3f;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.AnimationController;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;

/**
 * Goose mob — a flighted passive creature.
 *
 * <p>On the ground it waddles, idles, floats on water, and flees the player; periodically it
 * takes off and joins/leads a {@link GooseFlock} flying in a V formation (see {@link GooseAI}).
 * Rendered from the {@code SB_Goose.sbe} asset; see {@code Chicken} for the reference
 * SBE-driven mob pattern.
 *
 * <p><b>Flight physics:</b> while airborne the goose is {@linkplain #isSelfPropelled()
 * self-propelled}, so {@code EntityManager} skips the external physics step (gravity,
 * ground-snap, terrain collision) and {@link #applyPhysics(float)} integrates motion with no
 * gravity. On the ground it falls through to the standard {@link LivingEntity} physics path,
 * identical to the other passive mobs.
 */
public class Goose extends LivingEntity {

    /** Flap-hop velocity; one value reaches ~2 blocks like the chicken. */
    private static final float JUMP_VELOCITY = 12.0f;
    /** Light per-frame damping applied to airborne velocity (no gravity in flight). */
    private static final float FLIGHT_AIR_DAMPING = 0.99f;

    private final GooseAI gooseAI;
    private final AnimationController animationController;

    public Goose(World world, Vector3f position) {
        super(world, position, EntityType.GOOSE);

        this.gooseAI = new GooseAI(this);
        this.animationController = new AnimationController(this);

        this.interactionRange = 2.0f;
        this.turnSpeed = 200.0f;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        gooseAI.update(deltaTime);
        animationController.updateAnimations(deltaTime);
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer.
    }

    @Override
    public EntityType getType() {
        return EntityType.GOOSE;
    }

    @Override
    public void onInteract(Player player) {
        // No interaction behavior yet.
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        if (source == DamageSource.PLAYER) {
            applyPlayerKnockback();
        }
        gooseAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        gooseAI.cleanup();
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
    }

    @Override
    public ItemStack[] getDrops() {
        if (Math.random() < 0.55) {
            int count = 1 + (int) (Math.random() * 3); // 1–3 feathers
            return new ItemStack[] { new ItemStack(ItemType.FEATHER, count) };
        }
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() {
        return 3;
    }

    /**
     * Self-propelled while airborne so the {@code EntityManager} skips the external
     * physics step and {@link GooseAI} owns the full 3D motion.
     */
    @Override
    public boolean isSelfPropelled() {
        return gooseAI.isAirborne();
    }

    /**
     * While airborne, integrate motion from velocity with no gravity (geese cruise above
     * the terrain). On the ground, defer to the standard mob physics so behavior matches
     * the other passive mobs exactly.
     */
    @Override
    protected void applyPhysics(float deltaTime) {
        if (gooseAI.isAirborne()) {
            age += deltaTime;
            position.add(new Vector3f(velocity).mul(deltaTime));
            velocity.mul(FLIGHT_AIR_DAMPING);
        } else {
            super.applyPhysics(deltaTime);
        }
    }

    /** Makes the goose go idle - stops horizontal movement. */
    public void startIdling() {
        this.velocity.set(0, velocity.y, 0);
    }

    /** Flap-hop by applying upward velocity (ground only). */
    public void jump() {
        if (isOnGround()) {
            Vector3f v = getVelocity();
            v.y = JUMP_VELOCITY;
            setVelocity(v);
            setOnGround(false);
        }
    }

    public GooseAI getAI() {
        return gooseAI;
    }

    public AnimationController getAnimationController() {
        return animationController;
    }
}
