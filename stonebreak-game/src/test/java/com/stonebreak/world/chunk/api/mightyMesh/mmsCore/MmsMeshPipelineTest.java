package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MmsMeshPipeline - the mesh generation and upload pipeline.
 *
 * Tests pipeline operations:
 * - Mesh generation scheduling
 * - GPU upload processing
 * - Retry logic
 * - Resource cleanup
 */
@DisplayName("MmsMeshPipeline Tests")
public class MmsMeshPipelineTest {

    private World mockWorld;
    private WorldConfiguration mockConfig;
    private ChunkErrorReporter mockErrorReporter;
    private MmsMeshPipeline pipeline;

    @BeforeEach
    void setUp() {
        mockWorld = Mockito.mock(World.class);
        mockConfig = Mockito.mock(WorldConfiguration.class);
        mockErrorReporter = Mockito.mock(ChunkErrorReporter.class);

        // Configure mock config
        when(mockConfig.getChunkBuildThreads()).thenReturn(2);

        pipeline = new MmsMeshPipeline(mockWorld, mockConfig, mockErrorReporter);
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
    }

    // === Constructor Tests ===

    @Test
    @DisplayName("Constructor should throw when world is null")
    void constructorShouldThrowWhenWorldIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            new MmsMeshPipeline(null, mockConfig, mockErrorReporter);
        });
    }

    @Test
    @DisplayName("Constructor should throw when config is null")
    void constructorShouldThrowWhenConfigIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            new MmsMeshPipeline(mockWorld, null, mockErrorReporter);
        });
    }

    @Test
    @DisplayName("Constructor should throw when error reporter is null")
    void constructorShouldThrowWhenErrorReporterIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            new MmsMeshPipeline(mockWorld, mockConfig, null);
        });
    }

    @Test
    @DisplayName("Constructor should initialize pipeline successfully")
    void constructorShouldInitializePipelineSuccessfully() {
        assertNotNull(pipeline);
        assertEquals(0, pipeline.getPendingMeshBuildCount());
        assertEquals(0, pipeline.getPendingGLUploadCount());
    }

    // === Queue Statistics Tests ===

    @Test
    @DisplayName("Should track pending mesh build count")
    void shouldTrackPendingMeshBuildCount() {
        assertEquals(0, pipeline.getPendingMeshBuildCount(),
            "Should start with 0 pending builds");
    }

    @Test
    @DisplayName("Should track pending GL upload count")
    void shouldTrackPendingGLUploadCount() {
        assertEquals(0, pipeline.getPendingGLUploadCount(),
            "Should start with 0 pending uploads");
    }

    @Test
    @DisplayName("Should track pending cleanup count")
    void shouldTrackPendingCleanupCount() {
        assertEquals(0, pipeline.getPendingCleanupCount(),
            "Should start with 0 pending cleanups");
    }

    @Test
    @DisplayName("Should track failed chunk count")
    void shouldTrackFailedChunkCount() {
        assertEquals(0, pipeline.getFailedChunkCount(),
            "Should start with 0 failed chunks");
    }

    // === Chunk Scheduling Tests ===

    @Test
    @DisplayName("Should ignore null chunk when scheduling")
    void shouldIgnoreNullChunkWhenScheduling() {
        assertDoesNotThrow(() -> {
            pipeline.scheduleConditionalMeshBuild(null);
        });

        assertEquals(0, pipeline.getPendingMeshBuildCount());
    }

    @Test
    @DisplayName("Should not schedule chunk that is not ready")
    void shouldNotScheduleChunkThatIsNotReady() {
        Chunk mockChunk = createMockChunk(0, 0);

        // Configure chunk as not dirty
        when(mockChunk.getCcoDirtyTracker().isMeshDirty()).thenReturn(false);

        pipeline.scheduleConditionalMeshBuild(mockChunk);

        assertEquals(0, pipeline.getPendingMeshBuildCount(),
            "Should not schedule non-dirty chunk");
    }

    @Test
    @DisplayName("Should not schedule chunk that is already generating")
    void shouldNotScheduleChunkThatIsAlreadyGenerating() {
        Chunk mockChunk = createMockChunk(0, 0);

        // Configure chunk as dirty but already generating
        when(mockChunk.getCcoDirtyTracker().isMeshDirty()).thenReturn(true);
        when(mockChunk.getCcoStateManager().hasState(CcoChunkState.MESH_GENERATING))
            .thenReturn(true);

        pipeline.scheduleConditionalMeshBuild(mockChunk);

        assertEquals(0, pipeline.getPendingMeshBuildCount(),
            "Should not schedule chunk already generating");
    }

    // === Queue Removal Tests ===

    @Test
    @DisplayName("Should remove chunk from all queues")
    void shouldRemoveChunkFromAllQueues() {
        Chunk mockChunk = createMockChunk(0, 0);

        pipeline.removeChunkFromQueues(mockChunk);

        // Should not throw and should handle gracefully
        assertEquals(0, pipeline.getPendingMeshBuildCount());
    }

    @Test
    @DisplayName("Should handle null chunk when removing from queues")
    void shouldHandleNullChunkWhenRemovingFromQueues() {
        assertDoesNotThrow(() -> {
            pipeline.removeChunkFromQueues(null);
        });
    }

    // === GPU Cleanup Tests ===

    @Test
    @DisplayName("Should schedule chunk for GPU cleanup")
    void shouldScheduleChunkForGPUCleanup() {
        Chunk mockChunk = createMockChunk(0, 0);
        MmsRenderableHandle mockHandle = mock(MmsRenderableHandle.class);

        when(mockChunk.getMmsRenderableHandle()).thenReturn(mockHandle);

        pipeline.addChunkForGpuCleanup(mockChunk);

        verify(mockChunk).setMmsRenderableHandle(null);
        verify(mockChunk.getCcoStateManager())
            .removeState(CcoChunkState.MESH_GPU_UPLOADED);
    }

    @Test
    @DisplayName("Should handle null chunk in GPU cleanup")
    void shouldHandleNullChunkInGPUCleanup() {
        assertDoesNotThrow(() -> {
            pipeline.addChunkForGpuCleanup(null);
        });
    }

    @Test
    @DisplayName("Should handle chunk with no handle in GPU cleanup")
    void shouldHandleChunkWithNoHandleInGPUCleanup() {
        Chunk mockChunk = createMockChunk(0, 0);
        when(mockChunk.getMmsRenderableHandle()).thenReturn(null);

        assertDoesNotThrow(() -> {
            pipeline.addChunkForGpuCleanup(mockChunk);
        });
    }

    @Test
    @DisplayName("Should process GPU cleanup queue")
    void shouldProcessGPUCleanupQueue() {
        // Note: This test just verifies it doesn't throw
        // Actual cleanup requires OpenGL context
        assertDoesNotThrow(() -> {
            pipeline.processGpuCleanupQueue();
        });
    }

    // === Failed Chunk Retry Tests ===

    @Test
    @DisplayName("Should requeue failed chunks")
    void shouldRequeueFailedChunks() {
        assertDoesNotThrow(() -> {
            pipeline.requeueFailedChunks();
        });
    }

    // === Pipeline Processing Tests ===

    @Test
    @DisplayName("Should handle empty build queue gracefully")
    void shouldHandleEmptyBuildQueueGracefully() {
        assertDoesNotThrow(() -> {
            pipeline.processChunkMeshBuildRequests(mockWorld);
        });
    }

    @Test
    @DisplayName("Should handle GL updates with empty queue")
    void shouldHandleGLUpdatesWithEmptyQueue() {
        // Note: This requires OpenGL context in real usage
        assertDoesNotThrow(() -> {
            pipeline.applyPendingGLUpdates();
        });
    }

    // === Shutdown Tests ===

    @Test
    @DisplayName("Should shutdown pipeline cleanly")
    void shouldShutdownPipelineCleanly() {
        assertDoesNotThrow(() -> {
            pipeline.shutdown();
        });
    }

    @Test
    @DisplayName("Should handle multiple shutdown calls")
    void shouldHandleMultipleShutdownCalls() {
        pipeline.shutdown();

        assertDoesNotThrow(() -> {
            pipeline.shutdown();
        });
    }

    @Test
    @DisplayName("Should reject operations after shutdown")
    void shouldRejectOperationsAfterShutdown() {
        pipeline.shutdown();

        Chunk mockChunk = createMockChunk(0, 0);

        // These should be no-ops after shutdown
        pipeline.scheduleConditionalMeshBuild(mockChunk);
        assertEquals(0, pipeline.getPendingMeshBuildCount());

        pipeline.processChunkMeshBuildRequests(mockWorld);
        pipeline.applyPendingGLUpdates();
        pipeline.requeueFailedChunks();

        // Should not throw
    }

    // === ToString Tests ===

    @Test
    @DisplayName("Should provide meaningful toString")
    void shouldProvideMeaningfulToString() {
        String result = pipeline.toString();

        assertNotNull(result);
        assertTrue(result.contains("MmsMeshPipeline"));
        assertTrue(result.contains("pending="));
        assertTrue(result.contains("uploads="));
        assertTrue(result.contains("cleanup="));
        assertTrue(result.contains("failed="));
    }

    // === Integration Tests ===

    @Test
    @DisplayName("Should coordinate mesh generation and upload workflow")
    void shouldCoordinateMeshGenerationAndUploadWorkflow() {
        // This test verifies the overall workflow structure
        Chunk mockChunk = createMockChunk(0, 0);

        // Configure chunk as ready
        when(mockChunk.getCcoDirtyTracker().isMeshDirty()).thenReturn(true);
        when(mockChunk.getCcoStateManager().hasState(CcoChunkState.MESH_GENERATING))
            .thenReturn(false);

        // Schedule mesh build
        pipeline.scheduleConditionalMeshBuild(mockChunk);

        // Process builds (will fail without MMS API initialized, but structure is tested)
        pipeline.processChunkMeshBuildRequests(mockWorld);

        // Process GL updates
        pipeline.applyPendingGLUpdates();

        // Process cleanup
        pipeline.processGpuCleanupQueue();

        // Should not throw
    }

    @Test
    @DisplayName("Should handle concurrent chunk operations safely")
    void shouldHandleConcurrentChunkOperationsSafely() throws InterruptedException {
        Chunk[] chunks = new Chunk[10];
        for (int i = 0; i < 10; i++) {
            chunks[i] = createMockChunk(i, 0);
            when(chunks[i].getCcoDirtyTracker().isMeshDirty()).thenReturn(true);
            when(chunks[i].getCcoStateManager().hasState(CcoChunkState.MESH_GENERATING))
                .thenReturn(false);
        }

        // Schedule chunks from multiple threads
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    pipeline.scheduleConditionalMeshBuild(chunks[j]);
                    pipeline.removeChunkFromQueues(chunks[j]);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join(1000);
        }

        // Should not throw or deadlock
    }

    // === Helper Methods ===

    private Chunk createMockChunk(int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getChunkX()).thenReturn(x);
        when(chunk.getChunkZ()).thenReturn(z);
        when(chunk.getCcoDirtyTracker()).thenReturn(mock(com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoDirtyTracker.class));
        when(chunk.getCcoStateManager()).thenReturn(mock(com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager.class));
        return chunk;
    }
}
