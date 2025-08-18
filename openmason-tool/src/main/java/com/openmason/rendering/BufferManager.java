package com.openmason.rendering;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Central manager for OpenGL buffer lifecycle and memory monitoring.
 * Tracks all buffers and vertex arrays to prevent memory leaks and provide
 * debugging information about OpenGL resource usage.
 * 
 * This singleton class provides:
 * - Buffer registration and tracking
 * - Memory usage monitoring
 * - Automatic cleanup on shutdown
 * - Performance statistics
 * - Leak detection
 */
public class BufferManager {
    private static volatile BufferManager instance;
    private static final Object lock = new Object();
    
    // Thread-safe collections for tracking resources
    private final ConcurrentHashMap<Integer, OpenGLBuffer> activeBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, VertexArray> activeVertexArrays = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BufferStatistics> bufferHistory = new ConcurrentLinkedQueue<>();
    
    // Statistics
    private final AtomicLong totalBuffersCreated = new AtomicLong(0);
    private final AtomicLong totalVertexArraysCreated = new AtomicLong(0);
    private final AtomicLong totalMemoryAllocated = new AtomicLong(0);
    private final AtomicLong totalMemoryDeallocated = new AtomicLong(0);
    
    // Configuration
    private boolean enableMemoryTracking = true;
    private boolean enableLeakDetection = true;
    private long memoryWarningThreshold = 100 * 1024 * 1024; // 100MB
    private int maxHistoryEntries = 1000;
    
    private BufferManager() {
        // Private constructor for singleton
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    }
    
    /**
     * Gets the singleton instance of the BufferManager.
     * 
     * @return The BufferManager instance
     */
    public static BufferManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new BufferManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Registers a buffer with the manager for tracking.
     * 
     * @param buffer The buffer to register
     */
    public void registerBuffer(OpenGLBuffer buffer) {
        if (buffer == null || !buffer.isValid()) {
            return;
        }
        
        activeBuffers.put(buffer.getBufferId(), buffer);
        totalBuffersCreated.incrementAndGet();
        
        if (enableMemoryTracking) {
            long memoryUsed = buffer.getDataSize();
            totalMemoryAllocated.addAndGet(memoryUsed);
            
            recordBufferStatistics(buffer, BufferOperation.CREATE);
            checkMemoryUsage();
        }
    }
    
    /**
     * Unregisters a buffer from the manager.
     * 
     * @param buffer The buffer to unregister
     */
    public void unregisterBuffer(OpenGLBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        OpenGLBuffer removed = activeBuffers.remove(buffer.getBufferId());
        
        if (removed != null && enableMemoryTracking) {
            long memoryFreed = removed.getDataSize();
            totalMemoryDeallocated.addAndGet(memoryFreed);
            
            recordBufferStatistics(removed, BufferOperation.DESTROY);
        }
    }
    
    /**
     * Registers a vertex array with the manager for tracking.
     * 
     * @param vertexArray The vertex array to register
     */
    public void registerVertexArray(VertexArray vertexArray) {
        if (vertexArray == null || !vertexArray.isValid()) {
            return;
        }
        
        activeVertexArrays.put(vertexArray.getVaoId(), vertexArray);
        totalVertexArraysCreated.incrementAndGet();
    }
    
    /**
     * Unregisters a vertex array from the manager.
     * 
     * @param vertexArray The vertex array to unregister
     */
    public void unregisterVertexArray(VertexArray vertexArray) {
        if (vertexArray != null) {
            activeVertexArrays.remove(vertexArray.getVaoId());
        }
    }
    
    /**
     * Records buffer statistics for monitoring and debugging.
     * 
     * @param buffer The buffer involved in the operation
     * @param operation The type of operation performed
     */
    private void recordBufferStatistics(OpenGLBuffer buffer, BufferOperation operation) {
        BufferStatistics stats = new BufferStatistics(
            System.currentTimeMillis(),
            buffer.getDebugName(),
            buffer.getBufferType(),
            buffer.getDataSize(),
            operation
        );
        
        bufferHistory.offer(stats);
        
        // Trim history if it gets too large
        while (bufferHistory.size() > maxHistoryEntries) {
            bufferHistory.poll();
        }
    }
    
