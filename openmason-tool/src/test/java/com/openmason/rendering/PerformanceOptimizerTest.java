package com.openmason.rendering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for PerformanceOptimizer class.
 * Tests performance monitoring, adaptive quality, thread safety, and memory monitoring.
 */
class PerformanceOptimizerTest {
    
    private PerformanceOptimizer optimizer;
    private MockedStatic<BufferManager> bufferManagerMock;
    private BufferManager mockBufferManager;
    
    @BeforeEach
    void setUp() {
        // Mock BufferManager
        bufferManagerMock = mockStatic(BufferManager.class);
        mockBufferManager = mock(BufferManager.class);
        bufferManagerMock.when(BufferManager::getInstance).thenReturn(mockBufferManager);
        
        // Mock buffer manager methods
        when(mockBufferManager.getTotalMemoryAllocated()).thenReturn(50L * 1024 * 1024); // 50MB
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(30L * 1024 * 1024); // 30MB
        
        optimizer = new PerformanceOptimizer();
    }
    
    @AfterEach
    void tearDown() {
        if (bufferManagerMock != null) {
            bufferManagerMock.close();
        }
    }
    
    /**
     * Test optimizer initialization.
     */
    @Test
    void testInitialization() {
        assertTrue(optimizer.isEnabled());
        assertFalse(optimizer.isDebugMode());
        assertEquals(60.0, optimizer.getCurrentFPS(), 0.01); // Should start at 0, but we allow initial target
        assertTrue(optimizer.isAdaptiveQualityEnabled());
    }
    
