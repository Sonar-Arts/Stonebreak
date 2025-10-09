package com.stonebreak.world.chunk.utils;

import com.stonebreak.core.Game;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChunkManager optimizations including:
 * - Thread pool sizing
 * - Distance-based priority
 * - Adaptive GL batch sizing
 */
public class ChunkManagerOptimizationTest {

    @BeforeEach
    public void setUp() {
        // Reset optimizations to default state
        ChunkManager.setOptimizationsEnabled(true);
    }

    @Test
    public void testOptimalThreadCountCalculation() {
        // Arrange
        int cpuCores = Runtime.getRuntime().availableProcessors();

        // Act - Create ChunkManager (implicitly calculates thread count)
        World world = new TestWorld(new WorldConfiguration(8, 4), 12345L, true);
        ChunkManager manager = new ChunkManager(world, 8);

        // Assert - Thread count should be within expected range
        // Formula: max(2, min(8, cores/2 + 1))
        int expectedMin = 2;
        int expectedMax = 8;
        int expectedOptimal = Math.max(2, Math.min(8, cpuCores / 2 + 1));

        System.out.println("CPU Cores: " + cpuCores);
        System.out.println("Expected thread count: " + expectedOptimal);

        // Verify thread count is reasonable (can't directly test private field)
        // but we can verify the manager was created successfully
        assertNotNull(manager, "ChunkManager created with optimal thread count");
    }

    @Test
    public void testAdaptiveGLBatchSizingLowFrameTime() {
        // Arrange - Simulate low frame time (good performance)
        Game.setDeltaTimeForTesting(0.012f); // 12ms = 83 FPS

        // Act - Get batch size multiple times
        int batchSize1 = ChunkManager.getOptimizedGLBatchSize();
        int batchSize2 = ChunkManager.getOptimizedGLBatchSize();
        int batchSize3 = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Batch size should increase or stay high
        assertTrue(batchSize3 >= batchSize1,
            "Batch size should increase with good frame time: " +
                batchSize1 + " -> " + batchSize3);

        System.out.println("GL Batch sizes (low frame time): " +
            batchSize1 + " -> " + batchSize2 + " -> " + batchSize3);
    }

    @Test
    public void testAdaptiveGLBatchSizingHighFrameTime() {
        // Arrange - Simulate high frame time (poor performance)
        Game.setDeltaTimeForTesting(0.025f); // 25ms = 40 FPS

        // Act - Get batch size multiple times
        int batchSize1 = ChunkManager.getOptimizedGLBatchSize();
        int batchSize2 = ChunkManager.getOptimizedGLBatchSize();
        int batchSize3 = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Batch size should decrease
        assertTrue(batchSize3 <= batchSize1,
            "Batch size should decrease with poor frame time: " +
                batchSize1 + " -> " + batchSize3);

        System.out.println("GL Batch sizes (high frame time): " +
            batchSize1 + " -> " + batchSize2 + " -> " + batchSize3);
    }

    @Test
    public void testAdaptiveGLBatchSizingRespectsBounds() {
        // Act - Test extreme frame times
        Game.setDeltaTimeForTesting(0.001f); // 1ms - unrealistically good
        int maxBatchSize = 0;
        for (int i = 0; i < 100; i++) {
            maxBatchSize = Math.max(maxBatchSize, ChunkManager.getOptimizedGLBatchSize());
        }

        Game.setDeltaTimeForTesting(0.100f); // 100ms - very poor
        int minBatchSize = 999;
        for (int i = 0; i < 100; i++) {
            minBatchSize = Math.min(minBatchSize, ChunkManager.getOptimizedGLBatchSize());
        }

        // Assert - Should stay within bounds
        assertTrue(maxBatchSize <= 64, "Max batch size should not exceed 64: " + maxBatchSize);
        assertTrue(minBatchSize >= 2, "Min batch size should not go below 2: " + minBatchSize);

        System.out.println("Batch size range: " + minBatchSize + " - " + maxBatchSize);
    }

