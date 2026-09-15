package com.stonebreak.util;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #225 start-to-finish: breaking a block with a known breaker position must spawn
 * the drop into the adjacent passable cell nearest the breaker (never the legacy surfacing
 * offset, which landed drops inside the solid block above a broken log) and spit it out
 * toward the breaker with a low upward pop.
 */
class DropUtilTest {

    private static final int BROKEN_X = 8;
    private static final int BROKEN_Y = 64; // The broken log's cell — AIR
    private static final int BROKEN_Z = 8;
    private static final int TRUNK_BASE_Y = 65; // First solid trunk cell above the log

    /**
     * A broken log in the middle of a tree trunk: the broken cell (x=8, z=8) at
     * BROKEN_Y is AIR with solid ground below it, and a solid trunk column rises
     * above it (BROKEN_Y + 1 .. BROKEN_Y + 20). The cells beside the column are air.
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
            if (x == BROKEN_X && z == BROKEN_Z) {
                if (y == BROKEN_Y) {
                    return BlockType.AIR; // The broken log
                }
                if (y > BROKEN_Y && y <= BROKEN_Y + 20) {
                    return BlockType.DIRT; // The trunk column above it
                }
            }
            return y < BROKEN_Y ? BlockType.DIRT : BlockType.AIR;
        });
        return world;
    }

    @Test
    void handleBlockBrokenSpawnsDropInNearestPassableCellToPlayer() {
        World world = trunkWorld();
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);

        // Player far on the +X side of the broken log.
        DropUtil.handleBlockBroken(world,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f),
                BlockType.DIRT, null, 0, new Vector3f(30.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));

        verify(em, atLeastOnce()).addEntity(captor.capture());
        Entity drop = captor.getAllValues().get(0);

        // The +X neighbour is the nearest passable cell to the player — NOT the legacy
        // surfacing offset at BROKEN_Y + 1, which is inside the solid trunk column.
        assertEquals(new Vector3f(BROKEN_X + 1.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), drop.getPosition(),
            "drop must spawn in the open cell nearest the breaker");
        assertEquals(BlockType.DIRT, world.getBlockAt(BROKEN_X, BROKEN_Y + 1, BROKEN_Z),
            "precondition: the cell above the log is solid trunk — the legacy offset landed there");

        // Spit velocity: toward the breaker (+X) with a low upward pop, no big launch.
        Vector3f velocity = drop.getVelocity();
        assertTrue(velocity.x > 0f, "drop must spit toward the breaker (x=" + velocity.x + ")");
        assertTrue(velocity.y > 0f && velocity.y < 1.0f,
            "upward pop must be low, no big random launch (y=" + velocity.y + ")");
    }

    @Test
    void handleBlockBrokenSpawnsDropInBrokenCellWhenPlayerIsAdjacent() {
        World world = trunkWorld();
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);

        // Player standing right on top of the broken log's neighbour cell: the broken
        // cell itself (open) is nearer than any neighbour.
        DropUtil.handleBlockBroken(world,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f),
                BlockType.DIRT, null, 0,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 1.5f, BROKEN_Z + 0.5f));

        verify(em, atLeastOnce()).addEntity(captor.capture());
        Entity drop = captor.getAllValues().get(0);
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), drop.getPosition(),
            "drop must spawn in the broken cell itself when the breaker is adjacent");
    }

    @Test
    void createItemDropWithoutBreakerKeepsLegacyScatter() {
        World world = trunkWorld();
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        when(world.getEntityManager()).thenReturn(em);
        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);

        // No breaker position: non-break callers (mob deaths, inventory spills) keep the
        // legacy scatter + random pop — the drop must NOT be snapped to a cell centre.
        com.stonebreak.items.ItemStack stack =
                new com.stonebreak.items.ItemStack(com.stonebreak.items.ItemType.STICK, 1);
        DropUtil.createItemDrop(world, new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), stack);

        verify(em, atLeastOnce()).addEntity(captor.capture());
        Entity drop = captor.getAllValues().get(0);
        Vector3f position = drop.getPosition();
        // Legacy scatter: within half a block of the caller, one block above it —
        // never exactly at a cell centre on every axis.
        assertTrue(Math.abs(position.x - (BROKEN_X + 0.5f)) < 0.5f
                        && Math.abs(position.z - (BROKEN_Z + 0.5f)) < 0.5f,
            "legacy scatter must offset within half a block (x=" + position.x + ", z=" + position.z + ")");
        Vector3f velocity = drop.getVelocity();
        // Legacy random pop is always >= 1.0 (DROP_VELOCITY_MIN); the break-drop spit-out
        // pop is always <= 0.6 — a big launch distinguishes the scatter path from the
        // resolved one.
        assertTrue(velocity.y >= 1.0f,
            "legacy random pop must be a big launch (y=" + velocity.y + ")");
    }
}
