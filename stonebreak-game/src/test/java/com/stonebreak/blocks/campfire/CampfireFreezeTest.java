package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A camp freezes into its fire while the chunk is still resident — ahead of the chunk's save
 * snapshot — so what is saved is the frozen camp; dropping the chunk afterwards does not ask the
 * camp a second time (it is gone by then, and would answer "no camp").
 */
class CampfireFreezeTest {

    private static final int X = 3;
    private static final int Y = 64;
    private static final int Z = 5;

    /** A world that is just one resident chunk at (0, 0). */
    private static World worldOver(Chunk chunk) {
        World world = mock(World.class);
        when(world.getBlockStateAt(anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> chunk.getBlockState(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        doAnswer(inv -> {
            chunk.setBlockState(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
            return null;
        }).when(world).setBlockStateAt(anyInt(), anyInt(), anyInt(), anyString());
        return world;
    }

    @Test
    void theFrozenCampIsInTheChunkBeforeItUnloads() {
        Chunk chunk = new Chunk(0, 0);
        World world = worldOver(chunk);
        CampfireRegistry fires = new CampfireRegistry(new SmeltingManager());
        AtomicInteger asked = new AtomicInteger();
        fires.setAnchor(new CampAnchor() {
            @Override public void loaded(BlockPos fire, String campData) { }
            @Override public String unloading(BlockPos fire) {
                return asked.incrementAndGet() == 1 ? "frozen" : null;
            }
            @Override public void extinguished(BlockPos fire) { }
        });
        fires.onBlockPlaced(world, X, Y, Z);
        fires.setCampData(world, new BlockPos(X, Y, Z), "live");

        fires.freezeCamps(world, chunk);
        assertTrue(chunk.getBlockState(X, Y, Z).contains("camp=frozen"),
                "written while resident, so the save carries it: " + chunk.getBlockState(X, Y, Z));

        fires.onChunkUnloaded(chunk);
        assertEquals(1, asked.get(), "the camp is frozen once");
        assertTrue(chunk.getBlockState(X, Y, Z).contains("camp=frozen"), "and stays frozen");
    }
}
