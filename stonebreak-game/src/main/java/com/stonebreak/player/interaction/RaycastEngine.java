package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.Camera;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

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
