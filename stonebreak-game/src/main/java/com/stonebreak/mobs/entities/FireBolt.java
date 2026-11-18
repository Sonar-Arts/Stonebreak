package com.stonebreak.mobs.entities;

import com.stonebreak.rendering.Renderer;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.TrailFx;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Projectile entity fired from the player when right-clicking with a Staff.
 * Travels in a straight line, damages living entities on contact, and
 * despawns on block collision or after its TTL expires.
 */
public class FireBolt extends Entity {

    private static final float BOLT_SPEED = 25.0f;
    private static final float MAX_AGE    = 5.0f;
    private static final float DAMAGE     = 8.0f;
    // Body boxes are grown by the bolt's half-size when it sweeps through them.
    private static final float HIT_MARGIN = 0.15f;
    /** How long an impacted bolt stays: the longest trail/burst particle's life. */
    private static final float IMPACT_LINGER = 0.75f;
    /** Flames streaming back from a flying bolt (Stonebreak#441; follows the bolt, launched along its back). */
    public static final String TRAIL_FX = "stonebreak:fire_trail";
    /** The burst of flame where the bolt strikes. */
    public static final String IMPACT_FX = "stonebreak:fire_burst";
    /** Replicated (EntityAnimS2C) when the bolt hits, so a client shadow bursts and hides its core. */
    static final String IMPACT_STATE = "Impact";

    private final Vector3f direction;
    private final com.openmason.engine.physics.RayHit rayHit = new com.openmason.engine.physics.RayHit();
    // Once true the bolt has hit something and stops moving; it stays alive only
    // long enough for its trail/impact particles to fade out before despawning.
    private boolean impacted = false;
    private float impactAge;
    /** Drawn copies only (see {@link #drawsParticles}): the server world's bolt is never rendered. */
    private final TrailFx trail = new TrailFx(TRAIL_FX);
    /** Shadow only: last interpolated position, to aim the trail behind its motion. */
    private final Vector3d lastShadowPos = new Vector3d(Double.NaN, 0, 0);

    public FireBolt(World world, Vector3dc position, Vector3f direction) {
        super(world, position);
        this.direction = new Vector3f(direction).normalize();
        this.width  = 0.3f;
        this.height = 0.3f;
        this.length = 0.3f;
        // Face the travel direction
        this.rotation.y = (float) Math.toDegrees(Math.atan2(-this.direction.x, -this.direction.z));
        // Scale the visual cube to match bounding box
        this.scale.set(0.3f, 0.3f, 0.3f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        // Keep velocity zeroed so EntityManager's physics step is a no-op
        velocity.set(0, 0, 0);
        onGround = false;

        // After impact the bolt no longer moves — it stays long enough for the trail and burst
        // to fade (on clients too: their shadow goes when this does), then despawns.
        if (impacted) {
            impactAge += deltaTime;
            if (impactAge >= IMPACT_LINGER || age > MAX_AGE + 2.0f) {
                alive = false;
            }
            return;
        }

        if (age > MAX_AGE) {
            impact();
            return;
        }

        // One swept move per update (OpenMason #117): the bolt's box against every block shape,
        // door and model in its path, and the centre line against water — no frame rate lets a fast
        // bolt tunnel through a thin block or skip past a mob.
        float travel = BOLT_SPEED * deltaTime;
        Vector3d end = new Vector3d(position).fma(travel, direction);
        float reach = 1.0f; // fraction of the move before the first block or water
        if (world != null && travel > 0.0f) {
            com.stonebreak.world.physics.WorldPhysics physics = com.stonebreak.world.physics.WorldPhysics.of(world);
            if (physics.sweep(position.x, position.y, position.z, end.x, end.y, end.z,
                    HIT_MARGIN, HIT_MARGIN, HIT_MARGIN, com.stonebreak.world.physics.WorldPhysics.PROJECTILE_MASK, rayHit)) {
                reach = (float) rayHit.distance;
            }
            if (physics.raycast(position.x, position.y, position.z, direction.x, direction.y, direction.z, travel,
                    0, com.stonebreak.world.physics.WorldPhysics.WATER, rayHit)) {
                reach = Math.min(reach, (float) (rayHit.distance / travel));
            }
        }
        Vector3d stop = new Vector3d(position).fma(travel * reach, direction);

        // Entity collision — the first living entity on the stretch it actually flew.
        EntityManager em = OwningEntityManager.of(this); // server-spawned: scan the OWNING world
        LivingEntity hit = em != null ? ProjectileSweep.firstHit(em, position, stop, HIT_MARGIN) : null;
        position.set(stop);
        if (hit != null) {
            ProjectileDamage.deal(this, hit, DAMAGE, LivingEntity.DamageSource.FIRE);
            impact();
            return;
        }
        if (reach < 1.0f) {
            impact();
            return;
        }

        // The fire trail follows the bolt — only where it is drawn: the server world's copy is
        // never rendered (clients trail their shadow instead).
        if (drawsParticles()) {
            trail.follow(position.x, position.y, position.z, -direction.x, -direction.y, -direction.z);
        }
    }

    private boolean drawsParticles() {
        return world == null || world.isRenderOnly();
    }

    /**
     * Client shadow: the server's bolt makes no particles for us, so trail behind the
     * interpolated motion here, and burst when the impact state arrives.
     */
    @Override
    public void updateClientVisuals(float deltaTime) {
        if (!impacted && !Double.isNaN(lastShadowPos.x)) {
            float bx = (float) (lastShadowPos.x - position.x);
            float by = (float) (lastShadowPos.y - position.y);
            float bz = (float) (lastShadowPos.z - position.z);
            float len = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (len > 1e-3f) {
                trail.follow(position.x, position.y, position.z, bx / len, by / len, bz / len);
            }
        }
        lastShadowPos.set(position);
    }

    @Override
    public void applyNetworkState(String state) {
        if (IMPACT_STATE.equals(state)) {
            impact();
        }
    }

    @Override
    public String networkVisualState() {
        return impacted ? IMPACT_STATE : null;
    }

    /**
     * Stops the bolt: the trail stops emitting and a burst of flame goes up where it struck. The
     * entity lingers while the trail's last flames fade (its core is no longer drawn), so a client's
     * shadow does not vanish before the impact state reaches it.
     */
    private void impact() {
        if (impacted) return;
        impacted = true;
        if (drawsParticles()) {
            trail.end();
            Fx.fire(IMPACT_FX, position.x, position.y, position.z);
        }
    }

    @Override
    public void onRemovedFromWorld() {
        trail.end();
    }

    /** Whether the bolt has struck something and is now just fading out. */
    public boolean isImpacted() {
        return impacted;
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
        // Rendering is handled externally by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.FIRE_BOLT;
    }
}
