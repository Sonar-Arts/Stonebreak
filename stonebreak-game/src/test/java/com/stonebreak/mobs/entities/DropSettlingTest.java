package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairShape;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A drop landing on flat ground must come to a COMPLETE rest at the server's 20 Hz tick.
 * Regression for the resting-drop jitter: the ground probe sampled the air block above the
 * surface at the exact rest height, so onGround flipped every other tick and the drop
 * oscillated ~3 cm forever — each cycle broadcast to clients as visible jitter (drops are
 * server-authoritative; EntityManager also no longer double-drives them with the external
 * EntityCollision pass, whose bottom convention disagreed with the drops' center-based one).
 */
class DropSettlingTest {

    private static final float SERVER_TICK = 0.05f; // 20 Hz
    private static final int GROUND_TOP_Y = 64;     // solid at y <= 63, air above
    private static final int TRUNK_BASE_Y = 65;     // first solid trunk cell above the broken log

    /** World with a flat ground plane and this-world entity manager (empty). */
    private static World flatWorld() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int y = inv.getArgument(1);
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    /**
     * A broken log in the middle of a tree trunk: the broken cell (x=8, z=8) is AIR at
     * TRUNK_BASE_Y - 1 with solid ground below it, and a solid trunk column rises above
     * it (TRUNK_BASE_Y .. GROUND_TOP_Y + 20). The trunk neighbours beside the column are
     * air, so the embedded drop escapes sideways, floats beside the trunk, and settles
     * at the trunk base — never surfacing through the column.
     */
    private static World trunkWorld() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 8 && z == 8) {
                if (y == TRUNK_BASE_Y - 1) {
                    return BlockType.AIR; // The broken log
                }
                if (y >= TRUNK_BASE_Y && y < GROUND_TOP_Y + 20) {
                    return BlockType.DIRT; // The trunk column above it
                }
            }
            return y < TRUNK_BASE_Y - 1 ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    /** A 1×1 pillar standing on the ground plane: solid column at x=8, z=8 from y=64 up
     *  to PILLAR_TOP_Y (top block occupies [PILLAR_TOP_Y, PILLAR_TOP_Y + 1)), air beside it. */
    private static final int PILLAR_TOP_Y = 80;

    private static World pillarWorld() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 8 && z == 8 && y >= GROUND_TOP_Y && y <= PILLAR_TOP_Y) {
                return BlockType.DIRT; // The pillar column
            }
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    /** Ground plane (solid at y <= 63) with a single non-collidable cell at (8, 64, 8):
     *  a flower, a placed torch or another passable block, air above it. */
    private static World passableCellWorld(BlockType cellBlock) {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 8 && z == 8 && y == GROUND_TOP_Y) {
                return cellBlock; // The flower / placed torch
            }
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    /** Ground plane with a 1-layer snow block at (8, 64, 8), air above it. */
    private static World snowWorld() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 8 && z == 8 && y == GROUND_TOP_Y) {
                return BlockType.SNOW;
            }
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        when(world.getSnowHeight(anyInt(), anyInt(), anyInt())).thenReturn(0.125f);
        return world;
    }

    /** Ground plane with an oak stair at (8, 64, 8) (default facing), air above it. */
    private static World stairWorld() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 8 && z == 8 && y == GROUND_TOP_Y) {
                return BlockType.OAK_STAIRS;
            }
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    /** Ground plane (solid at y <= 63) with a multi-layer snow column at (9, 64, 9)
     *  of the given layer height sitting on it; air elsewhere. */
    private static World snowStepWorld(float snowHeight) {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (x == 9 && z == 9 && y == GROUND_TOP_Y) {
                return BlockType.SNOW; // The multi-layer snow column, on the plane
            }
            return y < GROUND_TOP_Y ? BlockType.DIRT : BlockType.AIR;
        });
        when(world.getSnowHeight(anyInt(), anyInt(), anyInt())).thenReturn(snowHeight);
        return world;
    }

    /**
     * Run the drop long enough to land and bleed off every bounce (15 s of server
     * ticks), then verify it comes to a COMPLETE rest at the server's 20 Hz tick.
     * Returns the rest Y.
     */
    private static float runToCompleteRest(Entity drop) {
        for (int i = 0; i < 300; i++) {
            drop.update(SERVER_TICK);
        }
        float restY = drop.getPosition().y;
        for (int i = 0; i < 20; i++) {
            drop.update(SERVER_TICK);
            assertEquals(restY, drop.getPosition().y, 1e-6f,
                "resting drop must not move (tick " + i + ")");
        }
        assertTrue(drop.isOnGround(), "settled drop must stay grounded");
        return restY;
    }

    private static void assertSettles(Entity drop) {
        // Plenty of time to land and bleed off every bounce (15 s of server ticks).
        for (int i = 0; i < 300; i++) {
            drop.update(SERVER_TICK);
        }
        float restY = drop.getPosition().y;
        for (int i = 0; i < 20; i++) {
            drop.update(SERVER_TICK);
            assertEquals(restY, drop.getPosition().y, 1e-6f,
                "resting drop must not move (tick " + i + ")");
        }
        assertTrue(drop.isOnGround(), "settled drop must stay grounded");
        assertEquals(GROUND_TOP_Y + drop.getHeight() / 2f, restY, 1e-3f,
            "drop must rest with its bottom on the ground surface");
    }
    @Test
    void blockDropSettlesWithoutOscillating() {
        World world = flatWorld();
        assertSettles(BlockDrop.createDrop(world, new Vector3f(8.5f, 70f, 8.5f), BlockType.DIRT));
    }

    @Test
    void itemDropSettlesWithoutOscillating() {
        World world = flatWorld();
        assertSettles(new ItemDrop(world, new Vector3f(8.5f, 70f, 8.5f), ItemType.STICK, 1));
    }

    /**
     * Issue #225 regression: a log broken in the middle of a tree trunk must not carry
     * its drop up through the trunk. The drop spawns in the broken cell (air) directly
     * below a solid trunk column; rising into a trunk cell used to sample that solid
     * cell as "ground" and snap the drop ON TOP of it, climbing trunk cell by trunk cell
     * until reaching air above the canopy. The drop must escape back down and settle at
     * the trunk base instead.
     */
    @Test
    void dropRisingIntoTrunkDoesNotSurfaceThroughIt() {
        World world = trunkWorld();
        // The drop starts in the BROKEN AIR CELL (TRUNK_BASE_Y - 1, the broken log's cell),
        // directly below the solid trunk column — exactly where a break drop spawns.
        for (Entity drop : List.of(
                BlockDrop.createDrop(world, new Vector3f(8.5f, TRUNK_BASE_Y - 0.5f, 8.5f), BlockType.DIRT),
                new ItemDrop(world, new Vector3f(8.5f, TRUNK_BASE_Y - 0.5f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 3f, 0f)); // Straight up into the trunk column
            for (int i = 0; i < 300; i++) {
                drop.update(SERVER_TICK);
            }
            assertTrue(drop.getPosition().y < TRUNK_BASE_Y + 1f,
                "drop must not surface above the trunk base (y=" + drop.getPosition().y + ")");
            assertTrue(drop.isOnGround(), "drop must settle at the trunk base");
        }
    }

    /**
     * Regression for the follow-up physics break: a drop falling FAST onto a narrow block
     * top enters the block's cell in one tick (a 20 Hz tick moves a fast drop further than
     * half its 0.25 height), and its centre ends up inside the block it landed on. That is
     * a normal landing — the drop must come to rest ON TOP of the pillar, not be pushed
     * off it (the first cut of issue #225 pushed drops off pillars and stumps onto the
     * ground beside them).
     */
    @Test
    void blockDropLandsOnNarrowPillarTop() {
        World world = pillarWorld();
        // Natural straight drop from above the pillar top (no horizontal drift).
        Entity natural = BlockDrop.createDropWithVelocity(
                world, new Vector3f(8.5f, 85f, 8.5f), BlockType.DIRT, new Vector3f(0f, 0f, 0f));
        // Deterministic fast throw: pre-move centre is above the pillar's top face but the
        // post-move centre ends up INSIDE the top block's cell in one tick (movement 0.28 >
        // half the 0.25 height) — the first cut of issue #225 saw that as "stuck in a block"
        // and pushed the drop off onto the ground beside the pillar.
        Entity thrown = BlockDrop.createDropWithVelocity(
                world, new Vector3f(8.5f, 81.05f, 8.5f), BlockType.DIRT, new Vector3f(0f, -5f, 0f));
        for (Entity drop : List.of(natural, thrown)) {
            for (int i = 0; i < 300; i++) {
                drop.update(SERVER_TICK);
            }
            float restY = drop.getPosition().y;
            for (int i = 0; i < 20; i++) {
                drop.update(SERVER_TICK);
                assertEquals(restY, drop.getPosition().y, 1e-6f,
                    "resting drop must not move (tick " + i + ")");
            }
            assertTrue(drop.isOnGround(), "drop must stay grounded on the pillar top");
            assertEquals(PILLAR_TOP_Y + 1 + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must rest with its bottom on the pillar's top surface");
            assertEquals(8.5f, drop.getPosition().x, 1e-3f, "drop must not be pushed off the pillar (x)");
            assertEquals(8.5f, drop.getPosition().z, 1e-3f, "drop must not be pushed off the pillar (z)");
        }
    }

    /**
     * Issue #265 core repro: a drop resolved into a flower cell (the #225 spawn rule
     * allows flowers) used to be snapped by the ground probe on the next tick to rest
     * ON TOP OF the flower cell — the probe sampled the flower as full-height ground —
     * floating a full block above the real ground. Flowers are passable AND never act
     * as ground: the drop falls through to the block below and rests inside the flower
     * cell with its bottom on the ground surface.
     */
    @Test
    void dropResolvedIntoFlowerCellFallsThroughToTheGroundBelow() {
        World world = passableCellWorld(BlockType.ROSE);
        for (Entity drop : List.of(
                BlockDrop.createDropWithVelocity(
                        world, new Vector3f(8.5f, 64.5f, 8.5f), BlockType.DIRT, new Vector3f(0f, 0f, 0f)),
                new ItemDrop(world, new Vector3f(8.5f, 64.5f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 0f, 0f));
            float restY = runToCompleteRest(drop);
            assertEquals(GROUND_TOP_Y + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must rest INSIDE the flower cell on the ground below (restY=" + restY + ")");
        }
    }

    /**
     * Predates #225: drops landing on any non-solid, non-water block rest one block up
     * in the air. A placed torch is passable AND never ground: the drop falls through
     * the torch cell to the block below.
     */
    @Test
    void dropFallsThroughPlacedTorchCell() {
        World world = passableCellWorld(BlockType.TORCH_PLACED);
        for (Entity drop : List.of(
                BlockDrop.createDropWithVelocity(
                        world, new Vector3f(8.5f, 64.5f, 8.5f), BlockType.DIRT, new Vector3f(0f, 0f, 0f)),
                new ItemDrop(world, new Vector3f(8.5f, 64.5f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 0f, 0f));
            float restY = runToCompleteRest(drop);
            assertEquals(GROUND_TOP_Y + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must fall through the torch cell and rest on the ground below (restY=" + restY + ")");
        }
    }

    /**
     * Issue #265: resting height follows BlockShape.collisionHeight — a drop thrown
     * onto a 1-layer snow block rests ON the snow surface (1/8 of a block), not at
     * full-block height. The old ground rule counted the snow cell as full-height
     * ground and rested the drop a full block up.
     */
    @Test
    void dropRestsOnTheSnowSurfaceNotAtFullBlockHeight() {
        World world = snowWorld();
        for (Entity drop : List.of(
                BlockDrop.createDropWithVelocity(
                        world, new Vector3f(8.5f, 65.5f, 8.5f), BlockType.DIRT, new Vector3f(0f, 0f, 0f)),
                new ItemDrop(world, new Vector3f(8.5f, 65.5f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 0f, 0f));
            float restY = runToCompleteRest(drop);
            assertEquals(GROUND_TOP_Y + 0.125f + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must rest ON the 1-layer snow surface (restY=" + restY + ")");
        }
    }

    /**
     * Issue #265: a drop landing on a stair rests on the tallest step its footprint
     * overlaps — the one BlockShape rule — with its bottom on that surface, grounded
     * and without oscillating. The rest height must follow the shape's answer, not a
     * hardcoded full block.
     */
    @Test
    void dropLandingOnStairRestsOnTheTallestStepItsFootprintOverlaps() {
        World world = stairWorld();
        for (Entity drop : List.of(
                BlockDrop.createDropWithVelocity(
                        world, new Vector3f(8.5f, 65.5f, 8.5f), BlockType.DIRT, new Vector3f(0f, 0f, 0f)),
                new ItemDrop(world, new Vector3f(8.5f, 65.5f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 0f, 0f));
            float restY = runToCompleteRest(drop);
            float step = StairShape.stepHeight(world, 8, GROUND_TOP_Y, 8, BlockType.OAK_STAIRS,
                    8.375f, 8.375f, 8.625f, 8.625f);
            assertTrue(step > 0f, "the stair must leave a solid step under the drop's footprint");
            assertEquals(GROUND_TOP_Y + step + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must rest on the tallest step its footprint overlaps (restY=" + restY + ")");
        }
    }

    /**
     * Measured regression for the sideways step-up (issue #265 review): a drop resting
     * on a full block (centre at cellY + 0.125) that slides into a multi-layer snow
     * column taller than that used to be teleported backwards to the previous cell's
     * centre with velocity zeroed — snow was eligible for the #225 sideways-escape once
     * it became eligible for the embedded path. A partial-height cell entered from the
     * side is a step-up, not an embedment: the drop climbs onto the snow and rides
     * over, never teleporting back.
     */
    @Test
    void dropSlidingIntoMultiLayerSnowStepsUpInsteadOfTeleportingBack() {
        for (float snowHeight : new float[]{0.125f, 0.375f, 0.750f}) {
            World world = snowStepWorld(snowHeight);
            Entity drop = new ItemDrop(world, new Vector3f(8.9f, 64.125f, 9.5f), ItemType.STICK, 1);
            drop.setVelocity(new Vector3f(3f, 0f, 0f));
            // The drop must actually STEP onto the snow: at some tick its centre is inside
            // the snow cell at exactly the snow-surface rest height. The escape path (the
            // bug reinstated) teleports it to the previous cell's centre — never at the
            // surface inside the snow cell — so the x condition is what rules that out.
            float snowSurfaceRestY = GROUND_TOP_Y + snowHeight + drop.getHeight() / 2f;
            boolean steppedOntoSnow = false;
            for (int i = 0; i < 300 && !steppedOntoSnow; i++) {
                drop.update(SERVER_TICK);
                steppedOntoSnow = drop.getPosition().x >= 9.0f
                        && Math.abs(drop.getPosition().y - snowSurfaceRestY) < 1e-4f;
            }
            assertTrue(steppedOntoSnow, "drop must step onto the snow surface inside the snow cell (y="
                + drop.getPosition().y + ")");
            float restY = runToCompleteRest(drop);
            assertTrue(drop.getPosition().x > 9.0f,
                "drop must cross into/past the snow cell, not teleport back to x=8.5 (x="
                    + drop.getPosition().x + ")");
            assertTrue(drop.isOnGround(), "sliding drop must stay grounded");
            assertEquals(GROUND_TOP_Y + drop.getHeight() / 2f, restY, 1e-3f,
                "drop must end resting on the plane beside the snow (restY=" + restY + ")");
        }
    }

}
