package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.effects.FireTrailParticles;
import com.stonebreak.world.World;
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
    // Largest distance the bolt is allowed to advance between collision samples.
    // Sub-stepping the per-frame move at this granularity stops fast bolts from
    // tunnelling through thin blocks or skipping past mobs at low frame rates.
    private static final float COLLISION_STEP = 0.25f;
    /** How long an impacted bolt stays (at least): the longest trail/burst particle's life. */
    private static final float IMPACT_LINGER = 0.75f;
    /** Replicated (EntityAnimS2C) when the bolt hits, so a client shadow bursts and hides its core. */
    static final String IMPACT_STATE = "Impact";

    /** Unit travel direction; a homing subclass turns it in {@link #steer}. */
    protected final Vector3f direction;
    // Once true the bolt has hit something and stops moving; it stays alive only
    // long enough for its trail/impact particles to fade out before despawning.
    private boolean impacted = false;
    private float impactAge;
    public final FireTrailParticles particles = new FireTrailParticles();
    /** Shadow only: last interpolated position, to aim the trail behind its motion. */
    private final Vector3f lastShadowPos = new Vector3f(Float.NaN, 0f, 0f);
    /**
     * Which way the bolt is seen to fly, for the fireball's tail. A client shadow is built
     * with a placeholder direction, so it has no heading until it has been seen to move.
     */
    private final Vector3f heading = new Vector3f();
    private boolean hasHeading;

    public FireBolt(World world, Vector3f position, Vector3f direction) {
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
            if (drawsParticles()) {
                particles.update(deltaTime);
            }
            if ((impactAge >= IMPACT_LINGER && particles.isEmpty()) || age > maxAge() + 2.0f) {
                alive = false;
            }
            return;
        }

        if (age > maxAge()) {
            impact();
            return;
        }

        steer(deltaTime);

        // Advance the bolt in small sub-steps so collision is checked along the
        // whole travel segment rather than only at the destination point.
        float remaining = speed() * deltaTime;
        heading.set(direction);
        hasHeading = true;
        while (remaining > 0.0f && !impacted) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            remaining -= stepLen;

            Vector3f newPos = new Vector3f(direction).mul(stepLen).add(position);

            // Block collision at the leading sample point.
            BlockType block = world.getBlockAt(
                    (int) Math.floor(newPos.x),
                    (int) Math.floor(newPos.y),
                    (int) Math.floor(newPos.z));
            if (block != null && !passesThrough(block)) {
                impact();
                return;
            }

            boolean struck = strike(position, newPos);
            position.set(newPos);
            if (struck) {
                impact();
                return;
            }
        }

        // Spawn fire trail particles behind the bolt — only where it is drawn: the server world's
        // copy is never rendered (clients draw their shadow's own trail).
        if (drawsParticles()) {
            Vector3f backDir = new Vector3f(direction).negate();
            particles.spawn(position, backDir);
            particles.update(deltaTime);
        }
    }

    /** Flight speed, blocks per second. */
    protected float speed() {
        return BOLT_SPEED;
    }

    /** Seconds of flight before the bolt fizzles out. */
    protected float maxAge() {
        return MAX_AGE;
    }

    /** Turns {@link #direction} before this tick's move; the staff's bolt flies straight. */
    protected void steer(float deltaTime) {
    }

    /** Whether the bolt flies on through {@code block} rather than bursting on it. */
    protected boolean passesThrough(BlockType block) {
        return block == BlockType.AIR;
    }

    /**
     * Hits whatever is on the segment {@code from}→{@code to}, if anything: the staff's bolt burns
     * the first living entity on it.
     *
     * @return whether something was hit (the bolt is spent)
     */
    protected boolean strike(Vector3f from, Vector3f to) {
        EntityManager em = OwningEntityManager.of(this); // server-spawned: scan the OWNING world
        LivingEntity hit = em != null ? ProjectileSweep.firstHit(em, from, to, HIT_MARGIN) : null;
        if (hit == null) {
            return false;
        }
        ProjectileDamage.deal(this, hit, DAMAGE, LivingEntity.DamageSource.FIRE);
        return true;
    }

    /** What colour the ball and its trail burn. */
    public Palette palette() {
        return Palette.FIRE;
    }

    /**
     * A fireball's colours, hottest last: {@code rim} at the flickering edge through {@code body}
     * and {@code mid} to the {@code core}; {@code halo} is the glow around it and {@code trail} its
     * particles (RGB).
     */
    public record Palette(Vector3f rim, Vector3f body, Vector3f mid, Vector3f core, Vector3f halo,
                          Vector3f trail) {
        public static final Palette FIRE = new Palette(
                new Vector3f(0.55f, 0.04f, 0.0f), new Vector3f(1.0f, 0.36f, 0.02f),
                new Vector3f(1.0f, 0.78f, 0.22f), new Vector3f(1.0f, 0.97f, 0.86f),
                new Vector3f(1.0f, 0.42f, 0.08f), new Vector3f(1.0f, 0.35f, 0.0f));
        /** Will-o'-wisp fire: cold blue, white at the heart. */
        public static final Palette WISP = new Palette(
                new Vector3f(0.02f, 0.10f, 0.45f), new Vector3f(0.10f, 0.42f, 1.0f),
                new Vector3f(0.45f, 0.78f, 1.0f), new Vector3f(0.88f, 0.96f, 1.0f),
                new Vector3f(0.20f, 0.50f, 1.0f), new Vector3f(0.25f, 0.55f, 1.0f));
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
        if (!impacted && !Float.isNaN(lastShadowPos.x)) {
            Vector3f moved = new Vector3f(position).sub(lastShadowPos);
            if (moved.lengthSquared() > 1e-6f) {
                heading.set(moved).normalize();
                hasHeading = true;
                particles.spawn(position, moved.negate().normalize());
            }
        }
        lastShadowPos.set(position);
        particles.update(deltaTime);
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
     * Stops the bolt and emits an impact burst. The entity remains alive until
     * its particles fade so the trail does not pop out of existence.
     */
    private void impact() {
        if (impacted) return;
        impacted = true;
        particles.burst(position);
    }

    /** Whether the bolt has been seen to move, so {@link #getHeading} is meaningful. */
    public boolean hasHeading() {
        return hasHeading;
    }

    /** Unit direction the bolt is seen to fly in, copied into {@code dest}. */
    public Vector3f getHeading(Vector3f dest) {
        return dest.set(heading);
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
