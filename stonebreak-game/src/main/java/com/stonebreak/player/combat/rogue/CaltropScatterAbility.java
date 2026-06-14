package com.stonebreak.player.combat.rogue;

import static com.stonebreak.player.PlayerConstants.CALTROP_CLUSTER_COUNT;
import static com.stonebreak.player.PlayerConstants.CALTROP_CONE_ANGLE_DEG;
import static com.stonebreak.player.PlayerConstants.CALTROP_CONE_RANGE;
import static com.stonebreak.player.PlayerConstants.CALTROP_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.CALTROP_DURATION;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Rogue active (F): terrain denial. Scatters a fan of caltrop clusters across a forward cone; each
 * cluster is a world entity that flat-foots the first other entity to step on it, enabling guaranteed
 * Rogue crits through the terrain. The Rogue is immune to their own caltrops. Does NOT break stealth.
 *
 * <p>State machine: IDLE -&gt; COOLDOWN. The clusters are independent world entities (see
 * {@link com.stonebreak.mobs.entities.CaltropCluster}) and manage their own contact/expiry.</p>
 */
public class CaltropScatterAbility {

    private enum State { IDLE, COOLDOWN }

    /** Clusters are dropped onto the surface found within this many blocks above/below the player's feet. */
    private static final int GROUND_SCAN_UP = 3;
    private static final int GROUND_SCAN_DOWN = 6;

    private State state = State.IDLE;
    private float cooldownRemaining;

    public boolean isActive() {
        return state != State.IDLE;
    }

    public boolean isOnCooldown() {
        return state == State.COOLDOWN;
    }

    public float getCooldownRemaining() {
        return state == State.COOLDOWN ? cooldownRemaining : 0f;
    }

    /**
     * Scatters {@link com.stonebreak.player.PlayerConstants#CALTROP_CLUSTER_COUNT} clusters across a
     * forward cone. Always succeeds when off cooldown (no target/line-of-sight requirement).
     */
    public boolean tryActivate(Player player) {
        if (state != State.IDLE) return false;

        EntityManager entityManager = Game.getEntityManager();
        World world = Game.getWorld();
        if (entityManager == null || world == null) return false;

        Vector3f origin = player.getPosition();
        Vector3f forward = new Vector3f(player.getCamera().getFront());
        forward.y = 0f;
        if (forward.lengthSquared() < 0.0001f) {
            forward.set(0f, 0f, 1f); // looking straight up/down — default to +Z
        }
        forward.normalize();

        float baseYaw = (float) Math.atan2(forward.x, forward.z);
        float halfAngle = (float) Math.toRadians(CALTROP_CONE_ANGLE_DEG) * 0.5f;

        for (int i = 0; i < CALTROP_CLUSTER_COUNT; i++) {
            // Fan evenly across the cone; stagger distance so clusters don't land on one arc.
            float angleT = CALTROP_CLUSTER_COUNT == 1 ? 0.5f : (float) i / (CALTROP_CLUSTER_COUNT - 1);
            float yaw = baseYaw - halfAngle + angleT * (2f * halfAngle);
            float distance = CALTROP_CONE_RANGE * (0.45f + 0.55f * ((i % 2 == 0) ? 1f : 0.7f));

            float x = origin.x + (float) Math.sin(yaw) * distance;
            float z = origin.z + (float) Math.cos(yaw) * distance;
            float y = findGroundY(world, x, z, origin.y);

            Vector3f clusterPos = new Vector3f(x, y, z);
            entityManager.spawnCaltropCluster(clusterPos, CALTROP_DURATION);
        }

        state = State.COOLDOWN;
        cooldownRemaining = CALTROP_COOLDOWN;
        return true;
    }

    public void update(float deltaTime, Player player) {
        if (state == State.COOLDOWN) {
            cooldownRemaining -= deltaTime;
            if (cooldownRemaining <= 0f) {
                state = State.IDLE;
            }
        }
    }

    public void reset() {
        state = State.IDLE;
        cooldownRemaining = 0f;
    }

    /**
     * Finds the top of the first solid block at {@code (x, z)} by scanning down from just above the
     * player's feet. Returns the surface Y to rest a cluster on, or the player's feet Y as a fallback
     * when no surface is found within the scan window (e.g. over a void or unloaded terrain).
     */
    private float findGroundY(World world, float x, float z, float feetY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int startY = (int) Math.floor(feetY) + GROUND_SCAN_UP;
        int endY = (int) Math.floor(feetY) - GROUND_SCAN_DOWN;
        for (int by = startY; by >= endY; by--) {
            BlockType block = world.getBlockAt(bx, by, bz);
            if (block != null && block.isSolid()) {
                return by + 1f; // sit on top of the solid block
            }
        }
        return feetY;
    }
}
