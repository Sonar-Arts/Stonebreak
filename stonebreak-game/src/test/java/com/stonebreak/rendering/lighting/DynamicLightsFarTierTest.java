package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;
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
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.AIR);
    }

    private void placeTorch(int x, int y, int z) {
        animated.positions().add(new BlockPos(x, y, z));
        when(world.getBlockAt(x, y, z)).thenReturn(BlockType.TORCH_PLACED);
    }

    /** A placed torch as the game leaves it: block plus the state every torch writes. */
    private static void torch(Chunk chunk, int lx, int ly, int lz) {
        chunk.setBlock(lx, ly, lz, BlockType.TORCH_PLACED);
        chunk.setBlockState(lx, ly, lz, com.stonebreak.blocks.torch.TorchState.STATE_PREFIX + "state=Ground;facing=NORTH");
    }

    private static Vector3f camera() {
        return new Vector3f(0.5f, 64.5f, 0.5f);
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
    void torchesInUnloadedChunksLightTheirLodTerrain() {
        Chunk far = new Chunk(20, 0);
        torch(far, 3, 70, 3);
        RememberedLightsTest.unload(remembered, far);
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(0, DynamicLights.count());
        assertEquals(1, DynamicLights.farCount());
    }

    @Test
    void theFarTierIsBounded() {
        Chunk far = new Chunk(30, 30);
        for (int i = 0; i < DynamicLights.MAX_FAR_LIGHTS + 10; i++) {
            torch(far, i % 16, 64 + i / 16, 0);
        }
        RememberedLightsTest.unload(remembered, far);
        DynamicLights.update(world, null, camera(), 0f);
        assertEquals(DynamicLights.MAX_FAR_LIGHTS, DynamicLights.farCount());
    }
}