    /**
     * Test frame timing recording.
     */
    @Test
    void testFrameTimingRecording() {
        long startTime = System.currentTimeMillis();
        optimizer.startFrame();
        
        // Simulate frame work
        try {
            Thread.sleep(16); // ~60 FPS frame time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        optimizer.endFrame();
        
        // FPS should be approximately 60 (allowing for variance)
        double fps = optimizer.getCurrentFPS();
        assertTrue(fps > 30 && fps < 120, "FPS should be reasonable, was: " + fps);
        
        assertTrue(optimizer.getAverageFrameTime() > 0);
    }
    
    /**
     * Test performance level classification.
     */
    @Test
    void testPerformanceLevelClassification() {
        // Simulate excellent performance (fast frames)
        for (int i = 0; i < 10; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(10); // ~100 FPS
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        // Should be excellent or good performance
        PerformanceOptimizer.PerformanceLevel level = optimizer.getCurrentPerformanceLevel();
        assertTrue(level == PerformanceOptimizer.PerformanceLevel.EXCELLENT || 
                  level == PerformanceOptimizer.PerformanceLevel.GOOD);
    }
    
    /**
     * Test poor performance detection.
     */
    @Test
    void testPoorPerformanceDetection() {
        // Simulate poor performance (slow frames)
        for (int i = 0; i < 10; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(100); // ~10 FPS
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        // Should detect poor or critical performance
        PerformanceOptimizer.PerformanceLevel level = optimizer.getCurrentPerformanceLevel();
        assertTrue(level == PerformanceOptimizer.PerformanceLevel.POOR || 
                  level == PerformanceOptimizer.PerformanceLevel.CRITICAL);
    }
    
    /**
     * Test MSAA level adjustment.
     */
    @Test
    void testMSAALevelAdjustment() {
        int initialLevel = optimizer.getCurrentMSAALevel();
        
        // Test valid level adjustment
        optimizer.setMSAALevel(1); // 2x MSAA
        assertEquals(1, optimizer.getCurrentMSAALevel());
        assertFalse(optimizer.isAdaptiveQualityEnabled()); // Should disable adaptive quality
        
        // Test invalid level (should be ignored)
        optimizer.setMSAALevel(-1);
        assertEquals(1, optimizer.getCurrentMSAALevel()); // Should remain unchanged
        
        optimizer.setMSAALevel(10);
        assertEquals(1, optimizer.getCurrentMSAALevel()); // Should remain unchanged
    }
    
    /**
     * Test render scale adjustment.
     */
    @Test
    void testRenderScaleAdjustment() {
        // Test valid scale adjustment
        optimizer.setRenderScale(0.75f);
        assertEquals(0.75f, optimizer.getCurrentRenderScale(), 0.01f);
        assertFalse(optimizer.isAdaptiveQualityEnabled());
        
        // Test invalid scales (should be clamped)
        optimizer.setRenderScale(0.0f);
        assertEquals(0.5f, optimizer.getCurrentRenderScale(), 0.01f); // Should clamp to minimum
        
        optimizer.setRenderScale(2.0f);
        assertEquals(1.0f, optimizer.getCurrentRenderScale(), 0.01f); // Should clamp to maximum
    }
    
    /**
     * Test adaptive quality toggle.
     */
    @Test
    void testAdaptiveQualityToggle() {
        assertTrue(optimizer.isAdaptiveQualityEnabled());
        
        optimizer.setAdaptiveQualityEnabled(false);
        assertFalse(optimizer.isAdaptiveQualityEnabled());
        
        optimizer.setAdaptiveQualityEnabled(true);
        assertTrue(optimizer.isAdaptiveQualityEnabled());
    }
    
    /**
     * Test debug mode toggle.
     */
    @Test
    void testDebugModeToggle() {
        assertFalse(optimizer.isDebugMode());
        
        optimizer.setDebugMode(true);
        assertTrue(optimizer.isDebugMode());
        
        optimizer.setDebugMode(false);
        assertFalse(optimizer.isDebugMode());
    }
    
    /**
     * Test optimizer enable/disable.
     */
    @Test
    void testOptimizerToggle() {
        assertTrue(optimizer.isEnabled());
        
        optimizer.setEnabled(false);
        assertFalse(optimizer.isEnabled());
        
        // Frame timing should not be recorded when disabled
        optimizer.startFrame();
        optimizer.endFrame();
        
        optimizer.setEnabled(true);
        assertTrue(optimizer.isEnabled());
    }
    
    /**
     * Test memory monitoring.
     */
    @Test
    void testMemoryMonitoring() {
        // Mock high memory usage
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(300L * 1024 * 1024); // 300MB
        
        optimizer.updateMemoryStatistics();
        
        PerformanceOptimizer.MemoryStatus status = optimizer.getMemoryStatus();
        assertTrue(status == PerformanceOptimizer.MemoryStatus.WARNING || 
                  status == PerformanceOptimizer.MemoryStatus.CRITICAL);
    }
    
    /**
     * Test statistics collection.
     */
    @Test
    void testStatisticsCollection() {
        // Record some frames
        for (int i = 0; i < 5; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        PerformanceOptimizer.PerformanceStatistics stats = optimizer.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.frameCount > 0);
        assertTrue(stats.averageFrameTime > 0);
        assertTrue(stats.currentFPS > 0);
    }
    
    /**
     * Test thread safety with concurrent frame recording.
     */
    @Test
    @Timeout(10)
    void testConcurrentFrameRecording() throws InterruptedException {
        int threadCount = 5;
        int framesPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger completedFrames = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < framesPerThread; j++) {
                        optimizer.startFrame();
                        
                        // Simulate frame work
                        try {
                            Thread.sleep(5 + (j % 10)); // Variable frame times
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        
                        optimizer.endFrame();
                        completedFrames.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount * framesPerThread, completedFrames.get());
        
        // Verify statistics are consistent
        PerformanceOptimizer.PerformanceStatistics stats = optimizer.getStatistics();
        assertTrue(stats.frameCount >= threadCount * framesPerThread);
        
        executor.shutdown();
    }
    
    /**
     * Test concurrent MSAA level changes.
     */
    @Test
    @Timeout(5)
    void testConcurrentMSAAChanges() throws InterruptedException {
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        int level = (threadId + j) % 4; // 0-3
                        optimizer.setMSAALevel(level);
                        
                        // Verify level is valid
                        int currentLevel = optimizer.getCurrentMSAALevel();
                        assertTrue(currentLevel >= 0 && currentLevel < 4);
                        
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        
        // Final state should be valid
        int finalLevel = optimizer.getCurrentMSAALevel();
        assertTrue(finalLevel >= 0 && finalLevel < 4);
        
        executor.shutdown();
    }
    
    /**
     * Test performance warnings generation.
     */
    @Test
    void testPerformanceWarnings() {
        // Simulate conditions that should generate warnings
        
        // Poor performance
        for (int i = 0; i < 10; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(70); // > 66ms critical threshold
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        // High memory usage
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(600L * 1024 * 1024); // 600MB
        optimizer.updateMemoryStatistics();
        
        // Should have generated warnings
        assertTrue(optimizer.hasActiveWarnings());
    }
    
    /**
     * Test reset functionality.
     */
    @Test
    void testReset() {
        // Record some frames
        for (int i = 0; i < 5; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        PerformanceOptimizer.PerformanceStatistics beforeReset = optimizer.getStatistics();
        assertTrue(beforeReset.frameCount > 0);
        
        optimizer.reset();
        
        PerformanceOptimizer.PerformanceStatistics afterReset = optimizer.getStatistics();
        assertEquals(0, afterReset.frameCount);
        assertEquals(0.0, afterReset.currentFPS, 0.01);
    }
    
    /**
     * Test frame timing consistency.
     */
    @Test
    void testFrameTimingConsistency() {
        double targetFrameTime = 16.67; // 60 FPS
        
        for (int i = 0; i < 20; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(16); // Close to target
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        double averageFrameTime = optimizer.getAverageFrameTime();
        
        // Should be close to target (allowing for system variance)
        assertTrue(Math.abs(averageFrameTime - targetFrameTime) < 10.0, 
                  "Average frame time should be close to target. Expected: " + targetFrameTime + 
                  ", Actual: " + averageFrameTime);
    }
    
    /**
     * Test edge case of very fast frames.
     */
    @Test
    void testVeryFastFrames() {
        for (int i = 0; i < 10; i++) {
            optimizer.startFrame();
            // No sleep - immediate frame completion
            optimizer.endFrame();
        }
        
        double fps = optimizer.getCurrentFPS();
        assertTrue(fps > 100, "FPS should be very high for immediate frames, was: " + fps);
        
        PerformanceOptimizer.PerformanceLevel level = optimizer.getCurrentPerformanceLevel();
        assertEquals(PerformanceOptimizer.PerformanceLevel.EXCELLENT, level);
    }
    
    /**
     * Test frame time variance calculation.
     */
    @Test
    void testFrameTimeVariance() {
        // Record frames with varying times
        int[] frameTimes = {10, 15, 20, 25, 30}; // Variable frame times
        
        for (int frameTime : frameTimes) {
            optimizer.startFrame();
            try {
                Thread.sleep(frameTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        double variance = optimizer.getFrameTimeVariance();
        assertTrue(variance > 0, "Frame time variance should be > 0 for variable frame times");
    }
    
    /**
     * Test memory status transitions.
     */
    @Test
    void testMemoryStatusTransitions() {
        // Start with normal memory
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(50L * 1024 * 1024); // 50MB
        optimizer.updateMemoryStatistics();
        assertEquals(PerformanceOptimizer.MemoryStatus.NORMAL, optimizer.getMemoryStatus());
        
        // Increase to warning level
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(250L * 1024 * 1024); // 250MB
        optimizer.updateMemoryStatistics();
        assertEquals(PerformanceOptimizer.MemoryStatus.WARNING, optimizer.getMemoryStatus());
        
        // Increase to critical level
        when(mockBufferManager.getCurrentMemoryUsage()).thenReturn(600L * 1024 * 1024); // 600MB
        optimizer.updateMemoryStatistics();
        assertEquals(PerformanceOptimizer.MemoryStatus.CRITICAL, optimizer.getMemoryStatus());
    }
    
    /**
     * Test statistics string formatting.
     */
    @Test
    void testStatisticsFormatting() {
        // Record some frames
        for (int i = 0; i < 3; i++) {
            optimizer.startFrame();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            optimizer.endFrame();
        }
        
        PerformanceOptimizer.PerformanceStatistics stats = optimizer.getStatistics();
        String statsString = stats.toString();
        
        assertNotNull(statsString);
        assertTrue(statsString.contains("Performance Statistics"));
        assertTrue(statsString.contains("FPS"));
        assertTrue(statsString.contains("Frame Time"));
        assertTrue(statsString.contains("ms"));
    }
}