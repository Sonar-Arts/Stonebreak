package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockShape;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.DropSpawnResolver;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * The one world-collision pass for item and block drops (issue #265) — the landing
 * half of the drop collision rule that {@link DropSpawnResolver} spawns and escapes
 * with. Both drops' ground probes used to answer "is this cell solid?" with their own
 * copy-pasted rule, so a drop could spawn into a cell that the next tick treated as
 * ground and snapped on top of.
 *
 * <p>One solidity/height rule for spawning, escaping and landing, backed by
 * {@link BlockShape#collisionHeight} over the drop's XZ footprint: a cell collides
 * exactly where its collision height is above zero. Non-collidable cells (flowers,
 * wildgrass, placed torches, water) are passable <b>and</b> never act as ground — the
 * drop falls through them to the block below. Snow answers its layer height, stairs
 * the tallest step the footprint overlaps, everything else collidable a full block.
 * Door cells answer solid (the {@link BlockShape} default): drops never resolve
 * against the posed model AABB, so "solid" is the safe drops policy.
 *
 * <p>This is a pure resolver: it never mutates its inputs. The caller applies the
 * outcome — the rest snap, the escape, the bounce and gravity gating.
 */
public final class DropWorldCollision {

    private DropWorldCollision() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Downward bias on the ground probe so a drop resting exactly on a block surface
     *  still samples the supporting cell — the cell whose collision surface equals the
     *  rest height. Must stay below the smallest collision height any shape can answer
     *  (snow layers answer 1/8 of a block). */
    private static final float GROUND_PROBE_BIAS = 0.01f;

    /** What happened to the drop during one pass. */
    public enum Outcome {
        /** Came to rest on (or bounced off) a collidable surface — {@code restCentreY} is the new centre Y. */
        GROUNDED,
        /** Was embedded in a collidable cell and escaped to a passable cell — {@code escapeCentre} is the cell centre. */
        ESCAPED,
        /** Nothing collidable under (or around) the drop — airborne. A fully-enclosed
         *  drop keeps its velocity and retries next tick. */
        AIRBORNE
    }

    /** One pass's resolution: the outcome, the rest centre Y (GROUNDED only), the escape
     *  cell centre (ESCAPED only) and the in-water state at the drop's final position. */
    public record Result(Outcome outcome, float restCentreY, Vector3f escapeCentre, boolean inWater) {
    }

    /**
     * One collision pass for a drop whose centre is {@code position} (already moved this
     * tick) that was at {@code oldPosition} before the move. The drop's volume is the
     * centre-based cube from {@code EntityType}: a {@code dropWidth}/{@code dropLength}
     * XZ footprint (which decides the stair step it rests on) and a {@code dropHeight}
     * half-height ground probe.
     */
    public static Result resolveGround(World world, Vector3f position, Vector3f oldPosition,
                                       float dropWidth, float dropLength, float dropHeight) {
        if (world == null) {
            return new Result(Outcome.AIRBORNE, position.y, null, false);
        }
        int blockX = (int) Math.floor(position.x);
        int blockZ = (int) Math.floor(position.z);

        // Embedded check FIRST (issue #225): when the drop's own cell is collidable (e.g.
        // the drop rose into a tree trunk or was pushed sideways into a log), the ground
        // probe below would sample that cell as "ground" and snap the drop ON TOP of it.
        // One solidity/height rule (issue #265): the supporting surface of that cell under
        // the drop's XZ footprint decides. A partial-height surface entered from ANY
        // direction is a step-up, not an embedment — snow layers and stair treads: the
        // drop climbs onto the surface instead of being teleported backwards to the
        // previous cell (a multi-layer snow column taller than the drop's rest height used
        // to be eligible for the #225 sideways-escape and snapped it back). A full-height
        // surface keeps the #225 policy: entering through its TOP face this tick (a fast
        // fall onto a narrow block — one 20 Hz tick moves a fast drop further than half
        // the drop's height) is a NORMAL landing — come to rest on the surface like any
        // other landing instead of being pushed off sideways; entering through a side or
        // the bottom face escapes out the nearest open side — the escape also zeroes
        // horizontal speed (keeping it made the drop bounce off the wall and back).
        int cellY = (int) Math.floor(position.y);
        if (DropSpawnResolver.isEmbedded(world, blockX, cellY, blockZ)) {
            float surface = surfaceHeight(world, blockX, cellY, blockZ,
                    position.x, position.z, dropWidth, dropLength);
            if (surface > 0f && (oldPosition.y >= cellY + surface || surface < 1.0f)) {
                return grounded(world, position, cellY + surface + dropHeight / 2f);
            }
            Vector3f escape = DropSpawnResolver.resolveEscape(world, blockX, cellY, blockZ, oldPosition);
            if (escape != null) {
                return new Result(Outcome.ESCAPED, position.y, escape,
                        inWaterAt(world, escape.x, escape.y, escape.z));
            }
            return airborne(world, position);
        }

        // Ground probe, biased down a hair: the rest snap below puts the drop's bottom at
        // exactly the supporting surface, where an unbiased floor(bottom) sampled the open
        // cell above it — onGround flipped false every other tick and the drop oscillated
        // ~3 cm forever at the server's 20 Hz (broadcast to clients as visible jitter).
        // The supporting cell is the one whose collision surface equals the rest height:
        // for a full block that is the cell below, for a snow layer or a stair the cell
        // the drop's bottom rests in.
        int probeY = (int) Math.floor(position.y - dropHeight / 2 - GROUND_PROBE_BIAS);
        float surface = surfaceHeight(world, blockX, probeY, blockZ,
                position.x, position.z, dropWidth, dropLength);
        if (surface > 0f) {
            return grounded(world, position, probeY + surface + dropHeight / 2f);
        }
        return airborne(world, position);
    }

    private static Result grounded(World world, Vector3f position, float restCentreY) {
        // The in-water state at the rest position: a drop resting on a floor under shallow
        // water has its centre in the WATER cell — the flag gates gravity in the drop's own
        // physics and gates WaterFlowPhysics on block drops, so it must reflect the snapped
        // position, not the pre-snap one.
        return new Result(Outcome.GROUNDED, restCentreY, null,
                inWaterAt(world, position.x, restCentreY, position.z));
    }

    private static Result airborne(World world, Vector3f position) {
        return new Result(Outcome.AIRBORNE, position.y, null,
                inWaterAt(world, position.x, position.y, position.z));
    }

    /**
     * Collision surface under the drop's XZ footprint, in blocks above the cell floor —
     * the one {@link BlockShape} rule backing spawning, escaping and landing (issue
     * #265): flowers, wildgrass, water and placed torches answer 0 (passable and never
     * ground), snow its layer height, stairs the tallest step the footprint overlaps,
     * else a full block or nothing.
     */
    private static float surfaceHeight(World world, int x, int y, int z,
                                       float dropCentreX, float dropCentreZ,
                                       float dropWidth, float dropLength) {
        float halfW = dropWidth / 2f;
        float halfL = dropLength / 2f;
        return BlockShape.collisionHeight(world, x, y, z,
                dropCentreX - halfW, dropCentreZ - halfL,
                dropCentreX + halfW, dropCentreZ + halfL);
    }

    /** In-water check at explicit final coordinates (drops skip the external
     *  {@link EntityCollision} physics pass, so this must be self-managed — it gates
     *  gravity in the drop's own physics and WaterFlowPhysics on block drops). */
    private static boolean inWaterAt(World world, float x, float y, float z) {
        return world.getBlockAt(
                (int) Math.floor(x),
                (int) Math.floor(y),
                (int) Math.floor(z)) == BlockType.WATER;
    }
}
