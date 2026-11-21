package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.world.World;
import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.world.chunk.Cube;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A camp freezes into its fire while the cube is still resident — ahead of the cube's save
 * — so what is saved is the frozen camp; dropping the cube afterwards does not ask the camp a
 * second time (it is gone by then, and would answer "no camp").
 */
class CampfireFreezeTest {

    private static final int X = 3;
    private static final int Y = 64;
    private static final int Z = 5;

    /** A world whose block states live in {@code states} (keyed by block position). */
    private static World worldOver(Map<BlockPos, String> states) {
        World world = mock(World.class);
        when(world.getBlockStateAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> states.get(
                new BlockPos((int) (long) inv.getArgument(0), (int) (long) inv.getArgument(1), (int) (long) inv.getArgument(2))));
        doAnswer(inv -> {
            states.put(new BlockPos((int) (long) inv.getArgument(0), (int) (long) inv.getArgument(1),
                    (int) (long) inv.getArgument(2)), inv.getArgument(3));
            return null;
        }).when(world).setBlockStateAt(anyLong(), anyLong(), anyLong(), anyString());
        return world;
    }

    @Test
    void theFrozenCampIsInTheCubeBeforeItUnloads() {
        Map<BlockPos, String> states = new HashMap<>();
        World world = worldOver(states);
        Cube cube = new Cube(world, CubePos.ofBlock(X, Y, Z));
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

        BlockPos fire = new BlockPos(X, Y, Z);
        fires.freezeCamps(world, cube);
        assertTrue(states.get(fire).contains("camp=frozen"),
                "written while resident, so the save carries it: " + states.get(fire));

        fires.onCubeUnloaded(cube);
        assertEquals(1, asked.get(), "the camp is frozen once");
        assertTrue(states.get(fire).contains("camp=frozen"), "and stays frozen");
    }
}
