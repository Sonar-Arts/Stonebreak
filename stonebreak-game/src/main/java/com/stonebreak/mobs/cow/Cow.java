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
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;

/**
 * Cow mob implementation - the first living entity in Stonebreak.
 * Behaviour is assembled from shared {@link com.stonebreak.mobs.entities.ai.behavior.Behavior}s;
 * this class adds the cow-specific content: milk system and sounds.
 */
public class Cow extends LivingEntity {

    /** Turn rate and the mid-air drive the slow, long-bodied cow needs to land ledges. */
    private static final float ROTATION_SPEED = 180.0f;
    private static final float HOP_BOOST_SPEED = 2.2f;
    private static final float HOP_DURATION = 0.8f;

    // Milk system (basic implementation)
    private boolean canBeMilked;
    private float milkRegenTimer;
    private static final float MILK_REGEN_TIME = 300.0f; // 5 minutes

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

        this.textureVariant = textureVariant != null ? textureVariant : "default";

        // Initialize milk system
        this.canBeMilked = true;
        this.milkRegenTimer = 0.0f;

        // Cow personality: an even idle/wander split with occasional grazing, and a bolt for it
        // when hit. (No AwarenessController: the investigate/pursue drive made cows slowly
        // gravitate toward the player; awareness stays reserved for hostile mobs.)
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                new FleeBehavior(10.0f, 4.0f, 1.0f),
                StandStillBehavior.idle(0.4f, 3.0f, 8.0f),
                new WanderBehavior(0.4f, 3.0f, 8.0f, 0.8f),
                StandStillBehavior.graze(0.2f, 3.0f, 8.0f));

        // Set interaction range for cows
        this.interactionRange = 2.5f;

        // Set faster turning speed for cows
        this.turnSpeed = 180.0f; // Faster rotation for more responsive movement
    }

    /**
     * Updates the cow's behavior, AI, and state. AI and the animation clock run
     * in {@code LivingEntity.update}; only cow-specific systems live here.
     */
    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);

        updateMilkSystem(deltaTime);
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
     * Rendering is handled by EntityRenderer in EntityManager.
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer
    }

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
        mobAI.onDamaged(damage);
    }

    /**
     * Handles cow death and item drops.
     */
    @Override
    protected void onDeath() {
        mobAI.cleanup();
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

    public boolean isCanBeMilked() { return canBeMilked; }
    public void setCanBeMilked(boolean canBeMilked) { this.canBeMilked = canBeMilked; }
    public float getMilkRegenTimer() { return milkRegenTimer; }
    public void setMilkRegenTimer(float timer) { this.milkRegenTimer = timer; }
}