    /**
     * Checks current memory usage and logs warnings if thresholds are exceeded.
     */
    private void checkMemoryUsage() {
        long currentMemory = getCurrentMemoryUsage();
        
        if (currentMemory > memoryWarningThreshold) {
            System.err.println("WARNING: OpenGL buffer memory usage high: " + formatBytes(currentMemory));
            System.err.println("Active buffers: " + activeBuffers.size());
            System.err.println("Active vertex arrays: " + activeVertexArrays.size());
        }
    }
    
    /**
     * Gets the current memory usage of all tracked buffers.
     * 
     * @return Total memory usage in bytes
     */
    public long getCurrentMemoryUsage() {
        return activeBuffers.values().stream()
            .mapToLong(OpenGLBuffer::getDataSize)
            .sum();
    }
    
    /**
     * Gets detailed statistics about buffer usage.
     * 
     * @return BufferManagerStatistics object with detailed information
     */
    public BufferManagerStatistics getStatistics() {
        return new BufferManagerStatistics(
            activeBuffers.size(),
            activeVertexArrays.size(),
            totalBuffersCreated.get(),
            totalVertexArraysCreated.get(),
            getCurrentMemoryUsage(),
            totalMemoryAllocated.get(),
            totalMemoryDeallocated.get(),
            new ArrayList<>(bufferHistory)
        );
    }
    
    /**
     * Validates all tracked resources and detects potential leaks.
     * 
     * @return List of validation issues found
     */
    public List<String> validateResources() {
        List<String> issues = new ArrayList<>();
        
        if (!enableLeakDetection) {
            return issues;
        }
        
        long currentTime = System.currentTimeMillis();
        long staleThreshold = 5 * 60 * 1000; // 5 minutes
        
        // Check for stale buffers
        for (OpenGLBuffer buffer : activeBuffers.values()) {
            if (!buffer.isValid()) {
                issues.add("Invalid buffer still tracked: " + buffer.getDebugName());
            } else if (currentTime - buffer.getLastAccessTime() > staleThreshold) {
                issues.add("Stale buffer (not accessed recently): " + buffer.getDebugName());
            }
        }
        
        // Check for stale vertex arrays
        for (VertexArray vao : activeVertexArrays.values()) {
            if (!vao.isValid()) {
                issues.add("Invalid vertex array still tracked: " + vao.getDebugName());
            } else if (currentTime - vao.getLastAccessTime() > staleThreshold) {
                issues.add("Stale vertex array (not accessed recently): " + vao.getDebugName());
            }
        }
        
        return issues;
    }
    
    /**
     * Forces cleanup of all tracked resources.
     * Should be called before application shutdown.
     */
    public void cleanup() {
        // System.out.println("BufferManager: Cleaning up " + activeBuffers.size() + 
        //                   " buffers and " + activeVertexArrays.size() + " vertex arrays");
        
        // Clean up vertex arrays first (they may reference buffers)
        List<VertexArray> vaos = new ArrayList<>(activeVertexArrays.values());
        for (VertexArray vao : vaos) {
            try {
                vao.close();
            } catch (Exception e) {
                System.err.println("Error cleaning up vertex array " + vao.getDebugName() + ": " + e.getMessage());
            }
        }
        
        // Clean up remaining buffers
        List<OpenGLBuffer> buffers = new ArrayList<>(activeBuffers.values());
        for (OpenGLBuffer buffer : buffers) {
            try {
                buffer.close();
            } catch (Exception e) {
                System.err.println("Error cleaning up buffer " + buffer.getDebugName() + ": " + e.getMessage());
            }
        }
        
        // Clear collections
        activeBuffers.clear();
        activeVertexArrays.clear();
        bufferHistory.clear();
        
        // Print final statistics
        printFinalStatistics();
    }
    
