package com.stonebreak.mobs.entities;

import static com.stonebreak.player.PlayerConstants.CALTROP_CONTACT_RADIUS;
import static com.stonebreak.player.PlayerConstants.FLAT_FOOTED_DURATION;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A caltrop cluster spawned by the Rogue's Caltrop Scatter. Rests on the ground for its duration and
 * flat-foots the first creature to step on it, then is consumed. The Rogue is immune by construction:
 * caltrops only test living entities (mobs), and the player is not one of them.
 *
 * <p>Detached from the caster and dumb, in the spirit of {@link LeylineBreachZone}: it owns only its
 * lifetime and the single contact check.</p>
 */
public class CaltropCluster extends Entity {

    private final float duration;

    public CaltropCluster(World world, Vector3f position, float duration) {
        super(world, position);
        this.duration = duration;
        this.width = 0.4f;
        this.height = 0.25f;
        this.length = 0.4f;
        // Small, low spiky footprint for the glow-cube render path.
        this.scale.set(0.4f, 0.25f, 0.4f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        // Stationary: keep velocity zeroed so EntityManager's physics step is a no-op.
        velocity.set(0, 0, 0);
        onGround = true;

        if (age > duration) {
            alive = false;
            return;
        }

        EntityManager entityManager = world.getEntityManager(); // server-spawned: scan the OWNING world
        if (entityManager == null) {
            entityManager = Game.getEntityManager();
        }
        if (entityManager == null) return;

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            if (entity.getPosition().distance(position) > CALTROP_CONTACT_RADIUS) continue;

            entity.applyStatusEffect(StatusEffectType.FLAT_FOOTED, FLAT_FOOTED_DURATION, 0f);
            alive = false; // cluster is consumed on contact
            return;
        }
    }

    /** 0..1 fraction of the cluster's lifetime remaining. */
    public float getRemainingFraction() {
        return Math.max(0f, 1f - age / duration);
    }

    @Override
    public boolean isSelfPropelled() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled externally by EntityRenderer (glow-cube path).
    }

    @Override
    public EntityType getType() {
        return EntityType.CALTROP_CLUSTER;
    }
}
