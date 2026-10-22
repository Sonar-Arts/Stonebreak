package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
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
 * Drop merging must operate on the drop's OWN world's entity manager and must never absorb a
 * network shadow. Regression for the two-world bug where an authoritative server drop scanned
 * the Game singleton's (client render world) manager, found its own replicated shadow within
 * range, and hid it — hiding every fresh single drop from the host's renderer.
 *
 * <p>Merging is real (#372): the absorbed drop's count moves into the survivor and the absorbed
 * drop dies, so no item is held by two live drops (the old visual compression counted a hidden
 * sibling that stayed collectable — a pickup on that tick took the item twice).
 */
class DropCompressionTest {

    private static final float TICK = 0.05f;

    private static World worldWithManager(EntityManager em) {
        World world = mock(World.class);
        when(world.getEntityManager()).thenReturn(em);
        return world;
    }

    @Test
    void blockDropAbsorbsNearbySameTypeDrop() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        BlockDrop a = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        BlockDrop b = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        b.setStackCount(3);
        when(em.getAllEntities()).thenReturn(List.of(a, b));

        a.update(TICK);
        b.update(TICK); // the absorbed drop must not merge back or count itself again

        assertFalse(b.isAlive(), "the absorbed drop is removed, so it cannot be picked up too");
        assertTrue(a.isAlive());
        assertEquals(4, a.getStackCount(), "the survivor holds both counts");
    }

    @Test
    void blockDropMergeStaysAtMaxStackSize() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        BlockDrop a = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        BlockDrop b = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        a.setStackCount(BlockType.DIRT.getMaxStackSize());
        when(em.getAllEntities()).thenReturn(List.of(a, b));

        a.update(TICK);

        assertTrue(b.isAlive(), "a full drop absorbs nothing");
        assertEquals(BlockType.DIRT.getMaxStackSize(), a.getStackCount());
    }

    @Test
    void blockDropsOfDifferentTypesDoNotMerge() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        BlockDrop dirt = BlockDrop.createDrop(world, pos, BlockType.DIRT);
        BlockDrop stone = BlockDrop.createDrop(world, pos, BlockType.STONE);
        when(em.getAllEntities()).thenReturn(List.of(dirt, stone));

        dirt.update(TICK);

        assertTrue(stone.isAlive());
        assertEquals(1, dirt.getStackCount());
    }

    @Test
    void itemDropAbsorbsOnlyTheSameItem() {
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        ItemDrop a = new ItemDrop(world, pos, ItemType.STICK, 2);
        ItemDrop b = new ItemDrop(world, pos, ItemType.STICK, 5);
        ItemDrop banana = new ItemDrop(world, pos, ItemType.BANANA, 1);
        when(em.getAllEntities()).thenReturn(List.of(a, b, banana));

        a.update(TICK);

        assertFalse(b.isAlive());
        assertTrue(banana.isAlive(), "a different item never merges");
        assertEquals(7, a.getStackCount());
    }

    @Test
    void itemDropsCarryingDifferentBlocksDoNotMerge() {
        // Block stacks have no ItemType; the old getItemType() comparison saw null == null.
        EntityManager em = mock(EntityManager.class);
        World world = worldWithManager(em);
        Vector3f pos = new Vector3f(8f, 64f, 8f);
        ItemDrop dirt = new ItemDrop(world, pos, new ItemStack(BlockType.DIRT, 1));
        ItemDrop stone = new ItemDrop(world, pos, new ItemStack(BlockType.STONE, 1));
        when(em.getAllEntities()).thenReturn(List.of(dirt, stone));

        dirt.update(TICK);

        assertTrue(stone.isAlive());
        assertEquals(1, dirt.getStackCount());
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

        assertTrue(shadow.isAlive(), "a network shadow must never be absorbed/hidden");
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

        assertTrue(shadow.isAlive(), "a network shadow must never be absorbed/hidden");
        assertEquals(1, authoritative.getStackCount());
    }
}
