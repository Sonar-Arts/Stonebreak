package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.ParticleCollider;
import com.stonebreak.blocks.BlockShape;
import com.stonebreak.world.World;

/**
 * Particle collision against the rendered world through {@link BlockShape} — the one collision-height rule
 * players, mobs and drops use — so debris rests on snow layers, stair treads and full blocks alike
 * (Stonebreak#436). Unloaded cells are air (nothing generates for a particle). Allocation-free.
 */
public final class BlockParticleCollider implements ParticleCollider {

    private World world;

    /** The world particles collide with (null = nothing blocks). Main thread, before the update. */
    public void setWorld(World world) {
        this.world = world;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solidHeight(x, y, z) > 0f;
    }

    @Override
    public float solidHeight(int x, int y, int z) {
        World w = world;
        if (w == null) {
            return 0f;
        }
        float h = BlockShape.collisionHeight(w, x, y, z);
        return h <= 0f ? 0f : Math.min(h, 1f);
    }
}
