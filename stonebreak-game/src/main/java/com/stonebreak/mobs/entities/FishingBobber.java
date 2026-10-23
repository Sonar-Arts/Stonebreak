package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Bobber entity spawned when the player casts a fishing rod.
 * Launched with an arc, then settles with a small bob animation when it lands.
 * When settled in water, a random bite timer triggers a bite event; the bobber
 * dips below the surface until the player reels in or the bite window expires.
 * Physics (gravity + block collision) are handled by EntityCollision.applyEntityPhysics.
 */
public class FishingBobber extends Entity {

    private static final float LAUNCH_SPEED   = 12.0f;
    private static final float LAUNCH_UP_BIAS = 2.5f;
    private static final float BOB_AMPLITUDE  = 0.05f;
    private static final float BOB_FREQUENCY  = 2.5f;

    private static final float BITE_DELAY_MIN = 3.0f;
    private static final float BITE_DELAY_MAX = 15.0f;
    private static final float BITE_WINDOW    = 5.0f;
    private static final float BITE_DIP       = -0.15f;

    private static final Random RNG = new Random();

    private boolean settled       = false;
    private float   floatY        = -1f;
    private boolean inWaterSettled = false;

    private float   biteDelay      = 0f;
    private float   biteTimer      = 0f;
    private boolean fishBiting     = false;
    private float   biteWindowTimer = 0f;

    public FishingBobber(World world, Vector3f position, Vector3f direction) {
        super(world, position);
        this.width  = 0.2f;
        this.height = 0.2f;
        this.length = 0.2f;
        this.scale.set(0.2f, 0.2f, 0.2f);

        Vector3f normalizedDir = new Vector3f(direction).normalize();
        this.velocity.set(
            normalizedDir.x * LAUNCH_SPEED,
            normalizedDir.y * LAUNCH_SPEED + LAUNCH_UP_BIAS,
            normalizedDir.z * LAUNCH_SPEED
        );
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        if (!settled && (onGround || inWater)) {
            settled = true;
            velocity.set(0, 0, 0);
            if (inWater) {
                int bx = (int) Math.floor(position.x);
                int bz = (int) Math.floor(position.z);
                int by = (int) Math.floor(position.y + height / 2.0f);
                while (world.getBlockAt(bx, by + 1, bz) == BlockType.WATER) by++;
                floatY = by + 1.0f;
                position.y = floatY;
                inWaterSettled = true;
                biteDelay = BITE_DELAY_MIN + RNG.nextFloat() * (BITE_DELAY_MAX - BITE_DELAY_MIN);
            }
        }

        if (settled) {
            velocity.set(0, 0, 0);
            if (floatY >= 0f) {
                position.y = floatY;
            }
        }

        if (inWaterSettled) {
            if (!fishBiting) {
                biteTimer += deltaTime;
                if (biteTimer >= biteDelay) {
                    fishBiting = true;
                    biteWindowTimer = 0f;
                }
            } else {
                biteWindowTimer += deltaTime;
                if (biteWindowTimer >= BITE_WINDOW) {
                    fishBiting = false;
                    biteTimer = 0f;
                    biteDelay = BITE_DELAY_MIN + RNG.nextFloat() * (BITE_DELAY_MAX - BITE_DELAY_MIN);
                }
            }
        }
    }

    /**
     * Visual Y offset applied by the renderer. Only the bobber floating in water
     * animates: when a fish is biting it dips below the surface, otherwise it
     * gently bobs on the waves. A bobber that landed on solid ground sits still.
     */
    public float getBobOffset() {
        if (!inWaterSettled) return 0f;
        if (fishBiting) return BITE_DIP;
        return (float) Math.sin(age * BOB_FREQUENCY) * BOB_AMPLITUDE;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendered externally by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.BOBBER;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    public boolean isSettled() {
        return settled;
    }

    public boolean isFishBiting() {
        return fishBiting;
    }

    // ─── Replication (#341: the bobber is a server entity; clients hold shadows) ─────────

    /** Visual states replicated through {@code EntityAnimS2C}: in flight or on land, floating, biting. */
    static final String STATE_CAST = "cast";
    static final String STATE_FLOAT = "float";
    static final String STATE_BITE = "bite";

    @Override
    public String networkVisualState() {
        if (!inWaterSettled) return STATE_CAST;
        return fishBiting ? STATE_BITE : STATE_FLOAT;
    }

    /** Shadow side: adopt the server bobber's float/bite state so {@link #getBobOffset} matches it. */
    @Override
    public void applyNetworkState(String state) {
        inWaterSettled = STATE_FLOAT.equals(state) || STATE_BITE.equals(state);
        fishBiting = STATE_BITE.equals(state);
        settled = inWaterSettled;
    }

    /** Shadows skip {@link #update}: advance the clock the bob animation reads. */
    @Override
    public void updateClientVisuals(float deltaTime) {
        age += deltaTime;
    }

    /**
     * The live bobber cast by player {@code ownerPlayerId} in {@code entityManager} (its
     * replicated shadow on a client), or null.
     */
    public static FishingBobber findOwnedBy(EntityManager entityManager, int ownerPlayerId) {
        if (entityManager == null || ownerPlayerId < 0) {
            return null;
        }
        for (Entity e : entityManager.getEntitiesByType(EntityType.BOBBER)) {
            if (e instanceof FishingBobber bobber && bobber.isAlive()
                    && bobber.getOwnerPlayerId() == ownerPlayerId) {
                return bobber;
            }
        }
        return null;
    }
}
