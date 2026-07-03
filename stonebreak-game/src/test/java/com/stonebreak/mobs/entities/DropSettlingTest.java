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
}
