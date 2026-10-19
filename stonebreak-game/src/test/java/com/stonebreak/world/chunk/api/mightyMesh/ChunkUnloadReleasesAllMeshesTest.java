package com.stonebreak.world.chunk.api.mightyMesh;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.openmason.engine.voxel.sbo.SBORenderData;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Unloading a chunk must release EVERY mesh it owns. The stamp layer (crosses — tall grass,
 * flowers — and SBO stamps) and the per-type SBO meshes used to be skipped, so their region
 * handles stayed live after the chunk was gone; the GPU-culled pass draws every live region
 * mesh in the frustum, so that vegetation kept rendering outside the loaded area.
 */
class ChunkUnloadReleasesAllMeshesTest {

    @Test
    void gpuCleanupQueuesStampAndSboHandlesAndClearsThem() {
        MmsMeshPipeline pipeline = new MmsMeshPipeline(
                mock(World.class), new WorldConfiguration(), new ChunkErrorReporter());
        Chunk chunk = new Chunk(0, 0);

        chunk.setRegionAtlasHandle(mock(MmsRegionMeshHandle.class));
        chunk.setRegionStampHandle(mock(MmsRegionMeshHandle.class));
        chunk.setStampRenderableHandle(mock(MmsRenderableHandle.class));
        chunk.setSBORenderDataList(List.of(
                new SBORenderData(mock(MmsRenderableHandle.class), null),
                new SBORenderData(mock(MmsRenderableHandle.class), null)));

        pipeline.addChunkForGpuCleanup(chunk);

        assertNull(chunk.getRegionAtlasHandle());
        assertNull(chunk.getRegionStampHandle(), "region stamp handle left live after unload");
        assertNull(chunk.getStampRenderableHandle(), "legacy stamp handle left live after unload");
        assertNull(chunk.getSBORenderDataList(), "SBO meshes left live after unload");
        // atlas + region stamp + legacy stamp + two SBO meshes
        assertEquals(5, pipeline.getPendingCleanupCount());
    }
}
