package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * The Wisp's will-o'-wisp: a slow ball of cold blue fire that bends after the player it was thrown
 * at for its first few seconds, then flies straight on. Slow enough to sidestep, and it leaves its
 * victim burning briefly. It only hurts players — a wisp's fire passes through the forest's other
 * creatures.
 */
public final class WillOWispOrb extends FireBolt {

    /** Blocks per second: about a sprint, so a player who moves sideways gets out of its way. */
    static final float SPEED = 8.0f;
    /** Seconds it keeps bending toward its victim before it commits to a straight line. */
    static final float HOMING_SECONDS = 3.0f;
    /** How fast it can bend, degrees per second — a curve, never a hairpin. */
    static final float TURN_RATE_DEG = 70.0f;
    static final float MAX_AGE = 6.0f;
    static final float HIT_RADIUS = 0.45f;

    private final int victimId;
    private final float damage;
    private final float burnSeconds;
    private final float burnDps;

    private final Vector3f scratch = new Vector3f();

    /** A shadow or a stray orb: flies straight, hurts nobody in particular. */
    public WillOWispOrb(World world, Vector3f position, Vector3f direction) {
        this(world, position, direction, PlayerLocator.NEAREST, 0f, 0f, 0f);
    }

    public WillOWispOrb(World world, Vector3f position, Vector3f direction, int victimId,
                        float damage, float burnSeconds, float burnDps) {
        super(world, position, direction);
        this.victimId = victimId;
        this.damage = damage;
        this.burnSeconds = burnSeconds;
        this.burnDps = burnDps;
    }

    @Override
    protected float speed() {
        return SPEED;
    }

    @Override
    protected float maxAge() {
        return MAX_AGE;
    }

    /** Bends toward the victim's chest at a capped rate, while it still has the will to. */
    @Override
    protected void steer(float deltaTime) {
        if (age > HOMING_SECONDS) {
            return;
        }
        PlayerLocator players = roster();
        Vector3f target = players != null ? players.playerPosition(victimId, position, scratch) : null;
        if (target == null) {
            return;
        }
        target.add(0f, PlayerLocator.CHEST_HEIGHT, 0f).sub(position);
        if (target.lengthSquared() < 1.0e-4f) {
            return;
        }
        turnToward(direction, target.normalize(), (float) Math.toRadians(TURN_RATE_DEG) * deltaTime);
    }

    /** Rotates unit {@code dir} toward unit {@code goal} by at most {@code maxRadians}. */
    static void turnToward(Vector3f dir, Vector3f goal, float maxRadians) {
        float angle = dir.angle(goal);
        if (angle <= maxRadians || angle < 1.0e-5f) {
            dir.set(goal);
            return;
        }
        // Slerp by the fraction of the gap we are allowed to close this tick.
        float t = maxRadians / angle;
        float sin = (float) Math.sin(angle);
        float a = (float) Math.sin((1f - t) * angle) / sin;
        float b = (float) Math.sin(t * angle) / sin;
        dir.set(dir.x * a + goal.x * b, dir.y * a + goal.y * b, dir.z * a + goal.z * b).normalize();
    }

    /** Burns the first player it passes through. */
    @Override
    protected boolean strike(Vector3f from, Vector3f to) {
        PlayerLocator players = roster();
        if (players == null || !players.hitPlayerAt(to, HIT_RADIUS, damage, -1)) {
            return false;
        }
        players.ignitePlayerAt(to, HIT_RADIUS, burnSeconds, burnDps);
        return true;
    }

    private PlayerLocator roster() {
        PlayerLocator players = world != null ? world.getPlayerLocator() : null;
        return players == PlayerLocator.NONE ? null : players;
    }

    @Override
    public Palette palette() {
        return Palette.WISP;
    }

    @Override
    public EntityType getType() {
        return EntityType.WISP_ORB;
    }
}
