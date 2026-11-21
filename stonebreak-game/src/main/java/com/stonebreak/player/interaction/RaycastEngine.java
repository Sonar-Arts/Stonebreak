package com.stonebreak.player.interaction;

import com.openmason.engine.physics.RayHit;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Camera;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import java.util.List;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.RAY_CAST_DISTANCE;

/**
 * Rays from the player's eye through the engine physics (exact voxel DDA against block shapes and
 * model boxes, OpenMason #117) to find targeted blocks. Three variants cover breaking/placement
 * (ignore water), bucket interactions (detect water) and line of sight. Also provides a ray/plane
 * intersection helper used by face-picking logic.
 */
public class RaycastEngine {

    private final PhysicsState state;
    private final Camera camera;
    private World world;

    public RaycastEngine(PhysicsState state, Camera camera, World world) {
        this.state = state;
        this.camera = camera;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /** First solid (non-air, non-water) block hit; water is transparent. */
    public Vector3i raycast() {
        return marchToFirstSolid(eyeOrigin(), camera.getFront(), RAY_CAST_DISTANCE);
    }

    /** First solid block hit along the look ray within {@code maxDistance} (e.g. trap placement). */
    public Vector3i raycast(float maxDistance) {
        return marchToFirstSolid(eyeOrigin(), camera.getFront(), maxDistance);
    }

    /** Alias used by placement code — identical logic to raycast(). */
    public Vector3i raycastForPlacement() {
        return marchToFirstSolid(eyeOrigin(), camera.getFront(), RAY_CAST_DISTANCE);
    }

    /** First non-air block hit (water is opaque). Used by bucket interactions. */
    public Vector3i raycastIncludingWater() {
        Vector3d origin = eyeOrigin();
        Vector3f direction = camera.getFront();
        return cellOf(cast(origin, direction, RAY_CAST_DISTANCE,
                WorldPhysics.PICK_MASK | WorldPhysics.DOOR_CELL, WorldPhysics.WATER));
    }

    public Vector3d eyeOrigin() {
        Vector3d p = state.getPosition();
        return new Vector3d(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
    }

    public Vector3f eyeDirection() {
        return camera.getFront();
    }

    /**
     * Unit direction from {@link #eyeOrigin()} toward what the crosshair is on — the
     * direction projectiles leave the eyes in. In first person that is just the look
     * direction; the third-person camera sits behind and above the eyes, so a shot
     * parallel to its ray would land off the crosshair. Instead it aims at the point the
     * camera ray reaches (the first solid block, or {@link #AIM_DISTANCE} out).
     */
    public Vector3f aimDirection() {
        return aimDirectionFrom(eyeOrigin());
    }

    /**
     * {@link #aimDirection()} for a projectile leaving {@code origin} instead of the eyes
     * (e.g. a staff head): the unit direction from it to the point the crosshair is on.
     */
    public Vector3f aimDirectionFrom(Vector3dc origin) {
        Vector3d cam = new Vector3d(camera.getPosition());
        Vector3f front = new Vector3f(camera.getFront()).normalize();
        float behind = (float) cam.distance(origin);
        if (behind < 1e-3f) {
            return front;
        }
        float hit = distanceToFirstSolid(cam, front, AIM_DISTANCE);
        // Never aim at a point level with or behind the origin, or the shot swings sideways.
        float t = Math.max(hit == Float.MAX_VALUE ? AIM_DISTANCE : hit, behind + 1f);
        Vector3d aim = new Vector3d(front).mul(t).add(cam).sub(origin).normalize();
        return new Vector3f((float) aim.x, (float) aim.y, (float) aim.z);
    }

    /**
     * Where a projectile drawn from {@code source} (a held item's tip) actually leaves: the
     * source itself, unless it is null or a solid block stands between it and the eyes — a
     * staff pushed into a wall must not cast from its far side — when it is the eyes.
     */
    public Vector3d launchOrigin(Vector3dc source) {
        Vector3d eye = eyeOrigin();
        if (source == null) {
            return eye;
        }
        Vector3d toSource = new Vector3d(source).sub(eye);
        double length = toSource.length();
        if (length < 1e-3) {
            return eye;
        }
        toSource.div(length);
        Vector3f dir = new Vector3f((float) toSource.x, (float) toSource.y, (float) toSource.z);
        boolean blocked = distanceToFirstSolid(eye, dir, (float) length) < length;
        return blocked ? eye : new Vector3d(source);
    }

    /** How far out the third-person aim point sits when the crosshair is on open sky. */
    private static final float AIM_DISTANCE = 64f;

    /**
     * Ray/plane intersection. Returns distance along ray to intersection, or -1 if
     * parallel or behind the origin.
     */
    public static float rayIntersectsPlane(Vector3dc rayOrigin, Vector3fc rayDirection,
                                           Vector3dc planePoint, Vector3fc planeNormal) {
        float denominator = rayDirection.dot(planeNormal);
        if (Math.abs(denominator) < 0.001f) return -1;
        // Plane point minus ray origin in double, then small enough for float.
        float t = (float) (((planePoint.x() - rayOrigin.x()) * planeNormal.x()
                + (planePoint.y() - rayOrigin.y()) * planeNormal.y()
                + (planePoint.z() - rayOrigin.z()) * planeNormal.z()) / denominator);
        return t > 0.001f ? t : -1;
    }

    /** Melee reach from the eye to a target's box; the server's melee check derives from it. */
    public static final float ENTITY_ATTACK_RANGE = 4.0f;

    /**
     * Returns the closest living entity along the player's look ray within melee range, or null.
     */
    public LivingEntity raycastEntity(List<LivingEntity> entities) {
        Vector3d origin = eyeOrigin();
        Vector3f dir = camera.getFront();
        LivingEntity closest = null;
        float closestT = ENTITY_ATTACK_RANGE;
        for (LivingEntity entity : entities) {
            float t = entity.getHitBox().rayIntersect(origin, dir);
            if (t < closestT) {
                closestT = t;
                closest = entity;
            }
        }
        return closest;
    }

    /**
     * Returns the closest living entity along an arbitrary ray within {@code maxRange}, or null
     * if no entity is hit or solid terrain blocks the ray before reaching it. Used by long-range
     * hitscan abilities (e.g. the Ranger's Culling Shot) whose direction is locked independently
     * of the current camera.
     */
    public LivingEntity raycastEntityAlong(Vector3dc origin, Vector3fc direction,
                                           List<LivingEntity> entities, float maxRange) {
        LivingEntity closest = null;
        float closestT = maxRange;
        for (LivingEntity entity : entities) {
            float t = entity.getHitBox().rayIntersect(origin, direction);
            if (t < closestT) {
                closestT = t;
                closest = entity;
            }
        }
        if (closest == null) return null;
        return distanceToFirstSolid(origin, direction, closestT) < closestT ? null : closest;
    }

    /**
     * Distance along the ray to the first thing that blocks sight — a block (stairs and stalagmites
     * by their shape: the open part of a cell lets the ray through), a door panel, a torch or a scene
     * collider; water and open doorways are clear — or {@link Float#MAX_VALUE} if the ray is clear
     * out to {@code maxDistance}.
     */
    public float distanceToFirstSolid(Vector3dc origin, Vector3fc direction, float maxDistance) {
        return distanceToFirstSolid(origin, direction, maxDistance, false);
    }

    /**
     * {@link #distanceToFirstSolid(Vector3dc, Vector3fc, float)}, optionally looking through leaves —
     * for eyes that make a player out between the twigs, where a block-solid view would not. Leaves
     * are aimable cells like any block to the physics cache, so the ray resumes past each leaf cell
     * it meets rather than masking them out.
     */
    public float distanceToFirstSolid(Vector3dc origin, Vector3fc direction, float maxDistance,
                                      boolean throughLeaves) {
        if (!throughLeaves) {
            RayHit h = cast(origin, direction, maxDistance, WorldPhysics.SIGHT_MASK, 0);
            return h == null ? Float.MAX_VALUE : (float) h.distance;
        }
        float length = direction.length();
        if (length < 1e-6f) {
            return Float.MAX_VALUE;
        }
        Vector3f unit = new Vector3f(direction).div(length);
        Vector3d from = new Vector3d(origin);
        float travelled = 0.0f;
        for (int leaves = 0; leaves < MAX_LEAF_CELLS && travelled < maxDistance; leaves++) {
            RayHit h = cast(from, unit, maxDistance - travelled, WorldPhysics.SIGHT_MASK, 0);
            if (h == null) {
                return Float.MAX_VALUE;
            }
            BlockType bt = h.kind == RayHit.KIND_CELL ? world.getBlockAt(h.x, h.y, h.z) : null;
            if (bt == null || !bt.isLeaves()) {
                return travelled + (float) h.distance;
            }
            // Through the leaf cell: resume just past where the ray leaves it.
            float exit = (float) cellExit(from, unit, h.x, h.y, h.z) + 1e-4f;
            travelled += exit;
            from.set(unit).mul(travelled).add(origin);
        }
        return travelled < maxDistance ? travelled : Float.MAX_VALUE;
    }

    /** Leaf cells one see-through ray may pass before it gives up (a forest is not transparent forever). */
    private static final int MAX_LEAF_CELLS = 64;

    /** Distance along a unit ray from {@code from} to where it leaves cell (x, y, z). */
    private static double cellExit(Vector3dc from, Vector3fc unit, long x, long y, long z) {
        double exit = Double.MAX_VALUE;
        double[] o = {from.x(), from.y(), from.z()};
        float[] d = {unit.x(), unit.y(), unit.z()};
        long[] c = {x, y, z};
        for (int a = 0; a < 3; a++) {
            if (d[a] > 1e-6f) {
                exit = Math.min(exit, (c[a] + 1 - o[a]) / d[a]);
            } else if (d[a] < -1e-6f) {
                exit = Math.min(exit, (c[a] - o[a]) / d[a]);
            }
        }
        return Math.max(0.0, exit);
    }

    /**
     * The cell targeted along a ray: the first block surface (shaped blocks by their shape, so a
     * stair's open corner lets the ray through to what stands behind it), or — hitting a door panel
     * or torch model anywhere — that block's anchor cell (break, toggle and the crack overlay all
     * key on it). Animated cells are never targets themselves: the ray passes an open doorway.
     */
    private Vector3i marchToFirstSolid(Vector3dc origin, Vector3fc direction, float maxDistance) {
        return cellOf(cast(origin, direction, maxDistance, WorldPhysics.PICK_MASK, 0));
    }

    private final RayHit hit = new RayHit();

    private RayHit cast(Vector3dc origin, Vector3fc direction, float maxDistance, int mask, int fluidMask) {
        if (world == null) {
            return null;
        }
        boolean any = WorldPhysics.of(world).raycast(origin.x(), origin.y(), origin.z(),
                direction.x(), direction.y(), direction.z(), maxDistance, mask, fluidMask, hit);
        return any ? hit : null;
    }

    private static Vector3i cellOf(RayHit h) {
        if (h == null) {
            return null;
        }
        if (h.kind == RayHit.KIND_BOX && h.user != 0L) {
            BlockPos anchor = WorldPhysics.BlockPosKey.unpack(h.user);
            return new Vector3i(anchor.x(), anchor.y(), anchor.z());
        }
        // The targeted-cell API is int (block edits, highlight, packets): beyond +-2^31 nothing is
        // targetable rather than throwing (worldgen is valid to ~+-2^23 anyway, see the cube doc).
        if (h.x != (int) h.x || h.y != (int) h.y || h.z != (int) h.z) {
            return null;
        }
        return new Vector3i((int) h.x, (int) h.y, (int) h.z);
    }
}
