package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchState;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Torches in an unloaded cube keep lighting the LOD terrain that replaces it. */
class RememberedLightsTest {

    /** A mocked world holding the given blocks/states, with its registries indexing the torches among them. */
    private static final class Fixture {
        final Map<BlockPos, BlockType> blocks = new HashMap<>();
        final Map<BlockPos, String> states = new HashMap<>();
        final com.stonebreak.blocks.anim.AnimatedBlockRegistry registry = new com.stonebreak.blocks.anim.AnimatedBlockRegistry();
        final World world = mock(World.class);

        Fixture() {
            when(world.getAnimatedBlockRegistry()).thenReturn(registry);
            when(world.getFurnaceRegistry()).thenReturn(new com.stonebreak.blocks.furnace.FurnaceStateRegistry(null));
            when(world.getBlockAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> blocks.getOrDefault(
                    pos(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), BlockType.AIR));
            when(world.getBlockStateAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> states.get(
                    pos(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))));
        }

        private static BlockPos pos(long x, long y, long z) {
            return new BlockPos((int) x, (int) y, (int) z);
        }

        /** A block (and its state), indexed as a lit torch when it is one, as the live game does on install. */
        Fixture put(int x, int y, int z, BlockType type, String state) {
            BlockPos p = new BlockPos(x, y, z);
            blocks.put(p, type);
            if (state != null) {
                states.put(p, state);
            }
            if (type == BlockType.TORCH_PLACED) {
                registry.positions().add(p);
            }
            return this;
        }

        Cube cubeOf(int x, int y, int z) {
            return new Cube(world, CubePos.ofBlock(x, y, z));
        }
    }

    @Test
    void unloadCapturesTheEmberInWorldSpace() {
        String wall = TorchState.STATE_PREFIX + "state=Side;facing=EAST";
        BlockPos pos = new BlockPos(-2 * 16 + 5, 70, 3 * 16 + 9);
        Fixture f = new Fixture().put(pos.x(), pos.y(), pos.z(), BlockType.TORCH_PLACED, wall);
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, f.cubeOf(pos.x(), pos.y(), pos.z()));

        RememberedLights.Light light = lights.find(pos);
        assertNotNull(light);
        assertSame(BlockLightSource.TORCH, light.source());
        Vector3f expected = TorchState.parse(wall).emberPosition(pos.x(), pos.y(), pos.z(), new Vector3f());
        assertEquals(expected.x, light.x(), 1e-6f);
        assertEquals(expected.y, light.y(), 1e-6f);
        assertEquals(expected.z, light.z(), 1e-6f);
    }

    @Test
    void reinstallingTheCubeForgetsItsLights() {
        Fixture f = new Fixture().put(1, 64, 1, BlockType.TORCH_PLACED, TorchState.STATE_PREFIX + "state=Ground;facing=NORTH");
        Cube cube = f.cubeOf(1, 64, 1);
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, cube);
        assertEquals(1, lights.chunkCount());
        lights.onCubeInstalled(cube);
        assertEquals(0, lights.chunkCount());
        assertNull(lights.find(new BlockPos(1, 64, 1)));
    }

    @Test
    void cubesWithoutLightsAreNotKept() {
        // A furnace needs the world to judge whether it is lit, so it is not remembered.
        Fixture f = new Fixture().put(0, 64, 0, BlockType.FURNACE, "furnace:state=Lit;burn=40;burnTotal=200");
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, f.cubeOf(0, 64, 0));
        assertEquals(0, lights.chunkCount());
    }

    @Test
    void onlyTheUnloadingCubesSourcesAreCaptured() {
        // A registry entry in another cube, or one whose block is gone, is not this cube's light.
        Fixture f = new Fixture();
        f.registry.positions().add(new BlockPos(5, 64, 5));      // cube (0, 2, 0)
        f.registry.positions().add(new BlockPos(40, 64, 40));    // inside cube (1, 2, 1), but the cell is air
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, f.cubeOf(40, 64, 40));
        assertEquals(0, lights.chunkCount());
    }

    @Test
    void aTorchWithoutAStateIsRememberedAsAGroundTorch() {
        // Lit live from the registry even when its state string is missing, so it must stay lit.
        Fixture f = new Fixture().put(2, 64, 2, BlockType.TORCH_PLACED, null);
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, f.cubeOf(2, 64, 2));
        RememberedLights.Light light = lights.find(new BlockPos(2, 64, 2));
        assertNotNull(light);
        assertEquals(64.6f, light.y(), 1e-5f);
    }

    @Test
    void reunloadingReplacesTheCubesLights() {
        Fixture f = new Fixture().put(1, 64, 1, BlockType.TORCH_PLACED, TorchState.STATE_PREFIX + "state=Ground;facing=NORTH");
        RememberedLights lights = new RememberedLights();
        lights.onCubeUnloadPending(f.world, f.cubeOf(1, 64, 1));
        // The torch was broken while it was loaded.
        f.blocks.clear();
        f.states.clear();
        lights.onCubeUnloadPending(f.world, f.cubeOf(1, 64, 1));
        assertEquals(0, lights.chunkCount());
    }
}
