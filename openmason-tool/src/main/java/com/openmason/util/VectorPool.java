package com.openmason.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance object pool for JOML Vector3f and Matrix4f objects.
 * Reduces garbage collection pressure during frequent mathematical operations.
 * 
 * Thread-safe implementation with automatic pool management and monitoring.
 */
public class VectorPool {
    
    private static final Logger logger = LoggerFactory.getLogger(VectorPool.class);
    
    // Pool configuration
    private static final int DEFAULT_POOL_SIZE = 50;
    private static final int MAX_POOL_SIZE = 200;
    private static final long CLEANUP_INTERVAL_MS = 30000; // 30 seconds
    
    // Vector3f pool
    private static final ConcurrentLinkedQueue<Vector3f> vector3fPool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger vector3fCreated = new AtomicInteger(0);
    private static final AtomicInteger vector3fAcquired = new AtomicInteger(0);
    private static final AtomicInteger vector3fReleased = new AtomicInteger(0);
    
    // Matrix4f pool
    private static final ConcurrentLinkedQueue<Matrix4f> matrix4fPool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger matrix4fCreated = new AtomicInteger(0);
    private static final AtomicInteger matrix4fAcquired = new AtomicInteger(0);
    private static final AtomicInteger matrix4fReleased = new AtomicInteger(0);
    
