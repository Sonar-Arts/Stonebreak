package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchState;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Torches in an unloaded chunk keep lighting the FastLOD terrain that replaces it. */
class RememberedLightsTest {

    private static Chunk chunkWithTorch(int cx, int cz, int lx, int ly, int lz, String state) {
        Chunk chunk = new Chunk(cx, cz);
        chunk.setBlock(lx, ly, lz, BlockType.TORCH_PLACED);
        chunk.setBlockState(lx, ly, lz, state);
        return chunk;
    }

    /**
     * Unload {@code chunk} from a world whose registries index its torches, as the live game's
     * do (a stateful block is discovered on chunk load), so the capture sees what was lit.
     */
    static void unload(RememberedLights lights, Chunk chunk) {
        com.stonebreak.blocks.anim.AnimatedBlockRegistry registry = new com.stonebreak.blocks.anim.AnimatedBlockRegistry();
        for (int key : chunk.getBlockStates().keySet()) {
            int lx = com.stonebreak.world.chunk.utils.LocalBlockKey.x(key);
            int ly = com.stonebreak.world.chunk.utils.LocalBlockKey.y(key);
            int lz = com.stonebreak.world.chunk.utils.LocalBlockKey.z(key);
            if (chunk.getBlock(lx, ly, lz) == BlockType.TORCH_PLACED) {
                registry.positions().add(new BlockPos(chunk.getX() * 16 + lx, ly, chunk.getZ() * 16 + lz));
            }
        }
        com.stonebreak.world.World world = org.mockito.Mockito.mock(com.stonebreak.world.World.class);
        org.mockito.Mockito.when(world.getAnimatedBlockRegistry()).thenReturn(registry);
        org.mockito.Mockito.when(world.getFurnaceRegistry()).thenReturn(new com.stonebreak.blocks.furnace.FurnaceStateRegistry(null));
        lights.onChunkUnloaded(world, chunk);
    }

    @Test
    void unloadCapturesTheEmberInWorldSpace() {
        String wall = TorchState.STATE_PREFIX + "state=Side;facing=EAST";
        Chunk chunk = chunkWithTorch(-2, 3, 5, 70, 9, wall);
        RememberedLights lights = new RememberedLights();
        unload(lights, chunk);

        BlockPos pos = new BlockPos(-2 * 16 + 5, 70, 3 * 16 + 9);
        RememberedLights.Light light = lights.find(pos);
        assertNotNull(light);
        assertSame(BlockLightSource.TORCH, light.source());
        Vector3f expected = TorchState.parse(wall).emberPosition(pos.x(), pos.y(), pos.z(), new Vector3f());
        assertEquals(expected.x, light.x(), 1e-6f);
        assertEquals(expected.y, light.y(), 1e-6f);
        assertEquals(expected.z, light.z(), 1e-6f);
    }

    @Test
    void reloadingTheChunkForgetsItsLights() {
        Chunk chunk = chunkWithTorch(0, 0, 1, 64, 1, TorchState.STATE_PREFIX + "state=Ground;facing=NORTH");
        RememberedLights lights = new RememberedLights();
        unload(lights, chunk);
        assertEquals(1, lights.chunkCount());
        lights.onChunkLoaded(chunk);
        assertEquals(0, lights.chunkCount());
        assertNull(lights.find(new BlockPos(1, 64, 1)));
    }

    @Test
    void chunksWithoutLightsAreNotKept() {
        Chunk chunk = new Chunk(4, 4);
        // A furnace needs the world to judge whether it is lit, so it is not remembered.
        chunk.setBlock(0, 64, 0, BlockType.FURNACE);
        chunk.setBlockState(0, 64, 0, "furnace:state=Lit;burn=40;burnTotal=200");
        RememberedLights lights = new RememberedLights();
        unload(lights, chunk);
        assertEquals(0, lights.chunkCount());
    }

    @Test
    void onlyTheUnloadingChunksSourcesAreCaptured() {
        // A registry entry in another chunk, or one whose block is gone, is not this chunk's light.
        Chunk chunk = new Chunk(1, 1);
        com.stonebreak.blocks.anim.AnimatedBlockRegistry registry = new com.stonebreak.blocks.anim.AnimatedBlockRegistry();
        registry.positions().add(new BlockPos(5, 64, 5));      // chunk (0, 0)
        registry.positions().add(new BlockPos(20, 64, 20));    // inside, but the cell is air
        com.stonebreak.world.World world = org.mockito.Mockito.mock(com.stonebreak.world.World.class);
        org.mockito.Mockito.when(world.getAnimatedBlockRegistry()).thenReturn(registry);
        RememberedLights lights = new RememberedLights();
        lights.onChunkUnloaded(world, chunk);
        assertEquals(0, lights.chunkCount());
    }

    @Test
    void aTorchWithoutAStateIsRememberedAsAGroundTorch() {
        // Lit live from the registry even when its state string is missing, so it must stay lit.
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(2, 64, 2, BlockType.TORCH_PLACED);
        com.stonebreak.blocks.anim.AnimatedBlockRegistry registry = new com.stonebreak.blocks.anim.AnimatedBlockRegistry();
        registry.positions().add(new BlockPos(2, 64, 2));
        com.stonebreak.world.World world = org.mockito.Mockito.mock(com.stonebreak.world.World.class);
        org.mockito.Mockito.when(world.getAnimatedBlockRegistry()).thenReturn(registry);
        RememberedLights lights = new RememberedLights();
        lights.onChunkUnloaded(world, chunk);
        RememberedLights.Light light = lights.find(new BlockPos(2, 64, 2));
        assertNotNull(light);
        assertEquals(64.6f, light.y(), 1e-5f);
    }

    @Test
    void reunloadingReplacesTheChunksLights() {
        RememberedLights lights = new RememberedLights();
        unload(lights, chunkWithTorch(0, 0, 1, 64, 1, TorchState.STATE_PREFIX + "state=Ground;facing=NORTH"));
        unload(lights, new Chunk(0, 0)); // the torch was broken while it was loaded
        assertEquals(0, lights.chunkCount());
    }
}
