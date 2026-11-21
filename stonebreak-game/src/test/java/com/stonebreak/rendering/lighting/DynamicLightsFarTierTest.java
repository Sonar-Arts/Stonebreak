package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.world.World;
import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lights past the shadowed near set fall to the far tier instead of going dark. */
class DynamicLightsFarTierTest {

    private World world;
    private AnimatedBlockRegistry animated;
    private RememberedLights remembered;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        animated = new AnimatedBlockRegistry();
        remembered = new RememberedLights();
        when(world.getAnimatedBlockRegistry()).thenReturn(animated);
        when(world.getFurnaceRegistry()).thenReturn(new FurnaceStateRegistry(null));
        when(world.getRememberedLights()).thenReturn(remembered);
        when(world.getBlockAt(anyLong(), anyLong(), anyLong())).thenReturn(BlockType.AIR);
    }

    private void placeTorch(int x, int y, int z) {
        animated.positions().add(new BlockPos(x, y, z));
        when(world.getBlockAt((long) x, (long) y, (long) z)).thenReturn(BlockType.TORCH_PLACED);
    }

    /**
     * Placed torches (block plus the state every torch writes) in the cube holding {@code first}, captured by
     * {@code remembered} as that cube unloads — while it is still resident in a world of its own.
     */
    private static void unloadTorches(RememberedLights remembered, BlockPos... torches) {
        World resident = mock(World.class);
        AnimatedBlockRegistry registry = new AnimatedBlockRegistry();
        when(resident.getAnimatedBlockRegistry()).thenReturn(registry);
        when(resident.getFurnaceRegistry()).thenReturn(new FurnaceStateRegistry(null));
        when(resident.getBlockAt(anyLong(), anyLong(), anyLong())).thenReturn(BlockType.AIR);
        for (BlockPos t : torches) {
            registry.positions().add(t);
            when(resident.getBlockAt((long) t.x(), (long) t.y(), (long) t.z())).thenReturn(BlockType.TORCH_PLACED);
            when(resident.getBlockStateAt((long) t.x(), (long) t.y(), (long) t.z())).thenReturn(
                    com.stonebreak.blocks.torch.TorchState.STATE_PREFIX + "state=Ground;facing=NORTH");
        }
        BlockPos first = torches[0];
        remembered.onCubeUnloadPending(resident, new Cube(resident, CubePos.ofBlock(first.x(), first.y(), first.z())));
    }

    private static Vector3d camera() {
        return new Vector3d(0.5, 64.5, 0.5);
    }

    @Test
    void torchesPastTheNearReachStayLit() {
        placeTorch(10, 64, 0);
        placeTorch(100, 64, 0);
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(1, DynamicLights.count(), "the close torch keeps its shadowed slot");
        assertEquals(1, DynamicLights.farCount(), "the distant one is lit unshadowed");
    }

    @Test
    void lightsBeyondTheNearBudgetOverflowIntoTheFarTier() {
        for (int i = 0; i < PointLightGlsl.MAX_LIGHTS + 5; i++) placeTorch(i * 2, 64, 3);
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(PointLightGlsl.MAX_LIGHTS, DynamicLights.count());
        assertEquals(5, DynamicLights.farCount());
    }

    @Test
    void torchesInUnloadedCubesLightTheirLodTerrain() {
        unloadTorches(remembered, new BlockPos(20 * 16 + 3, 70, 3));
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(0, DynamicLights.count());
        assertEquals(1, DynamicLights.farCount());
    }

    @Test
    void theFarTierIsBounded() {
        // One 32-block cube at (15, 2, 15): 16 x 6 torches along its x/y faces.
        BlockPos[] torches = new BlockPos[DynamicLights.MAX_FAR_LIGHTS + 10];
        for (int i = 0; i < torches.length; i++) {
            torches[i] = new BlockPos(480 + i % 16, 64 + i / 16, 480);
        }
        unloadTorches(remembered, torches);
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(DynamicLights.MAX_FAR_LIGHTS, DynamicLights.farCount());
    }
}
