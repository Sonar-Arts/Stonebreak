package com.stonebreak.mobs.sheep;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.PassiveMobAI;

/**
 * Sheep mob implementation. Behaviour comes entirely from the shared
 * {@link PassiveMobAI} framework with sheep tuning.
 */
public class Sheep extends LivingEntity {

    /** Sheep personality: slightly restless (more wandering, quicker turns); flees when hit. */
    private static final PassiveMobAI.Config AI_CONFIG = new PassiveMobAI.Config(
            2.5f, 7.0f,          // state duration min/max
            3.0f, 8.0f,          // wander distance min/max
            0.85f, 200.0f,       // move speed multiplier, rotation speed (deg/s)
            0.35f, 0.45f, 0.2f,  // idle / wander / graze weights
            0.0f, 0.0f,          // no wing-flap gesture
            2.2f, 0.8f,          // hop boost: drive forward mid-air so the long body clears ledge edges
            PassiveMobAI.DamageResponse.FLEE);

    public Sheep(World world, Vector3f position) {
        this(world, position, "default");
    }

    public Sheep(World world, Vector3f position, String textureVariant) {
        super(world, position, EntityType.SHEEP);
        this.textureVariant = textureVariant != null ? textureVariant : "default";
        this.mobAI = new PassiveMobAI(this, AI_CONFIG);
        this.interactionRange = 2.5f;
        this.turnSpeed = 200.0f;
    }

    @Override
    public void render(Renderer renderer) {
        // Handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.SHEEP;
    }

    @Override
    public void onInteract(Player player) {
        if (!isAlive()) return;
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        if (source == DamageSource.PLAYER) {
            applyPlayerKnockback();
        }
        mobAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        mobAI.cleanup();
    }

    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() { return 4; }
}
