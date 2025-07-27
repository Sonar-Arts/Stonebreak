package com.openmason.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for VectorPool class.
 * Tests object pooling, thread safety, performance characteristics, and resource management.
 */
class VectorPoolTest {
    
    @BeforeEach
    void setUp() {
        // Clear pool before each test to ensure clean state
        VectorPool.clear();
    }
    
    @AfterEach
    void tearDown() {
        // Clear pool after each test to prevent interference
        VectorPool.clear();
    }
    
    /**
     * Test basic Vector3f acquisition and release.
     */
    @Test
    void testBasicVector3fOperations() {
        Vector3f vector = VectorPool.acquireVector3f();
        
        assertNotNull(vector);
        assertEquals(0.0f, vector.x);
        assertEquals(0.0f, vector.y);
        assertEquals(0.0f, vector.z);
        
        // Modify vector
        vector.set(1.0f, 2.0f, 3.0f);
        
        // Release it
        VectorPool.release(vector);
        
        // Acquire another - should be reset to zero
        Vector3f vector2 = VectorPool.acquireVector3f();
        assertEquals(0.0f, vector2.x);
        assertEquals(0.0f, vector2.y);
        assertEquals(0.0f, vector2.z);
        
        VectorPool.release(vector2);
    }
    
    /**
     * Test Vector3f acquisition with initial values.
     */
    @Test
    void testVector3fWithInitialValues() {
        Vector3f vector = VectorPool.acquireVector3f(5.0f, 10.0f, 15.0f);
        
        assertEquals(5.0f, vector.x);
        assertEquals(10.0f, vector.y);
        assertEquals(15.0f, vector.z);
        
        VectorPool.release(vector);
    }
    
    /**
     * Test Vector3f acquisition with copy constructor.
     */
    @Test
    void testVector3fWithCopyConstructor() {
        Vector3f source = new Vector3f(7.0f, 8.0f, 9.0f);
        Vector3f copy = VectorPool.acquireVector3f(source);
        
        assertEquals(7.0f, copy.x);
        assertEquals(8.0f, copy.y);
        assertEquals(9.0f, copy.z);
        
        // Modify copy to ensure independence
        copy.set(1.0f, 2.0f, 3.0f);
        
        // Source should be unchanged
        assertEquals(7.0f, source.x);
        assertEquals(8.0f, source.y);
        assertEquals(9.0f, source.z);
        
        VectorPool.release(copy);
    }
    
    /**
     * Test basic Matrix4f acquisition and release.
     */
    @Test
    void testBasicMatrix4fOperations() {
        Matrix4f matrix = VectorPool.acquireMatrix4f();
        
        assertNotNull(matrix);
        assertTrue(matrix.isIdentity());
        
        // Modify matrix
        matrix.scale(2.0f);
        assertFalse(matrix.isIdentity());
        
        // Release it
        VectorPool.release(matrix);
        
        // Acquire another - should be reset to identity
        Matrix4f matrix2 = VectorPool.acquireMatrix4f();
        assertTrue(matrix2.isIdentity());
        
        VectorPool.release(matrix2);
    }
    
    /**
     * Test pool reuse efficiency.
     */
    @Test
    void testPoolReuseEfficiency() {
        VectorPool.PoolStatistics initialStats = VectorPool.getStatistics();
        
        // Acquire and release multiple vectors
        for (int i = 0; i < 100; i++) {
            Vector3f vector = VectorPool.acquireVector3f();
            VectorPool.release(vector);
        }
        
        VectorPool.PoolStatistics finalStats = VectorPool.getStatistics();
        
        // Should have high efficiency (most acquisitions should reuse objects)
        assertTrue(finalStats.getVector3fEfficiency() > 0.8, 
                  "Vector3f pool efficiency should be > 80%, was: " + finalStats.getVector3fEfficiency());
        
        assertEquals(100, finalStats.vector3fAcquired - initialStats.vector3fAcquired);
        assertEquals(100, finalStats.vector3fReleased - initialStats.vector3fReleased);
    }
    
