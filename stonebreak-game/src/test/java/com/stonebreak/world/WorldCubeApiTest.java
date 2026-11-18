package com.stonebreak.world;

import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.VoxelLayers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Phase 3 World API over a detached native world (no stream): blocks, layers, states, cubes, bounds. */
class WorldCubeApiTest {

    private final World world = World.createDetached(new WorldConfiguration(), 7L);

    @AfterEach
    void close() {
        world.cleanup();
    }

    @Test
    void blocksAtSixtyFourBitCoordinatesAndNegativeY() {
        long far = 3_000_000_000L;
        assertTrue(world.setBlockAt(far, -200, -far, BlockType.STONE));
        assertEquals(BlockType.STONE, world.getBlockAt(far, -200, -far));
        assertTrue(world.isLoaded(far, -200, -far));
        Cube cube = world.getCubeAt(far, -200, -far);
        assertNotNull(cube);
        assertEquals(far >> 5, cube.cx());
        assertEquals(-200 >> 5, cube.cy());
        assertTrue(cube.contains(far, -200, -far));
    }

    @Test
    void boundsAndKnownAir() {
        assertFalse(world.setBlockAt(0, WorldConfiguration.MAX_Y, 0, BlockType.STONE));
        assertFalse(world.setBlockAt(0, WorldConfiguration.MIN_Y - 1, 0, BlockType.STONE));
        assertTrue(world.setBlockAt(0, WorldConfiguration.MIN_Y, 0, BlockType.BEDROCK));
        assertEquals(BlockType.AIR, world.getBlockAt(0, WorldConfiguration.MAX_Y + 10, 0));
        // A detached world has no generator surface: unloaded space is known air.
        assertFalse(world.isLoaded(500, 70, 500));
        assertTrue(world.isKnown(500, 70, 500));
        assertEquals(BlockType.AIR, world.getBlockAt(500, 70, 500));
    }

    /**
     * After {@link World#cleanup()} (world switch, leave to menu) a straggling per-tick caller - weather audio
     * polled {@code topY} through {@code WeatherSurface} and hit "CubeWorld is closed" - gets the closed defaults
     * instead of an exception from the freed native world.
     */
    @Test
    void queriesOnAClosedWorldAnswerDefaultsInsteadOfThrowing() {
        World closed = World.createDetached(new WorldConfiguration(), 9L);
        closed.setBlockAt(2, 60, 2, BlockType.STONE);
        closed.setBlockAt(2, 61, 2, BlockType.WATER);
        closed.cleanup();
        assertTrue(closed.isClosed());

        assertEquals(BlockType.AIR, closed.getBlockAt(2, 60, 2));
        assertEquals(CubeWorld.MISSING, closed.getBlockIdAt(2, 60, 2));
        assertEquals(WorldConfiguration.MIN_Y, closed.topY(2, 2));
        assertEquals(WorldConfiguration.MIN_Y, closed.surfaceY(2, 2));
        assertFalse(closed.isLoaded(2, 60, 2));
        assertFalse(closed.isKnown(2, 60, 2));
        assertFalse(closed.isAreaReadyAt(2, 61, 2));
        assertFalse(closed.isColumnReady(0, 0));
        assertFalse(closed.isColumnReadyAt(2, 2));
        assertFalse(closed.hasCube(0, 1, 0));
        assertNull(closed.getCube(0, 1, 0));
        assertNull(closed.ensureCube(0, 1, 0));
        assertFalse(closed.isCubeKnownAir(0, 5, 0));
        assertEquals(0, closed.getLoadedCubeCount());
        assertNull(closed.getBlockStateAt(2, 60, 2));
        closed.setBlockStateAt(2, 60, 2, "facing=north");
        assertEquals(-1, closed.getWaterValueAt(2, 61, 2));
        assertEquals(-1, closed.getWaterLevelAt(2, 61, 2));
        assertNull(closed.getWaterStateAt(2, 61, 2));
        assertFalse(closed.isWaterSourceAt(2, 61, 2));
        assertFalse(closed.isPositionUnderwater(2, 61, 2));
        assertFalse(closed.setWaterValueAt(2, 61, 2, 3));
        assertEquals(1, closed.getSnowLayers(2, 62, 2));
        closed.getSnowLayerManager().setSnowLayers(2, 62, 2, 3);
        assertEquals(BlockType.AIR.getVisualHeight(), closed.getSnowHeight(2, 62, 2));
        assertFalse(closed.setBlockAt(2, 60, 2, BlockType.DIRT));
        assertFalse(closed.setBlockRaw(2, 60, 2, BlockType.DIRT));
        assertEquals(WorldConfiguration.NO_WATER, closed.getGeneratedWaterLevelAt(2, 2));
        assertNull(closed.streamStats());
        assertNull(closed.physics(), "a closed world has no physics of its own");
        closed.setStreamCenters(List.of(closed.centerAt(0, 64, 0)));
        closed.updateMainThread();
        closed.tickStream();
        closed.rebuildAllLoadedChunks();
        closed.cleanup(); // idempotent

        // The crash's own path: WeatherSurface over the closed world.
        assertEquals(com.stonebreak.world.weather.WeatherSurface.UNKNOWN,
                com.stonebreak.world.weather.WeatherSurface.groundY(closed, 2, 2));
        assertFalse(com.stonebreak.world.weather.WeatherSurface.isSnow(closed, 2, 2));
    }

