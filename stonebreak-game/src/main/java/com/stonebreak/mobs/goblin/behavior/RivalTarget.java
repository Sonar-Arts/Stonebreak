package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.AttackTarget;
import com.stonebreak.mobs.entities.ai.perception.SightLine;
import com.stonebreak.mobs.goblin.Goblin;
import org.joml.Vector3f;

/**
 * The nearest goblin of the rival pack this goblin's pack is fighting. Pack fights are noisy — any
 * goblin about comes to see — but credit no player.
 */
public final class RivalTarget implements AttackTarget {

    private final Goblin goblin;
    private final DamageType damageType;
    private final SightLine sight = new SightLine();
    private final Vector3f aim = new Vector3f();

    public RivalTarget(Goblin goblin, DamageType damageType) {
        this.goblin = goblin;
        this.damageType = damageType;
    }

    private Goblin rival() {
        return goblin.pack().nearestEnemy(goblin.getPosition());
    }

    @Override
    public boolean engaged(AiContext context) {
        return rival() != null;
    }

    @Override
    public Vector3f position(AiContext context) {
        Goblin rival = rival();
        return rival != null ? rival.getPosition() : null;
    }

    @Override
    public boolean canSee(AiContext context, Vector3f eye) {
        Goblin rival = rival();
        if (rival == null) {
            return false;
        }
        aim.set(rival.getPosition()).add(0, aimHeight(context), 0);
        return sight.clear(context.world(), eye, aim, true);
    }

    @Override
    public boolean hurt(AiContext context, float amount, float knockback) {
        Goblin rival = rival();
        return rival != null && rival.damage(amount, LivingEntity.DamageSource.MOB, damageType,
                goblin.getPosition(), false);
    }

    @Override
    public float aimHeight(AiContext context) {
        Goblin rival = rival();
        return rival != null ? rival.getHeight() * 0.6f : 0.0f;
    }

    @Override
    public boolean noisy(AiContext context) {
        return true;
    }
}
