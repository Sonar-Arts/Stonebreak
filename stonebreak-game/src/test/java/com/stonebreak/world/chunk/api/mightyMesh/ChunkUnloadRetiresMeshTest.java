package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Once a chunk is unloaded the pipeline must never take it back. Stale references
 * (a build in flight, a neighbor remesh request, a retry) used to re-add the dead
 * chunk to the pipeline's sets and maps, pinning the whole Chunk in memory and
 * uploading GPU meshes nothing would ever free.
 */
class ChunkUnloadRetiresMeshTest {

    @Test
    void unloadedChunkIsNeverRescheduled() {
        MmsMeshPipeline pipeline = new MmsMeshPipeline(
                mock(World.class), new WorldConfiguration(), new ChunkErrorReporter());
        try {
            Chunk control = new Chunk(1, 0);
            control.getCcoDirtyTracker().markMeshDirtyOnly();
            pipeline.scheduleConditionalMeshBuild(control);
            assertEquals(1, pipeline.getPendingMeshBuildCount(), "control chunk should schedule");

            Chunk chunk = new Chunk(0, 0);
            pipeline.removeChunkFromQueues(chunk);
            assertTrue(chunk.isMeshRetired());

            chunk.getCcoDirtyTracker().markMeshDirtyOnly();
            pipeline.scheduleConditionalMeshBuild(chunk);
            assertEquals(1, pipeline.getPendingMeshBuildCount(), "retired chunk was rescheduled");
        } finally {
            pipeline.shutdown();
        }
    }
}