    // Pool management
    private static final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());
    private static volatile boolean initialized = false;
    
    static {
        initializePool();
    }
    
    /**
     * Initializes the object pools with pre-allocated objects.
     */
    private static void initializePool() {
        if (initialized) return;
        
        logger.info("Initializing VectorPool with {} Vector3f and {} Matrix4f objects", 
                   DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE);
        
        // Pre-allocate Vector3f objects
        for (int i = 0; i < DEFAULT_POOL_SIZE; i++) {
            vector3fPool.offer(new Vector3f());
            vector3fCreated.incrementAndGet();
        }
        
        // Pre-allocate Matrix4f objects
        for (int i = 0; i < DEFAULT_POOL_SIZE; i++) {
            matrix4fPool.offer(new Matrix4f());
            matrix4fCreated.incrementAndGet();
        }
        
        initialized = true;
        logger.info("VectorPool initialized successfully");
    }
    
    /**
     * Acquires a Vector3f from the pool. If pool is empty, creates a new instance.
     * The returned vector is reset to zero.
     * 
     * @return A zeroed Vector3f instance
     */
    public static Vector3f acquireVector3f() {
        vector3fAcquired.incrementAndGet();
        
        Vector3f vector = vector3fPool.poll();
        if (vector == null) {
            vector = new Vector3f();
            vector3fCreated.incrementAndGet();
            
            if (logger.isDebugEnabled()) {
                logger.debug("Created new Vector3f (total created: {})", vector3fCreated.get());
            }
        } else {
            vector.zero(); // Reset to zero state
        }
        
        // Trigger cleanup if needed
        checkAndPerformCleanup();
        
        return vector;
    }
    
    /**
     * Acquires a Vector3f from the pool and sets it to the specified values.
     * 
     * @param x X component
     * @param y Y component  
     * @param z Z component
     * @return A Vector3f with the specified values
     */
    public static Vector3f acquireVector3f(float x, float y, float z) {
        Vector3f vector = acquireVector3f();
        vector.set(x, y, z);
        return vector;
    }
    
    /**
     * Acquires a Vector3f from the pool and copies values from the source vector.
     * 
     * @param source The vector to copy from
     * @return A Vector3f with copied values
     */
    public static Vector3f acquireVector3f(Vector3f source) {
        Vector3f vector = acquireVector3f();
        vector.set(source);
        return vector;
    }
    
    /**
     * Releases a Vector3f back to the pool for reuse.
     * The vector is automatically reset to zero state.
     * 
     * @param vector The vector to release (can be null)
     */
    public static void release(Vector3f vector) {
        if (vector == null) return;
        
        vector3fReleased.incrementAndGet();
        
        // Reset vector state
        vector.zero();
        
        // Only return to pool if we haven't exceeded max size
        if (vector3fPool.size() < MAX_POOL_SIZE) {
            vector3fPool.offer(vector);
        } else {
            // Pool is full, let GC handle this instance
            if (logger.isDebugEnabled()) {
                logger.debug("Vector3f pool full, allowing GC (pool size: {})", vector3fPool.size());
            }
        }
    }
    
    /**
     * Acquires a Matrix4f from the pool. If pool is empty, creates a new instance.
     * The returned matrix is reset to identity.
     * 
     * @return An identity Matrix4f instance
     */
    public static Matrix4f acquireMatrix4f() {
        matrix4fAcquired.incrementAndGet();
        
        Matrix4f matrix = matrix4fPool.poll();
        if (matrix == null) {
            matrix = new Matrix4f();
            matrix4fCreated.incrementAndGet();
            
            if (logger.isDebugEnabled()) {
                logger.debug("Created new Matrix4f (total created: {})", matrix4fCreated.get());
            }
        } else {
            matrix.identity(); // Reset to identity matrix
        }
        
        // Trigger cleanup if needed
        checkAndPerformCleanup();
        
        return matrix;
    }
    
    /**
     * Releases a Matrix4f back to the pool for reuse.
     * The matrix is automatically reset to identity state.
     * 
     * @param matrix The matrix to release (can be null)
     */
    public static void release(Matrix4f matrix) {
        if (matrix == null) return;
        
        matrix4fReleased.incrementAndGet();
        
        // Reset matrix state
        matrix.identity();
        
        // Only return to pool if we haven't exceeded max size
        if (matrix4fPool.size() < MAX_POOL_SIZE) {
            matrix4fPool.offer(matrix);
        } else {
            // Pool is full, let GC handle this instance
            if (logger.isDebugEnabled()) {
                logger.debug("Matrix4f pool full, allowing GC (pool size: {})", matrix4fPool.size());
            }
        }
    }
    
    /**
     * Convenience method for performing operations with automatic vector management.
     * The vectors are automatically released after the operation.
     * 
     * @param operation The operation to perform with the provided vectors
     */
    public static void withVectors(VectorOperation operation) {
        Vector3f v1 = acquireVector3f();
        Vector3f v2 = acquireVector3f();
        Vector3f v3 = acquireVector3f();
        
        try {
            operation.perform(v1, v2, v3);
        } finally {
            release(v1);
            release(v2);
            release(v3);
        }
    }
    
    /**
     * Convenience method for performing operations with automatic matrix management.
     * 
     * @param operation The operation to perform with the provided matrices
     */
    public static void withMatrices(MatrixOperation operation) {
        Matrix4f m1 = acquireMatrix4f();
        Matrix4f m2 = acquireMatrix4f();
        
        try {
            operation.perform(m1, m2);
        } finally {
            release(m1);
            release(m2);
        }
    }
    
    /**
     * Checks if cleanup is needed and performs it if the interval has elapsed.
     */
    private static void checkAndPerformCleanup() {
        long currentTime = System.currentTimeMillis();
        long lastCleanup = lastCleanupTime.get();
        
        if (currentTime - lastCleanup > CLEANUP_INTERVAL_MS) {
            if (lastCleanupTime.compareAndSet(lastCleanup, currentTime)) {
                performCleanup();
            }
        }
    }
    
    /**
     * Performs periodic cleanup to manage pool sizes and remove excess objects.
     */
    private static void performCleanup() {
        int vector3fPoolSize = vector3fPool.size();
        int matrix4fPoolSize = matrix4fPool.size();
        
        // Remove excess objects from Vector3f pool
        while (vector3fPool.size() > DEFAULT_POOL_SIZE) {
            vector3fPool.poll();
        }
        
        // Remove excess objects from Matrix4f pool
        while (matrix4fPool.size() > DEFAULT_POOL_SIZE) {
            matrix4fPool.poll();
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Pool cleanup completed. Vector3f: {} -> {}, Matrix4f: {} -> {}",
                        vector3fPoolSize, vector3fPool.size(),
                        matrix4fPoolSize, matrix4fPool.size());
        }
    }
    
    /**
     * Gets comprehensive pool statistics for monitoring and debugging.
     * 
     * @return PoolStatistics containing current pool state
     */
    public static PoolStatistics getStatistics() {
        return new PoolStatistics(
            vector3fPool.size(),
            vector3fCreated.get(),
            vector3fAcquired.get(),
            vector3fReleased.get(),
            matrix4fPool.size(),
            matrix4fCreated.get(),
            matrix4fAcquired.get(),
            matrix4fReleased.get()
        );
    }
    
    /**
     * Clears all pools and resets statistics. Use with caution.
     */
    public static void clear() {
        vector3fPool.clear();
        matrix4fPool.clear();
        
        vector3fCreated.set(0);
        vector3fAcquired.set(0);
        vector3fReleased.set(0);
        
        matrix4fCreated.set(0);
        matrix4fAcquired.set(0);
        matrix4fReleased.set(0);
        
        logger.info("VectorPool cleared and statistics reset");
    }
    
    /**
     * Functional interface for vector operations.
     */
    @FunctionalInterface
    public interface VectorOperation {
        void perform(Vector3f v1, Vector3f v2, Vector3f v3);
    }
    
    /**
     * Functional interface for matrix operations.
     */
    @FunctionalInterface
    public interface MatrixOperation {
        void perform(Matrix4f m1, Matrix4f m2);
    }
    
    /**
     * Immutable statistics class for pool monitoring.
     */
    public static class PoolStatistics {
        public final int vector3fPoolSize;
        public final int vector3fCreated;
        public final int vector3fAcquired;
        public final int vector3fReleased;
        public final int matrix4fPoolSize;
        public final int matrix4fCreated;
        public final int matrix4fAcquired;
        public final int matrix4fReleased;
        
        private PoolStatistics(int vector3fPoolSize, int vector3fCreated, int vector3fAcquired, int vector3fReleased,
                              int matrix4fPoolSize, int matrix4fCreated, int matrix4fAcquired, int matrix4fReleased) {
            this.vector3fPoolSize = vector3fPoolSize;
            this.vector3fCreated = vector3fCreated;
            this.vector3fAcquired = vector3fAcquired;
            this.vector3fReleased = vector3fReleased;
            this.matrix4fPoolSize = matrix4fPoolSize;
            this.matrix4fCreated = matrix4fCreated;
            this.matrix4fAcquired = matrix4fAcquired;
            this.matrix4fReleased = matrix4fReleased;
        }
        
        /**
         * Gets the current Vector3f pool efficiency (0.0 to 1.0).
         * Higher values indicate better reuse.
         */
        public double getVector3fEfficiency() {
            if (vector3fAcquired == 0) return 0.0;
            return 1.0 - ((double) vector3fCreated / vector3fAcquired);
        }
        
        /**
         * Gets the current Matrix4f pool efficiency (0.0 to 1.0).
         * Higher values indicate better reuse.
         */
        public double getMatrix4fEfficiency() {
            if (matrix4fAcquired == 0) return 0.0;
            return 1.0 - ((double) matrix4fCreated / matrix4fAcquired);
        }
        
        @Override
        public String toString() {
            return String.format(
                "VectorPool Statistics:\n" +
                "  Vector3f: pool=%d, created=%d, acquired=%d, released=%d (efficiency=%.2f)\n" +
                "  Matrix4f: pool=%d, created=%d, acquired=%d, released=%d (efficiency=%.2f)",
                vector3fPoolSize, vector3fCreated, vector3fAcquired, vector3fReleased, getVector3fEfficiency(),
                matrix4fPoolSize, matrix4fCreated, matrix4fAcquired, matrix4fReleased, getMatrix4fEfficiency()
            );
        }
    }
}