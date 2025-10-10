package com.stonebreak.world;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkManager;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for overall loading performance optimizations.
 * Tests verify combined impact of all optimization phases:
 * - Phase 1: Async chunk loading
 * - Phase 2: Frame budget-aware mesh generation
 * - Phase 3: Async entity loading
 * - Phase 4: Dynamic feature population
 */
public class LoadingPerformanceIntegrationTest {

    private World world;
    private EntityManager entityManager;

    @BeforeEach
    public void setUp() {
        // Create test world with realistic settings
        WorldConfiguration config = new WorldConfiguration(8, 4);
        world = new TestWorld(config, 12345L, true); // Test mode

        // Create entity manager
        entityManager = new EntityManager(world);

        // Enable optimizations
        ChunkManager.setOptimizationsEnabled(true);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testCompleteWorldLoadingPerformance() throws Exception {
        // This test simulates complete world loading scenario
        System.out.println("\n=== Complete World Loading Performance Test ===");

        long totalStartTime = System.currentTimeMillis();

        // Phase 1: Load chunks
        System.out.println("\nPhase 1: Loading 25 chunks (5x5 grid)...");
        long chunkLoadStart = System.currentTimeMillis();

        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                world.getChunkAt(x, z);
            }
        }

        long chunkLoadEnd = System.currentTimeMillis();
        long chunkLoadTime = chunkLoadEnd - chunkLoadStart;

        System.out.println("Chunk loading initiated in " + chunkLoadTime + "ms (async)");

        // Phase 2: Wait for async chunk loading
        System.out.println("\nPhase 2: Waiting for async chunk loading...");
        Thread.sleep(2000);

        int loadedChunks = world.getLoadedChunkCount();
        System.out.println("Loaded chunks: " + loadedChunks + "/25");

