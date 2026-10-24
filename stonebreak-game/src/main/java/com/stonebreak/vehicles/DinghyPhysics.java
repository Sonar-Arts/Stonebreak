package com.stonebreak.vehicles;

import com.stonebreak.blocks.BlockShape;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;
import com.stonebreak.blocks.waterSystem.WaterSubmersion;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * One tick of dinghy motion, shared by the server (an undriven or mob-crewed boat) and the
 * rowing client (its own boat, predicted). The oars are the only engine: thrust is their mean
 * pull, yaw their difference ({@link OarInput}). Afloat, a spring holds the hull at
 * {@link #WATERLINE}; aground, gravity and heavy friction leave only a slow crawl.
 *
 * <p>Block collision uses a square footprint (the hull's beam) so it does not change with the
 * boat's heading — the bow may overhang a bank slightly, but the boat never wedges on turning.
 */
public final class DinghyPhysics {

    /** Model height (above the entity position) that sits at the water surface. Just under the floorboards' top. */
    static final float WATERLINE = 0.1f;
    /** Forward acceleration at full pull, blocks/s². With {@link #WATER_DRAG} it tops out near 5 b/s. */
    static final float ACCEL = 6.5f;
    static final float WATER_DRAG = 1.3f;
    static final float LAND_DRAG = 6.0f;
    /** Thrust and turn rate on land, as a share of afloat. */
    static final float LAND_FACTOR = 0.15f;
    /** Turn rate at full differential pull, degrees per second. */
    static final float TURN_RATE = 75f;
    static final float BUOY_SPRING = 35f;
    static final float BUOY_DAMPING = 7f;
    /** A boat out of the water falls at half a body's gravity — it is light and broad. */
    static final float GRAVITY = -20f;
    static final float HALF_BEAM = 0.7f;
    static final float HULL_HEIGHT = 0.5f;
    private static final float EPS = 1.0e-3f;

    private DinghyPhysics() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void step(World world, Dinghy boat, OarInput oars, float dt) {
        if (world == null || dt <= 0f) {
            return;
        }
        Vector3f pos = boat.getPosition();
        Vector3f vel = boat.getVelocity();

        float surface = waterSurfaceY(world, pos.x, pos.y, pos.z);
        boolean afloat = !Float.isNaN(surface) && surface - WATERLINE > pos.y - 0.5f;
        boat.setSubmersion(afloat ? 1f : 0f);
        float authority = afloat ? 1f : (boat.isOnGround() ? LAND_FACTOR : 0f);

        float yaw = boat.getYaw() - oars.turnRight() * TURN_RATE * authority * dt;
        boat.setYaw(yaw);
        double yawRad = Math.toRadians(yaw);
        float fx = (float) -Math.sin(yawRad);
        float fz = (float) -Math.cos(yawRad);
        float push = oars.thrust() * ACCEL * authority * dt;
        vel.x += fx * push;
        vel.z += fz * push;

        float drag = (float) Math.exp(-(afloat ? WATER_DRAG : LAND_DRAG) * dt);
        vel.x *= drag;
        vel.z *= drag;

        if (afloat) {
            float target = surface - WATERLINE;
            vel.y += ((target - pos.y) * BUOY_SPRING - vel.y * BUOY_DAMPING) * dt;
            WaterFlowPhysics.applyWaterFlowForce(world, pos, vel, dt, HALF_BEAM * 2f, HULL_HEIGHT);
        } else {
            vel.y += GRAVITY * dt;
        }

        boat.setOnGround(false);
        moveX(world, pos, vel, dt);
        moveZ(world, pos, vel, dt);
        moveY(world, boat, pos, vel, dt);

        boat.setPosition(pos);
        boat.setVelocity(vel);
    }

    /**
     * Y of the water surface in the column at (x, z) near a hull at {@code y}, or NaN when there
     * is no water there. A hull dragged fully under rises toward a surface up to two blocks up.
     */
    static float waterSurfaceY(World world, float x, float y, float z) {
        float feet = y - 1f;
        float span = 3f;
        float fraction = WaterSubmersion.fractionAt(world, x, feet, z, span);
        if (fraction <= 0f) {
            return Float.NaN;
        }
        return feet + fraction * span;
    }

    private static void moveX(World world, Vector3f pos, Vector3f vel, float dt) {
        float nx = pos.x + vel.x * dt;
        if (blocked(world, nx, pos.y, pos.z)) {
            vel.x = 0f;
        } else {
            pos.x = nx;
        }
    }

    private static void moveZ(World world, Vector3f pos, Vector3f vel, float dt) {
        float nz = pos.z + vel.z * dt;
        if (blocked(world, pos.x, pos.y, nz)) {
            vel.z = 0f;
        } else {
            pos.z = nz;
        }
    }

    private static void moveY(World world, Dinghy boat, Vector3f pos, Vector3f vel, float dt) {
        float ny = pos.y + vel.y * dt;
        if (vel.y < 0f) {
            float floor = floorTop(world, pos.x, ny, pos.y, pos.z);
            if (!Float.isNaN(floor)) {
                pos.y = floor + EPS;
                vel.y = 0f;
                boat.setOnGround(true);
                return;
            }
        } else if (vel.y > 0f && blocked(world, pos.x, ny, pos.z)) {
            vel.y = 0f;
            return;
        }
        pos.y = ny;
    }

    /** True when a hull footprint at (x, y, z) overlaps solid collision. */
    static boolean blocked(World world, float x, float y, float z) {
        float minX = x - HALF_BEAM, maxX = x + HALF_BEAM;
        float minZ = z - HALF_BEAM, maxZ = z + HALF_BEAM;
        float minY = y + EPS, maxY = y + HULL_HEIGHT;
        for (int bx = (int) Math.floor(minX); bx <= (int) Math.floor(maxX - EPS); bx++) {
            for (int bz = (int) Math.floor(minZ); bz <= (int) Math.floor(maxZ - EPS); bz++) {
                for (int by = (int) Math.floor(minY); by <= (int) Math.floor(maxY); by++) {
                    float h = BlockShape.collisionHeight(world, bx, by, bz, minX, minZ, maxX, maxZ);
                    if (h > 0f && minY < by + h && maxY > by) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Highest solid top in [newY, oldY] under the footprint, or NaN when the drop is clear. */
    private static float floorTop(World world, float x, float newY, float oldY, float z) {
        float minX = x - HALF_BEAM, maxX = x + HALF_BEAM;
        float minZ = z - HALF_BEAM, maxZ = z + HALF_BEAM;
        float best = Float.NaN;
        for (int bx = (int) Math.floor(minX); bx <= (int) Math.floor(maxX - EPS); bx++) {
            for (int bz = (int) Math.floor(minZ); bz <= (int) Math.floor(maxZ - EPS); bz++) {
                for (int by = (int) Math.floor(newY); by <= (int) Math.floor(oldY); by++) {
                    float h = BlockShape.collisionHeight(world, bx, by, bz, minX, minZ, maxX, maxZ);
                    float top = by + h;
                    if (h > 0f && top >= newY && top <= oldY + EPS && (Float.isNaN(best) || top > best)) {
                        best = top;
                    }
                }
            }
        }
        return best;
    }
}
