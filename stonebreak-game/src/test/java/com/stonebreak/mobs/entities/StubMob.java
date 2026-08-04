package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import org.joml.Vector3f;

/**
 * A minimal concrete mob with no world behind it, for the many rules that only need a mob's
 * position, velocity, rotation, dimensions and type.
 *
 * <p>Shared rather than re-declared per test class: steering, footsteps and water all read the
 * same handful of properties, and three private copies of this would drift.
 */
public class StubMob extends LivingEntity {

    private final EntityType type;

    public StubMob(EntityType type) {
        this(type, new Vector3f(0, 64, 0), null);
    }

    public StubMob(EntityType type, Vector3f position) {
        this(type, position, null);
    }

    /** With a world, for the rules that need real blocks underneath — water, ground, collision. */
    public StubMob(EntityType type, Vector3f position, com.stonebreak.world.World world) {
        super(world, position, type);
        this.type = type;
    }

    @Override
    public EntityType getType() {
        return type;
    }

    /** Footsteps are created by the base constructor; tests that want them make their own. */
    @Override
    protected boolean hasFootsteps() {
        return false;
    }

    @Override
    public void render(Renderer renderer) {
    }

    @Override
    public void onInteract(Player player) {
    }

    @Override
    public void onDamage(float damage, LivingEntity.DamageSource source) {
    }

    @Override
    protected void onDeath() {
    }

    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }
}
