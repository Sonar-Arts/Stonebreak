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
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StartleBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WingFlapBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;

/**
 * Chicken mob implementation.
 *
 * <p>A small passive mob assembled from shared behaviours: it wanders and idles (it is simply
 * never given a grazing behaviour), occasionally flapping its wings. The obstacle-hop boost keeps
 * the airborne chicken driving forward so its footprint fully clears ledge edges before descending.
 */
public class Chicken extends LivingEntity {

    /** Turn rate, plus the hop boost that keeps an airborne chicken driving over ledges. */
    private static final float ROTATION_SPEED = 180.0f;
    private static final float HOP_BOOST_SPEED = 2.2f;
    private static final float HOP_DURATION = 0.8f;

    // Apex = v²/80 blocks under entity gravity: 12.0 reaches ~1.8 blocks
    // (vs the 10.5/~1.38 LivingEntity default), giving the chicken ample
    // clearance and airtime to flutter-hop cleanly onto and over ledges.
    private static final float JUMP_VELOCITY = 12.0f;

    /**
     * Creates a new chicken at the specified position.
     */
    public Chicken(World world, Vector3f position) {
        super(world, position, EntityType.CHICKEN);

        // Chicken personality: half idle / half wander, never grazes, the occasional wing flap
        // (1.2s matches the SB_Chicken.sbe Wingflap clip), and it freezes rather than bolts.
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                new StartleBehavior(2.0f),
                new WingFlapBehavior(0.15f, 1.2f),
                StandStillBehavior.idle(0.5f, 3.0f, 8.0f),
                new WanderBehavior(0.5f, 3.0f, 8.0f, 0.8f));
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