    /**
     * Prints final statistics when the manager is being cleaned up.
     */
    private void printFinalStatistics() {
        // System.out.println("=== BufferManager Final Statistics ===");
        // System.out.println("Total buffers created: " + totalBuffersCreated.get());
        // System.out.println("Total vertex arrays created: " + totalVertexArraysCreated.get());
        // System.out.println("Total memory allocated: " + formatBytes(totalMemoryAllocated.get()));
        // System.out.println("Total memory deallocated: " + formatBytes(totalMemoryDeallocated.get()));
        // System.out.println("Memory leak potential: " + formatBytes(totalMemoryAllocated.get() - totalMemoryDeallocated.get()));
        // System.out.println("======================================");
    }
    
    /**
     * Formats byte count as human-readable string.
     * 
     * @param bytes Byte count
     * @return Formatted string (e.g., "1.5 MB")
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    // Configuration setters
    public void setMemoryTrackingEnabled(boolean enabled) { this.enableMemoryTracking = enabled; }
    public void setLeakDetectionEnabled(boolean enabled) { this.enableLeakDetection = enabled; }
    public void setMemoryWarningThreshold(long threshold) { this.memoryWarningThreshold = threshold; }
    public void setMaxHistoryEntries(int maxEntries) { this.maxHistoryEntries = maxEntries; }
    
    // Configuration getters
    public boolean isMemoryTrackingEnabled() { return enableMemoryTracking; }
    public boolean isLeakDetectionEnabled() { return enableLeakDetection; }
    public long getMemoryWarningThreshold() { return memoryWarningThreshold; }
    public int getMaxHistoryEntries() { return maxHistoryEntries; }
    
    /**
     * Enumeration of buffer operations for statistics tracking.
     */
    private enum BufferOperation {
        CREATE, DESTROY, UPDATE
    }
    
    /**
     * Statistics record for a single buffer operation.
     */
    private static class BufferStatistics {
        public final long timestamp;
        public final String bufferName;
        public final int bufferType;
        public final int dataSize;
        public final BufferOperation operation;
        
        public BufferStatistics(long timestamp, String bufferName, int bufferType, int dataSize, BufferOperation operation) {
            this.timestamp = timestamp;
            this.bufferName = bufferName;
            this.bufferType = bufferType;
            this.dataSize = dataSize;
            this.operation = operation;
        }
    }
    
    /**
     * Complete statistics snapshot of the BufferManager state.
     */
    public static class BufferManagerStatistics {
        public final int activeBufferCount;
        public final int activeVertexArrayCount;
        public final long totalBuffersCreated;
        public final long totalVertexArraysCreated;
        public final long currentMemoryUsage;
        public final long totalMemoryAllocated;
        public final long totalMemoryDeallocated;
        public final List<BufferStatistics> history;
        
        public BufferManagerStatistics(int activeBufferCount, int activeVertexArrayCount,
                                     long totalBuffersCreated, long totalVertexArraysCreated,
                                     long currentMemoryUsage, long totalMemoryAllocated,
                                     long totalMemoryDeallocated, List<BufferStatistics> history) {
            this.activeBufferCount = activeBufferCount;
            this.activeVertexArrayCount = activeVertexArrayCount;
            this.totalBuffersCreated = totalBuffersCreated;
            this.totalVertexArraysCreated = totalVertexArraysCreated;
            this.currentMemoryUsage = currentMemoryUsage;
            this.totalMemoryAllocated = totalMemoryAllocated;
            this.totalMemoryDeallocated = totalMemoryDeallocated;
            this.history = history;
        }
        
        @Override
        public String toString() {
            return String.format(
                "BufferManagerStatistics{activeBuffers=%d, activeVAOs=%d, totalBuffers=%d, " +
                "totalVAOs=%d, currentMemory=%d, totalAllocated=%d, totalDeallocated=%d}",
                activeBufferCount, activeVertexArrayCount, totalBuffersCreated,
                totalVertexArraysCreated, currentMemoryUsage, totalMemoryAllocated, totalMemoryDeallocated
            );
        }
    }
}