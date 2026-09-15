package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
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
        for (Entity drop : List.of(
                BlockDrop.createDrop(world, new Vector3f(8.5f, TRUNK_BASE_Y + 0.3f, 8.5f), BlockType.DIRT),
                new ItemDrop(world, new Vector3f(8.5f, TRUNK_BASE_Y + 0.3f, 8.5f), ItemType.STICK, 1))) {
            drop.setVelocity(new Vector3f(0f, 3f, 0f)); // Straight up into the trunk column
            for (int i = 0; i < 300; i++) {
                drop.update(SERVER_TICK);
            }
            assertTrue(drop.getPosition().y < TRUNK_BASE_Y + 1f,
                "drop must not surface above the trunk base (y=" + drop.getPosition().y + ")");
            assertTrue(drop.isOnGround(), "drop must settle at the trunk base");
        }
    }
}