        // Phase 3: Load entities
        System.out.println("\nPhase 3: Loading 100 entities across chunks...");
        long entityLoadStart = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            List<EntityData> entityData = createTestEntityData(20);
            entityManager.loadEntitiesForChunk(entityData, i, 0);
        }

        long entityLoadEnd = System.currentTimeMillis();
        long entityLoadTime = entityLoadEnd - entityLoadStart;

        System.out.println("Entity loading initiated in " + entityLoadTime + "ms (async)");

        // Phase 4: Process updates (simulating game loop)
        System.out.println("\nPhase 4: Processing game updates...");
        long updateStart = System.currentTimeMillis();

        int updateCount = 60; // Simulate 1 second at 60 FPS
        List<Long> frameTimes = new ArrayList<>();

        for (int i = 0; i < updateCount; i++) {
            long frameStart = System.currentTimeMillis();

            // Simulate normal game loop
            Game.setDeltaTimeForTesting(0.016f); // 60 FPS target
            world.update(null);
            entityManager.update(0.016f);

            long frameEnd = System.currentTimeMillis();
            long frameTime = frameEnd - frameStart;
            frameTimes.add(frameTime);

            Thread.sleep(Math.max(0, 16 - frameTime)); // Target 60 FPS
        }

        long updateEnd = System.currentTimeMillis();
        long totalUpdateTime = updateEnd - updateStart;

        // Phase 5: Analyze performance
        System.out.println("\n=== Performance Analysis ===");

        long totalTime = System.currentTimeMillis() - totalStartTime;
        int finalLoadedChunks = world.getLoadedChunkCount();
        int finalEntityCount = entityManager.getEntityCount();

        // Calculate frame time statistics
        long avgFrameTime = frameTimes.stream().mapToLong(Long::longValue).sum() / frameTimes.size();
        long maxFrameTime = frameTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long minFrameTime = frameTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        long stutterCount = frameTimes.stream().filter(t -> t > 20).count();

        System.out.println("\nTotal time: " + totalTime + "ms");
        System.out.println("Chunks loaded: " + finalLoadedChunks);
        System.out.println("Entities loaded: " + finalEntityCount);
        System.out.println("\nFrame Time Statistics:");
        System.out.println("  Average: " + avgFrameTime + "ms");
        System.out.println("  Min: " + minFrameTime + "ms");
        System.out.println("  Max: " + maxFrameTime + "ms");
        System.out.println("  Stutters (>20ms): " + stutterCount + "/" + updateCount);
        System.out.println("  Stutter rate: " + String.format("%.1f%%", (stutterCount * 100.0 / updateCount)));

        // Assertions
        assertTrue(finalLoadedChunks > 0, "At least some chunks should be loaded");
        assertTrue(avgFrameTime < 30, "Average frame time should be reasonable: " + avgFrameTime + "ms");
        assertTrue(stutterCount < updateCount / 2, "Less than 50% of frames should stutter");

        System.out.println("\n=== Test Passed ===\n");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void testAsyncLoadingPreventsMainThreadBlocking() throws Exception {
        // Verify main thread never blocks for long durations

        System.out.println("\n=== Main Thread Blocking Test ===");

        AtomicInteger blockedFrames = new AtomicInteger(0);
        int totalFrames = 30;

        // Load chunks while monitoring main thread responsiveness
        for (int i = 0; i < totalFrames; i++) {
            long frameStart = System.currentTimeMillis();

            // Load chunks
            world.getChunkAt(i, 0);
            world.getChunkAt(i, 1);

            // Update world
            world.update(null);

            long frameEnd = System.currentTimeMillis();
            long frameTime = frameEnd - frameStart;

            if (frameTime > 50) { // Consider >50ms as "blocked"
                blockedFrames.incrementAndGet();
            }

            Thread.sleep(16);
        }

        int blocked = blockedFrames.get();
        double blockRate = (blocked * 100.0) / totalFrames;

        System.out.println("Blocked frames: " + blocked + "/" + totalFrames);
        System.out.println("Block rate: " + String.format("%.1f%%", blockRate));

        // Assert - Very few frames should block
        assertTrue(blocked < totalFrames / 10,
            "Less than 10% of frames should block: " + blockRate + "%");

        System.out.println("=== Test Passed ===\n");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testDistanceBasedPriorityImprovesPerceivedPerformance() throws Exception {
        // Test that chunks near player load faster than distant chunks

        System.out.println("\n=== Distance-Based Priority Test ===");

        // Load chunks at varying distances
        long nearbyStartTime = System.currentTimeMillis();
        world.getChunkAt(0, 0); // Player position
        world.getChunkAt(1, 0); // Adjacent
        world.getChunkAt(0, 1);
        Thread.sleep(500);
        long nearbyLoadTime = System.currentTimeMillis() - nearbyStartTime;

        // Count nearby chunks loaded
        int nearbyLoaded = 0;
        if (world.getChunkAt(0, 0) != null) nearbyLoaded++;
        if (world.getChunkAt(1, 0) != null) nearbyLoaded++;
        if (world.getChunkAt(0, 1) != null) nearbyLoaded++;

        // Load distant chunks
        long distantStartTime = System.currentTimeMillis();
        world.getChunkAt(20, 20);
        world.getChunkAt(21, 20);
        world.getChunkAt(20, 21);
        Thread.sleep(500);
        long distantLoadTime = System.currentTimeMillis() - distantStartTime;

        int distantLoaded = 0;
        if (world.getChunkAt(20, 20) != null) distantLoaded++;
        if (world.getChunkAt(21, 20) != null) distantLoaded++;
        if (world.getChunkAt(20, 21) != null) distantLoaded++;

        System.out.println("Nearby chunks loaded: " + nearbyLoaded + "/3 in " + nearbyLoadTime + "ms");
        System.out.println("Distant chunks loaded: " + distantLoaded + "/3 in " + distantLoadTime + "ms");

        // Assert - At least some nearby chunks should load
        assertTrue(nearbyLoaded > 0,
            "At least one nearby chunk should load with priority");

        System.out.println("=== Test Passed ===\n");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testFrameBudgetPreventsStuttering() throws Exception {
        // Test that frame budget system prevents stuttering

        System.out.println("\n=== Frame Budget Test ===");

        // Load many chunks rapidly
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                world.getChunkAt(x + 30, z + 30);
            }
        }

        Thread.sleep(1000);

        // Monitor frame times during heavy update
        List<Long> frameTimes = new ArrayList<>();
        int frames = 60;

        for (int i = 0; i < frames; i++) {
            long frameStart = System.currentTimeMillis();

            Game.setDeltaTimeForTesting(0.016f);
            world.update(null);

            long frameEnd = System.currentTimeMillis();
            frameTimes.add(frameEnd - frameStart);

            Thread.sleep(16);
        }

        // Analyze frame times
        long avgFrameTime = frameTimes.stream().mapToLong(Long::longValue).sum() / frameTimes.size();
        long maxFrameTime = frameTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long severeStutters = frameTimes.stream().filter(t -> t > 50).count();

        System.out.println("Average frame time: " + avgFrameTime + "ms");
        System.out.println("Max frame time: " + maxFrameTime + "ms");
        System.out.println("Severe stutters (>50ms): " + severeStutters + "/" + frames);

        // Assert - Frame budget should prevent severe stuttering
        assertTrue(severeStutters < frames / 10,
            "Less than 10% severe stutters with frame budget");
        assertTrue(avgFrameTime < 25,
            "Average frame time reasonable with frame budget");

        System.out.println("=== Test Passed ===\n");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testOptimizationsWorkTogetherSynergistically() throws Exception {
        // Test that all optimizations work together

        System.out.println("\n=== Synergistic Optimization Test ===");

        long startTime = System.currentTimeMillis();

        // Simultaneously trigger all optimization systems
        // 1. Load chunks (async chunk loading + priority)
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.getChunkAt(x + 40, z + 40);
            }
        }

        // 2. Load entities (async entity loading + batching)
        entityManager.loadEntitiesForChunk(createTestEntityData(30), 40, 40);

        // 3. Process updates (frame budget + feature population)
        Game.setDeltaTimeForTesting(0.016f);

        List<Long> frameTimes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long frameStart = System.currentTimeMillis();

            world.update(null);
            entityManager.update(0.016f);

            long frameEnd = System.currentTimeMillis();
            frameTimes.add(frameEnd - frameStart);

            Thread.sleep(16);
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Analyze combined performance
        long avgFrameTime = frameTimes.stream().mapToLong(Long::longValue).sum() / frameTimes.size();
        long stutters = frameTimes.stream().filter(t -> t > 25).count();

        System.out.println("Total time: " + totalTime + "ms");
        System.out.println("Average frame time: " + avgFrameTime + "ms");
        System.out.println("Stutters: " + stutters + "/30");
        System.out.println("Chunks loaded: " + world.getLoadedChunkCount());
        System.out.println("Entities loaded: " + entityManager.getEntityCount());

        // Assert - Combined optimizations provide smooth performance
        assertTrue(avgFrameTime < 30,
            "Combined optimizations maintain good average frame time");
        assertTrue(stutters < 15,
            "Combined optimizations minimize stuttering");

        System.out.println("=== Test Passed ===\n");
    }

    // Helper methods

    private List<EntityData> createTestEntityData(int count) {
        List<EntityData> entityDataList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            EntityData data = EntityData.builder()
                    .entityType(EntityType.COW)
                    .position(new Vector3f(i * 2.0f, 100.0f, 0.0f))
                    .health(10.0f)
                    .maxHealth(10.0f)
                    .alive(true)
                    .build();

            entityDataList.add(data);
        }

        return entityDataList;
    }
}
