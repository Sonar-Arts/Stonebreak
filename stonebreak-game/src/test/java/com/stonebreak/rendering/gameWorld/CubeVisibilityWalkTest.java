package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.voxel.mms.mmsRegion.MmsGpuCuller;
import com.openmason.engine.voxel.world.CendaVoxelWorld;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The camera pass's visibility walk over the engine's reusable {@link com.openmason.engine.voxel.world.VisibleCubeBuffers}:
 * missing cubes are read in place and capped for the proxies (the total still counts all of them), and a warm
 * walk allocates nothing per frame. Plus the F3 Hi-Z row's wording (pure function).
 */
class CubeVisibilityWalkTest {

    private static final int SURFACE_Y = 64;

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    @Test
    void walkCapsMissingCubesAndAllocatesNothingWhenWarm() {
        assumeTrue(CendaVoxelWorld.isAvailable(), "needs the Cenda voxel library");
        // Every column's surface at y=64: cubes cy >= 2 are known air, cy <= 1 unknown (terminal "missing").
        try (CubeWorld voxels = CubeWorld.create(WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y, 0,
                (cx, cz, sky, top) -> {
                    Arrays.fill(sky, SURFACE_Y);
                    Arrays.fill(top, SURFACE_Y);
                    return true;
                })) {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    voxels.createCube(cx, 3, cz, 0); // present air around the camera cube (0, 3, 0)
                }
            }
            double camX = 16.5, camY = 110.5, camZ = 16.5;
            RenderOrigin.update(camX, camY, camZ);
            float ex = (float) (camX - RenderOrigin.blockX());
            float ey = (float) (camY - RenderOrigin.blockY());
            float ez = (float) (camZ - RenderOrigin.blockZ());
            Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(90), 16f / 9f, 0.1f, 2000f)
                    .lookAt(ex, ey, ez, ex + 1f, ey - 0.6f, ez + 0.3f, 0f, 1f, 0f);

            CubeVisibility visibility = new CubeVisibility(16);
            ChunkRegionRenderer regions = ChunkRegionRenderer.getInstance();
            visibility.update(voxels, regions, viewProj, camX, camY, camZ, 256);

            assertTrue(visibility.walkActive(), "the native walk ran");
            assertTrue(visibility.missingTotal() > 16, "walk reaches the unknown layer: " + visibility.missingTotal());
            assertEquals(16, visibility.missingCount(), "proxies see at most the cap");
            for (int i = 0; i < visibility.missingCount(); i++) {
                assertTrue(visibility.missingY(i) <= 1, "only cubes below the surface are unknown");
            }

            // Warm up (JIT + buffer growth), then the best window of frames must allocate nothing.
            for (int f = 0; f < 300; f++) {
                visibility.update(voxels, regions, viewProj, camX, camY, camZ, 256);
            }
            int grows = visibility.bufferGrowCount();
            long m0 = allocated();
            long meterCost = allocated() - m0;
            long best = Long.MAX_VALUE;
            for (int w = 0; w < 5; w++) {
                long before = allocated();
                for (int f = 0; f < 50; f++) {
                    visibility.update(voxels, regions, viewProj, camX, camY, camZ, 256);
                }
                best = Math.min(best, Math.max(0L, allocated() - before - meterCost));
            }
            assertEquals(0L, best, "bytes allocated by the best window of 50 warm walks");
            assertEquals(grows, visibility.bufferGrowCount(), "steady walks never regrow the buffers");
        }
    }

    /**
     * A camera flown above the world's top (y 850 > MAX_Y 640): the walk used to start in the out-of-bounds
     * camera cube, whose every neighbour the native walk skips, so nothing below was reached (a sky hole over
     * READY terrain). It now starts from the top in-bounds cube of the camera's column and reaches the surface,
     * looking straight down AND obliquely (where the start column itself is out of the frustum).
     */
    @Test
    void cameraOutsideTheBoundsDrawsByFrustumOnly() {
        assumeTrue(CendaVoxelWorld.isAvailable(), "needs the Cenda voxel library");
        // A REMOTE-like world: no surface callback, so every column without a record is UNKNOWN. From above
        // the world a walk started in such a column meets only unknown (terminal) cubes and reached nothing,
        // so every READY column still loaded (the LOD hidden there) disappeared at once. Above / below the
        // bounds there is nothing to occlude: no walk, every drawable cube in the frustum draws.
        try (CubeWorld voxels = CubeWorld.create(WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y, 0, null)) {
            ChunkRegionRenderer regions = ChunkRegionRenderer.getInstance();
            for (double camY : new double[] {850.5, 1000.5, WorldConfiguration.MIN_Y - 40.5}) {
                double camX = 16.5, camZ = 16.5;
                RenderOrigin.update(camX, camY, camZ);
                float ex = (float) (camX - RenderOrigin.blockX());
                float ey = (float) (camY - RenderOrigin.blockY());
                float ez = (float) (camZ - RenderOrigin.blockZ());
                Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 4000f)
                        .lookAt(ex, ey, ez, ex, ey - 1f, ez + 0.001f, 0f, 1f, 0f);
                CubeVisibility visibility = new CubeVisibility(64);
                visibility.update(voxels, regions, viewProj, camX, camY, camZ, 640);
                assertFalse(visibility.walkActive(), "no walk from y " + camY);
                assertEquals(-1, visibility.walkFrame(), "frustum-only frame (prepareCameraPass enables all)");
                assertEquals(0, visibility.missingTotal(), "no proxies outside the bounds");
                assertEquals(Math.floorDiv((long) camY, 32L), visibility.cameraCubeY(), "camera cube kept");
            }
            // Inside the bounds the walk still runs (and walks through an unknown camera cube).
            RenderOrigin.update(16.5, 600.5, 16.5);
            CubeVisibility inside = new CubeVisibility(64);
            inside.update(voxels, regions, new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f,
                4000f), 16.5, 600.5, 16.5, 128);
            assertTrue(inside.walkActive(), "walk inside the bounds");
            assertTrue(CubeVisibility.outsideVerticalBounds(WorldConfiguration.MAX_CUBE_Y));
            assertTrue(CubeVisibility.outsideVerticalBounds(WorldConfiguration.MIN_CUBE_Y - 1));
            assertFalse(CubeVisibility.outsideVerticalBounds(WorldConfiguration.MAX_CUBE_Y - 1));
            assertFalse(CubeVisibility.outsideVerticalBounds(WorldConfiguration.MIN_CUBE_Y));
        }
    }

    @Test
    void hizRowNamesEveryState() {
        assertTrue(TerrainRenderStats.hizRow(false, false, MmsGpuCuller.HiZStats.NONE, 0, 0, false)
                .startsWith("off (CPU"));
        assertTrue(TerrainRenderStats.hizRow(true, false, MmsGpuCuller.HiZStats.NONE, 0, 0, false)
                .contains("stonebreak.hiz=off"));
        assertTrue(TerrainRenderStats.hizRow(true, true, MmsGpuCuller.HiZStats.NONE, 7, 2, true)
                .contains("multisampled"));
        assertTrue(TerrainRenderStats.hizRow(true, true, MmsGpuCuller.HiZStats.NONE, 7, 2, false)
                .contains("no GPU counters yet"));
        assertEquals("30/120 occluded, 80+10 drawn",
                TerrainRenderStats.hizRow(true, true, new MmsGpuCuller.HiZStats(9, 80, 120, 30, 10), 1, 1, false));
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
