package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
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
    public Vector3f aimDirectionFrom(Vector3f origin) {
        Vector3f cam = camera.getPosition();
        Vector3f front = new Vector3f(camera.getFront()).normalize();
        float behind = cam.distance(origin);
        if (behind < 1e-3f) {
            return front;
        }
        float hit = distanceToFirstSolid(cam, front, AIM_DISTANCE);
        // Never aim at a point level with or behind the origin, or the shot swings sideways.
        float t = Math.max(hit == Float.MAX_VALUE ? AIM_DISTANCE : hit, behind + 1f);
        return new Vector3f(front).mul(t).add(cam).sub(origin).normalize();
    }

    /**
     * Where a projectile drawn from {@code source} (a held item's tip) actually leaves: the
     * source itself, unless it is null or a solid block stands between it and the eyes — a
     * staff pushed into a wall must not cast from its far side — when it is the eyes.
     */
    public Vector3f launchOrigin(Vector3f source) {
        Vector3f eye = eyeOrigin();
        if (source == null) {
            return eye;
        }
        Vector3f toSource = new Vector3f(source).sub(eye);
        float length = toSource.length();
        if (length < 1e-3f) {
            return eye;
        }
        boolean blocked = distanceToFirstSolid(eye, toSource.div(length), length) < length;
        return blocked ? eye : new Vector3f(source);
    }

    /** How far out the third-person aim point sits when the crosshair is on open sky. */
    private static final float AIM_DISTANCE = 64f;

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

    /** Melee reach from the eye to a target's box; the server's melee check derives from it. */
    public static final float ENTITY_ATTACK_RANGE = 4.0f;

    /**
     * Returns the closest living entity along the player's look ray within melee range, or null.
     */
    public LivingEntity raycastEntity(List<LivingEntity> entities) {
        Vector3f origin = eyeOrigin();
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
    public LivingEntity raycastEntityAlong(Vector3f origin, Vector3f direction,
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
     * Distance along the ray to the first solid (non-air, non-water) block, or
     * {@link Float#MAX_VALUE} if the ray is clear out to {@code maxDistance}.
     */
    public float distanceToFirstSolid(Vector3f origin, Vector3f direction, float maxDistance) {
        List<PanelTarget> panels = doorPanelsNear(origin, maxDistance);
        List<float[]> sceneBoxes = world.getStaticCollisionBoxes(origin, maxDistance);
        for (float d = 0; d < maxDistance; d += STEP_SIZE) {
            Vector3f p = new Vector3f(direction).mul(d).add(origin);
            for (float[] box : sceneBoxes) {
                if (insidePanel(box, p)) return d;
            }
            for (PanelTarget panel : panels) {
                if (insidePanel(panel.box, p)) {
                    return d; // a door blocks line-of-sight exactly where its panel is
                }
            }
            int bx = (int) Math.floor(p.x);
            int by = (int) Math.floor(p.y);
            int bz = (int) Math.floor(p.z);
            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != BlockType.AIR && bt != BlockType.WATER
                    && !com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(bt)
                    && fillsPoint(bt, p, bx, by, bz)) {
                return d;
            }
        }
        return Float.MAX_VALUE;
    }

    /**
     * Whether the block in a cell actually occupies the ray's sample point.
     * Shaped blocks (stairs) leave part of their cell open, so a ray crossing
     * the open part must carry on to whatever stands behind it — otherwise you
     * would target, break and lose line of sight to a stair's empty corner.
     */
    private boolean fillsPoint(BlockType type, Vector3f p, int bx, int by, int bz) {
        if (type == BlockType.LIMESTONE_STALAGMITE) {
            // Only the spire itself is a target; the air beside it lets the ray through.
            return com.stonebreak.blocks.stalagmite.StalagmiteShape.contains(world, bx, by, bz, p.x, p.y, p.z);
        }
        if (!type.isStairs()) {
            return true;
        }
        float step = com.stonebreak.blocks.stairs.StairShape
                .stepHeight(world, bx, by, bz, type, p.x, p.z, p.x, p.z);
        return p.y - by <= step;
    }

    private Vector3i marchToFirstSolid(Vector3f origin, Vector3f direction, float maxDistance) {
        List<PanelTarget> panels = doorPanelsNear(origin, maxDistance);
        for (float d = 0; d < maxDistance; d += STEP_SIZE) {
            Vector3f p = new Vector3f(direction).mul(d).add(origin);

            // Model-centric door targeting: hitting the panel anywhere —
            // either block of its 2-tall model, open or closed pose — targets
            // the door's anchor cell (break, toggle, crack overlay all key on it).
            for (PanelTarget panel : panels) {
                if (insidePanel(panel.box, p)) {
                    return new Vector3i(panel.anchor);
                }
            }

            int bx = (int) Math.floor(p.x);
            int by = (int) Math.floor(p.y);
            int bz = (int) Math.floor(p.z);
            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != BlockType.AIR && bt != BlockType.WATER
                    && !com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(bt)
                    && fillsPoint(bt, p, bx, by, bz)) {
                // Animated cells are never colliders themselves — the ray
                // passes through an open doorway and hits what's behind.
                return new Vector3i(bx, by, bz);
            }
        }
        return null;
    }

    // ─── Door panel targets (model-tied AABBs) ───────────────────────────────

    private static final class PanelTarget {
        final float[] box;
        final Vector3i anchor;

        PanelTarget(float[] box, Vector3i anchor) {
            this.box = box;
            this.anchor = anchor;
        }
    }

    /**
     * Animated-block models (door panels) close enough for the ray to reach,
     * as world AABBs from {@code AnimatedBlockShapes.worldAabb} — the geometry
     * posed at the current state's clip end pose, i.e. exactly what is
     * rendered. Doors are sparse, so this is normally an empty list.
     */
    private List<PanelTarget> doorPanelsNear(Vector3f origin, float maxDistance) {
        List<PanelTarget> panels = null;
        float reach = maxDistance + 3f; // a posed model extends at most ~2 blocks from its anchor
        float reachSq = reach * reach;
        for (com.openmason.engine.util.BlockPos pos : world.getAnimatedBlockRegistry().positions()) {
            float dx = pos.x() + 0.5f - origin.x;
            float dy = pos.y() + 1.0f - origin.y;
            float dz = pos.z() + 0.5f - origin.z;
            if (dx * dx + dy * dy + dz * dz > reachSq) continue;
            BlockType type = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(type)) continue;
            float[] box = com.stonebreak.blocks.anim.AnimatedBlockShapes
                    .worldAabb(world, pos.x(), pos.y(), pos.z(), type);
            if (panels == null) panels = new java.util.ArrayList<>(2);
            panels.add(new PanelTarget(box, new Vector3i(pos.x(), pos.y(), pos.z())));
        }
        return panels == null ? List.of() : panels;
    }

    private static boolean insidePanel(float[] b, Vector3f p) {
        return p.x >= b[0] && p.x <= b[3]
                && p.y >= b[1] && p.y <= b[4]
                && p.z >= b[2] && p.z <= b[5];
    }
}
