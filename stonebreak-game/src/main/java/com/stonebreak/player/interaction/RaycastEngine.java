package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Camera;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;
import java.util.List;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.RAY_CAST_DISTANCE;

/**
 * Performs voxel ray-marching from the player's eye to find targeted blocks. Three
 * variants cover breaking/placement (ignore water) and bucket interactions (detect
 * water). Also provides a ray/plane intersection helper used by face-picking logic.
 */
public class RaycastEngine {

    private static final float STEP_SIZE = 0.025f;

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
        return marchToFirstSolid();
    }

    /** Alias used by placement code — identical logic to raycast(). */
    public Vector3i raycastForPlacement() {
        return marchToFirstSolid();
    }

    /** First non-air block hit (water is opaque). Used by bucket interactions. */
    public Vector3i raycastIncludingWater() {
        Vector3f origin = eyeOrigin();
        Vector3f direction = camera.getFront();
        for (float d = 0; d < RAY_CAST_DISTANCE; d += STEP_SIZE) {
            Vector3f p = new Vector3f(direction).mul(d).add(origin);
            int bx = (int) Math.floor(p.x);
            int by = (int) Math.floor(p.y);
            int bz = (int) Math.floor(p.z);
            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != BlockType.AIR) {
                return new Vector3i(bx, by, bz);
            }
        }
        return null;
    }

    public Vector3f eyeOrigin() {
        Vector3f p = state.getPosition();
        return new Vector3f(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
    }

    public Vector3f eyeDirection() {
        return camera.getFront();
    }

    /**
     * Ray/plane intersection. Returns distance along ray to intersection, or -1 if
     * parallel or behind the origin.
     */
    public static float rayIntersectsPlane(Vector3f rayOrigin, Vector3f rayDirection,
                                           Vector3f planePoint, Vector3f planeNormal) {
        float denominator = rayDirection.dot(planeNormal);
        if (Math.abs(denominator) < 0.001f) return -1;
        Vector3f toPlane = new Vector3f(planePoint).sub(rayOrigin);
        float t = toPlane.dot(planeNormal) / denominator;
        return t > 0.001f ? t : -1;
    }

    private static final float ENTITY_ATTACK_RANGE = 4.0f;

    /**
     * Returns the closest living entity along the player's look ray within melee range, or null.
     */
    public LivingEntity raycastEntity(List<LivingEntity> entities) {
        Vector3f origin = eyeOrigin();
        Vector3f dir = camera.getFront();
        LivingEntity closest = null;
        float closestT = ENTITY_ATTACK_RANGE;
        for (LivingEntity entity : entities) {
            float t = rayAABBIntersect(origin, dir, entity.getBoundingBox());
            if (t < closestT) {
                closestT = t;
                closest = entity;
            }
        }
        return closest;
    }

    private float rayAABBIntersect(Vector3f origin, Vector3f dir, Entity.BoundingBox box) {
        float tMin = 0.0f;
        float tMax = Float.MAX_VALUE;

        // X slab
        if (Math.abs(dir.x) < 1e-6f) {
            if (origin.x < box.minX || origin.x > box.maxX) return Float.MAX_VALUE;
        } else {
            float t1 = (box.minX - origin.x) / dir.x;
            float t2 = (box.maxX - origin.x) / dir.x;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Float.MAX_VALUE;
        }

        // Y slab
        if (Math.abs(dir.y) < 1e-6f) {
            if (origin.y < box.minY || origin.y > box.maxY) return Float.MAX_VALUE;
        } else {
            float t1 = (box.minY - origin.y) / dir.y;
            float t2 = (box.maxY - origin.y) / dir.y;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Float.MAX_VALUE;
        }

        // Z slab
        if (Math.abs(dir.z) < 1e-6f) {
            if (origin.z < box.minZ || origin.z > box.maxZ) return Float.MAX_VALUE;
        } else {
            float t1 = (box.minZ - origin.z) / dir.z;
            float t2 = (box.maxZ - origin.z) / dir.z;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Float.MAX_VALUE;
        }

        return tMin >= 0.0f ? tMin : Float.MAX_VALUE;
    }

    private Vector3i marchToFirstSolid() {
        Vector3f origin = eyeOrigin();
        Vector3f direction = camera.getFront();
        for (float d = 0; d < RAY_CAST_DISTANCE; d += STEP_SIZE) {
            Vector3f p = new Vector3f(direction).mul(d).add(origin);
            int bx = (int) Math.floor(p.x);
            int by = (int) Math.floor(p.y);
            int bz = (int) Math.floor(p.z);
            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != BlockType.AIR && bt != BlockType.WATER) {
                return new Vector3i(bx, by, bz);
            }
        }
        return null;
    }
}
