package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drop visual compression must operate on the drop's OWN world's entity manager and must
 * never absorb a network shadow. Regression for the two-world bug where an authoritative
 * server drop scanned the Game singleton's (client render world) manager, found its own
 * replicated shadow within compression range, and marked it compressed — hiding every
 * fresh single drop from the host's renderer.
 */
class DropCompressionTest {

    private static final float TICK = 0.05f;

    private static World worldWithManager(EntityManager em) {
        World world = mock(World.class);
        when(world.getEntityManager()).thenReturn(em);
        return world;
    }

    @Test
    void blockDropCompressesNearbySameTypeDrop() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        BlockDrop a = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        BlockDrop b = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        when(em.getAllEntities()).thenReturn(List.of(a, b));

        a.update(TICK);

        assertFalse(b.shouldRender(), "nearby same-type drop should compress into the survivor");
        assertTrue(a.shouldRender());
        assertEquals(2, a.getStackCount());
    }

    @Test
    void blockDropNeverCompressesANetworkShadow() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        BlockDrop authoritative = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        BlockDrop shadow = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        shadow.setNetworkShadow(true);
        when(em.getAllEntities()).thenReturn(List.of(authoritative, shadow));

        authoritative.update(TICK);

        assertTrue(shadow.shouldRender(), "a network shadow must never be absorbed/hidden");
        assertEquals(1, authoritative.getStackCount());
    }

    @Test
    void shadowDropsAdvanceTheirVisualClock() {
        // Shadows skip update(), but DropRenderer's bob/spin derive from getAge() —
        // updateClientVisuals (called by EntityManager for shadows) must advance it.
        World world = worldWithManager(mock(EntityManager.class));
        BlockDrop blockShadow = BlockDrop.createDrop(world, new Vector3f(), BlockType.DIRT);
        blockShadow.setNetworkShadow(true);
        blockShadow.updateClientVisuals(TICK);
        assertEquals(TICK, blockShadow.getAge(), 1e-6f);

        ItemDrop itemShadow = new ItemDrop(world, new Vector3f(), ItemType.STICK, 1);
        itemShadow.setNetworkShadow(true);
        itemShadow.updateClientVisuals(TICK);
        assertEquals(TICK, itemShadow.getAge(), 1e-6f);
    }

    @Test
    void itemDropNeverCompressesANetworkShadow() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        ItemDrop authoritative = new ItemDrop(world, pos, ItemType.STICK, 1);
        ItemDrop shadow = new ItemDrop(world, pos, ItemType.STICK, 1);
        shadow.setNetworkShadow(true);
        when(em.getAllEntities()).thenReturn(List.of(authoritative, shadow));

        authoritative.update(TICK);

        assertTrue(shadow.shouldRender(), "a network shadow must never be absorbed/hidden");
        assertEquals(1, authoritative.getStackCount());
    }
}