    @Test
    void surfaceAndTopFollowEdits() {
        world.setBlockAt(4, 60, 4, BlockType.STONE);
        world.setBlockAt(4, 61, 4, BlockType.WATER);
        world.setBlockAt(4, 62, 4, BlockType.WATER);
        assertEquals(61, world.surfaceY(4, 4));
        assertEquals(63, world.topY(4, 4));
        world.setBlockAt(4, 90, 4, BlockType.LEAVES);
        assertEquals(61, world.surfaceY(4, 4), "leaves pass sky light");
        assertEquals(91, world.topY(4, 4));
    }

    @Test
    void waterValuesLiveInTheLayerAndDieWithTheBlock() {
        world.setBlockAt(1, 70, 1, BlockType.WATER);
        assertEquals(ChunkWaterLayer.SOURCE, world.getWaterValueAt(1, 70, 1));
        assertTrue(world.setWaterValueAt(1, 70, 1, 5));
        assertEquals(5, world.getWaterLevelAt(1, 70, 1));
        assertTrue(world.setWaterValueAt(1, 70, 1, ChunkWaterLayer.river(3)));
        assertEquals(ChunkWaterLayer.SOURCE, world.getWaterLevelAt(1, 70, 1));
        assertEquals(3, World.riverFlowOf(world.getWaterValueAt(1, 70, 1)));
        world.setBlockAt(1, 70, 1, BlockType.STONE);
        assertEquals(-1, world.getWaterValueAt(1, 70, 1));
        assertEquals(0, world.voxels().getLayer(VoxelLayers.WATER, 1, 70, 1));
    }

    @Test
    void variantStatesUseTheLayerAndPayloadStatesTheCube() {
        world.setBlockAt(2, 70, 2, BlockType.OAK_STAIRS, false, "stairs:facing=EAST");
        assertEquals("stairs:facing=EAST", world.getBlockStateAt(2, 70, 2));
        int id = world.voxels().getLayer(VoxelLayers.STATE, 2, 70, 2);
        assertEquals("stairs:facing=EAST", world.blockStates().nameOf(id));

        String furnace = "furnace:state=lit;fuel=3;slots=a,b";
        world.setBlockAt(3, 70, 3, BlockType.FURNACE);
        world.setBlockStateAt(3, 70, 3, furnace);
        assertEquals(furnace, world.getBlockStateAt(3, 70, 3));
        assertEquals("lit", world.blockStates().nameOf(world.voxels().getLayer(VoxelLayers.STATE, 3, 70, 3)));

        List<String> seen = new ArrayList<>();
        world.getCubeAt(3, 70, 3).forEachState((x, y, z, s) -> seen.add(x + "," + y + "," + z + "=" + s));
        assertTrue(seen.contains("3,70,3=" + furnace));
        assertTrue(seen.contains("2,70,2=stairs:facing=EAST"));

        world.setBlockAt(3, 70, 3, BlockType.AIR);
        assertNull(world.getBlockStateAt(3, 70, 3), "a type change drops the state and its payload");
    }

    @Test
    void cubeBlobRoundTripsPayloadAndSections() {
        world.setBlockAt(5, 70, 5, BlockType.FURNACE);
        world.setBlockStateAt(5, 70, 5, "furnace:state=unlit;slots=x");
        Cube cube = world.getCubeAt(5, 70, 5);
        cube.putBlobSection("entities", new byte[]{1, 2, 3});
        assertTrue(cube.isPayloadDirty());
        byte[] blob = cube.encodeBlob();
        assertFalse(cube.isPayloadDirty());

        Cube copy = new Cube(world, cube.pos());
        copy.decodeBlob(blob);
        assertEquals(cube.payloadStates(), copy.payloadStates());
        assertArrayEquals(new byte[]{1, 2, 3}, copy.blobSection("entities"));

        assertTrue(world.voxels().setBlob(cube.cx(), cube.cy(), cube.cz(), blob));
        assertArrayEquals(blob, world.voxels().getBlob(cube.cx(), cube.cy(), cube.cz()));
    }

    @Test
    void encodedCubeCarriesBlocksAndLayers() {
        world.setBlockAt(6, 70, 6, BlockType.SNOW);
        world.getSnowLayerManager().setSnowLayers(6, 70, 6, 4);
        world.setBlockAt(7, 70, 6, BlockType.WATER);
        world.setWaterValueAt(7, 70, 6, 2);
        Cube cube = world.getCubeAt(6, 70, 6);
        byte[] bytes = world.voxels().encode(cube.cx(), cube.cy(), cube.cz(), CubeWorld.PARTS_NETWORK);

        World other = World.createDetached(new WorldConfiguration(), 7L);
        try {
            other.voxels().decode(cube.cx(), cube.cy(), cube.cz(), bytes);
            assertEquals(BlockType.SNOW, other.getBlockAt(6, 70, 6));
            assertEquals(4, other.getSnowLayers(6, 70, 6));
            assertEquals(2, other.getWaterLevelAt(7, 70, 6));
        } finally {
            other.cleanup();
        }
    }

    @Test
    void columnReadinessInADetachedWorld() {
        assertTrue(world.isColumnReady(0, 0), "a world without a stream treats every column as ready");
        assertTrue(world.isAreaReadyAt(0, 70, 0));
        assertEquals(1f, world.areaDrawnFraction(0, 0, 2), 0f);
    }
}
