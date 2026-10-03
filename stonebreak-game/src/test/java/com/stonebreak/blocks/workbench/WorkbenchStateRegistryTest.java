package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #307 end to end on the registry: items put in a crafting table persist in the chunk
 * (so they survive save + reload), each table has its own grid, and breaking a table drops
 * its grid instead of deleting it.
 */
class WorkbenchStateRegistryTest {

    private static final int Y = 20;

    private TestWorld world;
    private Chunk chunk;
    private WorkbenchStateRegistry registry;
    private final List<String> echoes = new ArrayList<>();

    @BeforeEach
    void freshWorld() {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        chunk = new Chunk(0, 0);
        world.setChunk(0, 0, chunk);
        registry = new WorkbenchStateRegistry();
        registry.setStateChangeListener((pos, state) -> echoes.add(state));
    }

    private static String grid(ItemStack... firstSlots) {
        WorkbenchState s = new WorkbenchState(new BlockPos(0, 0, 0));
        for (int i = 0; i < firstSlots.length; i++) {
            s.getSlots()[i] = firstSlots[i];
        }
        return s.encodeSlots();
    }

    @Test
    void editsArePersistedIntoTheChunkAndEchoed() {
        BlockPos pos = new BlockPos(3, Y, 5);
        registry.applySlots(world, pos, grid(new ItemStack(BlockType.DIRT, 4)));

        String stored = chunk.getBlockState(3, Y, 5);
        assertTrue(WorkbenchState.isWorkbenchState(stored), "the grid is in the chunk's saved states");
        assertEquals(List.of(stored), echoes);

        // Re-applying the same grid is not a change: nothing to broadcast.
        registry.applySlots(world, pos, grid(new ItemStack(BlockType.DIRT, 4)));
        assertEquals(1, echoes.size());
    }

    @Test
    void theGridSurvivesAChunkUnloadAndReload() {
        BlockPos pos = new BlockPos(3, Y, 5);
        registry.applySlots(world, pos, grid(new ItemStack(ItemType.STICK, 6)));

        registry.onChunkUnloaded(chunk);
        assertNull(registry.get(pos), "unload releases the in-memory entry");

        // A new session: a fresh registry hydrates from the (saved) chunk states.
        WorkbenchStateRegistry reloaded = new WorkbenchStateRegistry();
        reloaded.onChunkLoaded(chunk);
        ItemStack slot0 = reloaded.get(pos).getSlots()[0];
        assertSame(ItemType.STICK, slot0.getItem());
        assertEquals(6, slot0.getCount());
    }

    @Test
    void eachTableHasItsOwnGrid() {
        BlockPos a = new BlockPos(1, Y, 1);
        BlockPos b = new BlockPos(9, Y, 9);
        registry.applySlots(world, a, grid(new ItemStack(BlockType.DIRT, 2)));

        assertTrue(registry.getOrCreate(b).isEmpty(), "table B must not show table A's items");
        assertNotSame(registry.getOrCreate(a), registry.getOrCreate(b));
    }

    @Test
    void aReStreamedChunkUpdatesTheBoundGridInPlace() {
        BlockPos pos = new BlockPos(3, Y, 5);
        ItemStack[] bound = registry.getOrCreate(pos).getSlots();

        WorkbenchState server = new WorkbenchState(pos);
        server.getSlots()[1] = new ItemStack(BlockType.DIRT, 3);
        chunk.setBlockState(3, Y, 5, server.toStateString());
        registry.onChunkLoaded(chunk);

        assertSame(bound, registry.get(pos).getSlots());
        assertEquals(3, bound[1].getCount());
    }

    @Test
    void placingATableResetsAStaleGridAndEchoesIt() {
        BlockPos pos = new BlockPos(3, Y, 5);
        WorkbenchState stale = registry.getOrCreate(pos);
        stale.getSlots()[0] = new ItemStack(BlockType.DIRT, 9);

        registry.onBlockPlaced(world, 3, Y, 5);

        assertSame(stale, registry.get(pos), "identity kept for a UI that may be bound to it");
        assertTrue(stale.isEmpty());
        assertEquals(1, echoes.size());
    }

    @Test
    void breakingATableDropsItsGrid() {
        EntityManager em = mock(EntityManager.class);
        when(em.getAllEntities()).thenReturn(List.of());
        World dropWorld = mock(World.class);
        when(dropWorld.getEntityManager()).thenReturn(em);
        when(dropWorld.getBlockAt(anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> (int) inv.getArgument(1) < Y ? BlockType.DIRT : BlockType.AIR);

        BlockPos pos = new BlockPos(3, Y, 5);
        WorkbenchState s = registry.getOrCreate(pos);
        s.getSlots()[0] = new ItemStack(BlockType.DIRT, 4);
        s.getSlots()[7] = new ItemStack(ItemType.STICK, 2);

        registry.onBlockBroken(dropWorld, 3, Y, 5, new Vector3f(6f, Y, 5f));

        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);
        verify(em, atLeastOnce()).addEntity(captor.capture());
        int dirt = 0;
        int sticks = 0;
        for (Entity e : captor.getAllValues()) {
            ItemStack dropped = ((ItemDrop) e).getItemStack();
            if (dropped.getItem() == BlockType.DIRT) dirt += dropped.getCount();
            if (dropped.getItem() == ItemType.STICK) sticks += dropped.getCount();
        }
        assertEquals(4, dirt);
        assertEquals(2, sticks);
        assertNull(registry.get(pos), "a broken table forgets its grid");
    }
}