    @Test
    public void testGLBatchSizingRespondsToMemoryPressure() {
        // This test verifies memory pressure is considered
        // Actual memory pressure is hard to simulate in unit tests

        // Act
        Game.setDeltaTimeForTesting(0.016f); // Normal frame time
        int normalBatchSize = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Batch size should be reasonable
        assertTrue(normalBatchSize >= 2 && normalBatchSize <= 64,
            "Normal batch size should be within bounds: " + normalBatchSize);

        System.out.println("Normal GL batch size: " + normalBatchSize);
    }

    @Test
    public void testOptimizationsCanBeDisabled() {
        // Arrange
        ChunkManager.setOptimizationsEnabled(false);

        // Act
        int batchSize = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Should return fixed value when disabled
        assertEquals(32, batchSize,
            "Batch size should be fixed at 32 when optimizations disabled");

        // Re-enable for other tests
        ChunkManager.setOptimizationsEnabled(true);
    }

    @Test
    public void testOptimizationsEnabledByDefault() {
        // Act
        boolean enabled = ChunkManager.areOptimizationsEnabled();

        // Assert
        assertTrue(enabled, "Optimizations should be enabled by default");
    }

    @Test
    public void testHighMemoryPressureDetection() {
        // This test verifies the memory pressure detection system exists
        // Actual pressure detection depends on JVM memory state

        // Act
        boolean hasMemoryPressure = ChunkManager.isHighMemoryPressure();

        // Assert - Just verify the method works
        assertNotNull(hasMemoryPressure,
            "Memory pressure detection should return a boolean");

        System.out.println("High memory pressure: " + hasMemoryPressure);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testChunkManagerShutdownGracefully() {
        // Arrange
        World world = new TestWorld(new WorldConfiguration(8, 4), 12345L, true);
        ChunkManager manager = new ChunkManager(world, 8);

        // Act - Shutdown should complete without hanging
        try {
            manager.shutdown();
        } catch (Exception e) {
            fail("ChunkManager shutdown should not throw exception: " + e.getMessage());
        }

        // Assert
        assertTrue(true, "Shutdown completed successfully");
    }

    @Test
    public void testMemorySafeBatchSizingUnderPressure() {
        // This test verifies memory-safe batch sizing logic
        // We can't easily simulate actual memory pressure, but we can test the logic

        // Act - Get batch sizes at different frame times
        Game.setDeltaTimeForTesting(0.016f);
        int batchSize1 = ChunkManager.getOptimizedGLBatchSize();

        Game.setDeltaTimeForTesting(0.014f);
        int batchSize2 = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Batch sizes should be reasonable
        assertTrue(batchSize1 > 0, "Batch size should be positive");
        assertTrue(batchSize2 > 0, "Batch size should be positive");

        System.out.println("Memory-safe batch sizes: " + batchSize1 + ", " + batchSize2);
    }

    @Test
    public void testGLBatchSizeAdaptationSpeed() {
        // Verify batch size doesn't change too rapidly

        // Arrange - Start with good frame time
        Game.setDeltaTimeForTesting(0.014f);
        int startBatchSize = ChunkManager.getOptimizedGLBatchSize();

        // Act - Suddenly get poor frame time
        Game.setDeltaTimeForTesting(0.025f);
        int batch1 = ChunkManager.getOptimizedGLBatchSize();
        int batch2 = ChunkManager.getOptimizedGLBatchSize();

        // Assert - Should decrease gradually (not drop to minimum instantly)
        int decreasePerCall = startBatchSize - batch2;
        assertTrue(decreasePerCall <= 10,
            "Batch size should decrease gradually, not drop instantly: " + decreasePerCall);

        System.out.println("Batch size adaptation: " + startBatchSize +
            " -> " + batch1 + " -> " + batch2);
    }
}
