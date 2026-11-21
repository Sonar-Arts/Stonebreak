package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.AttackTarget;
import com.stonebreak.mobs.entities.ai.perception.SightLine;
import com.stonebreak.mobs.goblin.Goblin;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The animal a goblin's pack is hunting, as something to swing or shoot at. Killing it is quiet
 * business — no noise for the pack to come investigate — and never credits a player.
 */
public final class QuarryTarget implements AttackTarget {

    private final Goblin goblin;
    private final DamageType damageType;
    private final SightLine sight = new SightLine();
    private final Vector3d aim = new Vector3d();

    /** @param damageType what the goblin's weapon deals: a dagger stabs, a patty smacker bludgeons */
    public QuarryTarget(Goblin goblin, DamageType damageType) {
        this.goblin = goblin;
        this.damageType = damageType;
    }

    private LivingEntity quarry() {
        return goblin.pack().quarry();
    }

    @Override
    public boolean engaged(AiContext context) {
        return quarry() != null;
    }

    @Override
    public Vector3d position(AiContext context) {
        LivingEntity quarry = quarry();
        return quarry != null ? quarry.getPosition() : null;
    }

    @Override
    public boolean canSee(AiContext context, Vector3dc eye) {
        LivingEntity quarry = quarry();
        if (quarry == null) {
            return false;
        }
        aim.set(quarry.getPosition()).add(0, aimHeight(context), 0);
        return sight.clear(context.world(), eye, aim, true);
    }

    @Override
    public boolean hurt(AiContext context, float amount, float knockback) {
        LivingEntity quarry = quarry();
        return quarry != null && quarry.damage(amount, LivingEntity.DamageSource.MOB, damageType,
                goblin.getPosition(), false);
    }

    @Override
    public float aimHeight(AiContext context) {
        LivingEntity quarry = quarry();
        return quarry != null ? quarry.getHeight() * 0.5f : 0.0f;
    }

    @Override
    public boolean noisy(AiContext context) {
        return false;
    }
}
