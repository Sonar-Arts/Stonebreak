package com.stonebreak.mobs.chicken;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.util.DropUtil;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.PassiveMobAI;

/**
 * Chicken mob implementation.
 *
 * <p>A small passive mob driven by the shared {@link PassiveMobAI} framework:
 * it wanders and idles (never grazes — graze weight 0), occasionally flapping
 * its wings while idle. The obstacle-hop boost keeps the airborne chicken
 * driving forward so its footprint fully clears ledge edges before descending.
 */
public class Chicken extends LivingEntity {

    /**
     * Chicken personality: half idle / half wander, no grazing, occasional
     * wing flaps (1.2s matches the SB_Chicken.sbe Wingflap clip), hop boost
     * while airborne over obstacles; startles (freezes) when hit.
     */
    private static final PassiveMobAI.Config AI_CONFIG = new PassiveMobAI.Config(
            3.0f, 8.0f,          // state duration min/max
            3.0f, 8.0f,          // wander distance min/max
            0.8f, 180.0f,        // move speed multiplier, rotation speed (deg/s)
            0.5f, 0.5f, 0.0f,    // idle / wander weights; chickens never graze
            0.15f, 1.2f,         // wing-flap chance per second, flap duration
            2.2f, 0.8f,          // hop boost speed, hop duration cap
            PassiveMobAI.DamageResponse.STARTLE);

    // Apex = v²/80 blocks under entity gravity: 12.0 reaches ~1.8 blocks
    // (vs the 10.5/~1.38 LivingEntity default), giving the chicken ample
    // clearance and airtime to flutter-hop cleanly onto and over ledges.
    private static final float JUMP_VELOCITY = 12.0f;

    /**
     * Creates a new chicken at the specified position.
     */
    public Chicken(World world, Vector3f position) {
        super(world, position, EntityType.CHICKEN);

        this.mobAI = new PassiveMobAI(this, AI_CONFIG);
        this.jumpVelocity = JUMP_VELOCITY;

        // Smaller interaction range than a cow; faster turning for a light mob.
        this.interactionRange = 2.0f;
        this.turnSpeed = 180.0f;
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
        mobAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        mobAI.cleanup();
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
    }

    @Override
    public ItemStack[] getDrops() {
        if (Math.random() < 0.60) {
            int count = 1 + (int)(Math.random() * 2);
            return new ItemStack[] { new ItemStack(ItemType.FEATHER, count) };
        }
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() { return 2; }
}
