package com.stonebreak.blocks.palisade;

import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.LivingEntity.DamageSource;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Spiked wall contact for mobs: a few times a second, every living entity pressed into a wall's
 * points ({@link SpikeContactRules}) is pricked and thrown back the way the points lean. The mobs'
 * own half-second i-frames keep one pressed against the wall from being pricked faster than
 * {@link SpikeContactRules#COOLDOWN_SECONDS}. Players are pricked by the server's health tick.
 *
 * <p>Ticks on authoritative worlds only, like {@link com.stonebreak.blocks.cactus.CactusContactSystem}.
 */
public final class SpikeContactSystem {

    /** Seconds between scans: often enough that a goblin walking into the points is caught at once. */
    static final float SCAN_INTERVAL = 0.1f;

    private final World world;
    private float timer;

    public SpikeContactSystem(World world) {
        this.world = world;
    }

    public void tick(float deltaTime) {
        timer += deltaTime;
        if (timer < SCAN_INTERVAL) {
            return;
        }
        timer %= SCAN_INTERVAL;
        EntityManager entities = world.getEntityManager();
        if (entities == null) {
            return;
        }
        for (LivingEntity entity : entities.getLivingEntities()) {
            if (!entity.isAlive()) {
                continue;
            }
            Vector3d p = entity.getPosition();
            float hw = entity.getWidth() / 2;
            float hl = entity.getLength() / 2;
            float[] lean = SpikeContactRules.pointsTouching(world, p.x - hw, p.y, p.z - hl,
                    p.x + hw, p.y + entity.getHeight(), p.z + hl);
            if (lean != null && entity.damage(SpikeContactRules.DAMAGE, DamageSource.ENVIRONMENT, p, false)) {
                entity.applyKnockback(new Vector3f(lean[0], 0, lean[1]),
                        SpikeContactRules.KNOCKBACK, SpikeContactRules.KNOCKBACK_LIFT);
            }
        }
    }
}
