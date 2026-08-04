package com.stonebreak.mobs.sheep;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;

/**
 * Sheep mob implementation. Behaviour comes entirely from shared
 * {@link com.stonebreak.mobs.entities.ai.behavior.Behavior}s with sheep tuning.
 */
public class Sheep extends LivingEntity {

    /** Quick turns, and the mid-air drive a long body needs to clear ledge edges. */
    private static final float ROTATION_SPEED = 200.0f;
    private static final float HOP_BOOST_SPEED = 2.2f;
    private static final float HOP_DURATION = 0.8f;

    public Sheep(World world, Vector3f position) {
        this(world, position, "default");
    }

    public Sheep(World world, Vector3f position, String textureVariant) {
        super(world, position, EntityType.SHEEP);
        this.textureVariant = textureVariant != null ? textureVariant : "default";
        // Sheep personality: slightly restless — more wandering than a cow, and it bolts when hit.
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                new FleeBehavior(10.0f, 4.0f, 1.0f),
                StandStillBehavior.idle(0.35f, 2.5f, 7.0f),
                new WanderBehavior(0.45f, 3.0f, 8.0f, 0.85f),
                StandStillBehavior.graze(0.2f, 2.5f, 7.0f));
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