    /**
     * Test withVectors convenience method.
     */
    @Test
    void testWithVectorsConvenience() {
        AtomicInteger operationCalled = new AtomicInteger(0);
        
        VectorPool.withVectors((v1, v2, v3) -> {
            operationCalled.incrementAndGet();
            
            // Verify all vectors are available and zeroed
            assertNotNull(v1);
            assertNotNull(v2);
            assertNotNull(v3);
            
            assertEquals(0.0f, v1.x);
            assertEquals(0.0f, v2.x);
            assertEquals(0.0f, v3.x);
            
            // Use vectors
            v1.set(1.0f, 2.0f, 3.0f);
            v2.set(4.0f, 5.0f, 6.0f);
            v3.set(v1).add(v2);
            
            assertEquals(5.0f, v3.x);
            assertEquals(7.0f, v3.y);
            assertEquals(9.0f, v3.z);
        });
        
        assertEquals(1, operationCalled.get());
        
        // Vectors should be automatically released
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        assertEquals(3, stats.vector3fReleased);
    }
    
    /**
     * Test withMatrices convenience method.
     */
    @Test
    void testWithMatricesConvenience() {
        AtomicInteger operationCalled = new AtomicInteger(0);
        
        VectorPool.withMatrices((m1, m2) -> {
            operationCalled.incrementAndGet();
            
            assertNotNull(m1);
            assertNotNull(m2);
            
            assertTrue(m1.isIdentity());
            assertTrue(m2.isIdentity());
            
            // Use matrices
            m1.scale(2.0f);
            m2.translate(1.0f, 2.0f, 3.0f);
            
            assertFalse(m1.isIdentity());
            assertFalse(m2.isIdentity());
        });
        
        assertEquals(1, operationCalled.get());
        
        // Matrices should be automatically released
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        assertEquals(2, stats.matrix4fReleased);
    }
    
    /**
     * Test null handling in release methods.
     */
    @Test
    void testNullHandling() {
        // Should not throw exceptions
        assertDoesNotThrow(() -> {
            VectorPool.release((Vector3f) null);
            VectorPool.release((Matrix4f) null);
        });
        
        // Statistics should not change
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        assertEquals(0, stats.vector3fReleased);
        assertEquals(0, stats.matrix4fReleased);
    }
    
    /**
     * Test pool size limits and overflow handling.
     */
    @Test
    void testPoolSizeManagement() {
        // Fill pool beyond max size
        Vector3f[] vectors = new Vector3f[250]; // Exceeds MAX_POOL_SIZE of 200
        
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = VectorPool.acquireVector3f();
        }
        
        // Release all vectors
        for (Vector3f vector : vectors) {
            VectorPool.release(vector);
        }
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        
        // Pool size should be limited
        assertTrue(stats.vector3fPoolSize <= 200, 
                  "Pool size should be limited to 200, was: " + stats.vector3fPoolSize);
        
        assertEquals(250, stats.vector3fReleased);
    }
    
    /**
     * Test pool statistics accuracy.
     */
    @Test
    void testStatisticsAccuracy() {
        VectorPool.PoolStatistics initialStats = VectorPool.getStatistics();
        
        // Acquire some vectors and matrices
        Vector3f v1 = VectorPool.acquireVector3f();
        Vector3f v2 = VectorPool.acquireVector3f();
        Matrix4f m1 = VectorPool.acquireMatrix4f();
        
        // Release some
        VectorPool.release(v1);
        VectorPool.release(m1);
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        
        assertEquals(2, stats.vector3fAcquired - initialStats.vector3fAcquired);
        assertEquals(1, stats.vector3fReleased - initialStats.vector3fReleased);
        assertEquals(1, stats.matrix4fAcquired - initialStats.matrix4fAcquired);
        assertEquals(1, stats.matrix4fReleased - initialStats.matrix4fReleased);
        
        // Clean up
        VectorPool.release(v2);
    }
    
    /**
     * Test efficiency calculation in statistics.
     */
    @Test
    void testEfficiencyCalculation() {
        // First acquisition should create new objects (efficiency = 0)
        Vector3f v1 = VectorPool.acquireVector3f();
        VectorPool.PoolStatistics stats1 = VectorPool.getStatistics();
        assertEquals(0.0, stats1.getVector3fEfficiency(), 0.01);
        
        // Release and reacquire should reuse (efficiency > 0)
        VectorPool.release(v1);
        Vector3f v2 = VectorPool.acquireVector3f();
        VectorPool.PoolStatistics stats2 = VectorPool.getStatistics();
        assertTrue(stats2.getVector3fEfficiency() > 0.4);
        
        VectorPool.release(v2);
    }
    
    /**
     * Test thread safety with concurrent operations.
     */
    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Test Vector3f operations
                        Vector3f vector = VectorPool.acquireVector3f();
                        vector.set(j, j + 1, j + 2);
                        VectorPool.release(vector);
                        
                        // Test Matrix4f operations
                        Matrix4f matrix = VectorPool.acquireMatrix4f();
                        matrix.scale(1.5f);
                        VectorPool.release(matrix);
                        
                        // Test convenience methods
                        VectorPool.withVectors((v1, v2, v3) -> {
                            v1.set(1, 2, 3);
                            v2.set(v1);
                            v3.add(v1, v2);
                        });
                    }
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount, successCount.get());
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        
        // Verify all operations completed correctly
        assertEquals(threadCount * operationsPerThread * 2, stats.vector3fAcquired); // *2 for direct + withVectors
        assertEquals(threadCount * operationsPerThread * 2, stats.vector3fReleased);
        assertEquals(threadCount * operationsPerThread, stats.matrix4fAcquired);
        assertEquals(threadCount * operationsPerThread, stats.matrix4fReleased);
        
        executor.shutdown();
    }
    
    /**
     * Test pool behavior under memory pressure.
     */
    @Test
    void testMemoryPressureHandling() {
        // Simulate memory pressure by acquiring many objects without releasing
        Vector3f[] vectors = new Vector3f[1000];
        
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = VectorPool.acquireVector3f();
        }
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        
        // All should be new creations (no reuse possible)
        assertEquals(1000, stats.vector3fCreated);
        assertEquals(1000, stats.vector3fAcquired);
        assertEquals(0, stats.vector3fReleased);
        
        // Release all
        for (Vector3f vector : vectors) {
            VectorPool.release(vector);
        }
        
        // Pool should limit retention
        VectorPool.PoolStatistics finalStats = VectorPool.getStatistics();
        assertTrue(finalStats.vector3fPoolSize <= 200); // MAX_POOL_SIZE
    }
    
    /**
     * Test pool cleanup functionality.
     */
    @Test
    void testPoolCleanup() {
        // Fill pool
        for (int i = 0; i < 100; i++) {
            Vector3f vector = VectorPool.acquireVector3f();
            VectorPool.release(vector);
        }
        
        VectorPool.PoolStatistics beforeClear = VectorPool.getStatistics();
        assertTrue(beforeClear.vector3fPoolSize > 0);
        
        VectorPool.clear();
        
        VectorPool.PoolStatistics afterClear = VectorPool.getStatistics();
        assertEquals(0, afterClear.vector3fPoolSize);
        assertEquals(0, afterClear.matrix4fPoolSize);
        assertEquals(0, afterClear.vector3fCreated);
        assertEquals(0, afterClear.vector3fAcquired);
        assertEquals(0, afterClear.vector3fReleased);
    }
    
    /**
     * Test statistics toString formatting.
     */
    @Test
    void testStatisticsToString() {
        Vector3f vector = VectorPool.acquireVector3f();
        VectorPool.release(vector);
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        String statsString = stats.toString();
        
        assertTrue(statsString.contains("VectorPool Statistics"));
        assertTrue(statsString.contains("Vector3f"));
        assertTrue(statsString.contains("Matrix4f"));
        assertTrue(statsString.contains("efficiency"));
        assertTrue(statsString.contains("pool="));
        assertTrue(statsString.contains("created="));
        assertTrue(statsString.contains("acquired="));
        assertTrue(statsString.contains("released="));
    }
    
    /**
     * Repeated test to verify consistent behavior.
     */
    @RepeatedTest(5)
    void testConsistentBehavior() {
        Vector3f vector = VectorPool.acquireVector3f(10.0f, 20.0f, 30.0f);
        
        assertEquals(10.0f, vector.x);
        assertEquals(20.0f, vector.y);
        assertEquals(30.0f, vector.z);
        
        VectorPool.release(vector);
        
        // Next acquisition should be zeroed
        Vector3f nextVector = VectorPool.acquireVector3f();
        assertEquals(0.0f, nextVector.x);
        assertEquals(0.0f, nextVector.y);
        assertEquals(0.0f, nextVector.z);
        
        VectorPool.release(nextVector);
    }
    
    /**
     * Test edge case of empty pool operations.
     */
    @Test
    void testEmptyPoolOperations() {
        VectorPool.clear();
        
        // Operations on empty pool should still work
        Vector3f vector = VectorPool.acquireVector3f();
        assertNotNull(vector);
        
        Matrix4f matrix = VectorPool.acquireMatrix4f();
        assertNotNull(matrix);
        
        VectorPool.release(vector);
        VectorPool.release(matrix);
        
        VectorPool.PoolStatistics stats = VectorPool.getStatistics();
        assertEquals(1, stats.vector3fCreated);
        assertEquals(1, stats.matrix4fCreated);
    }
}