package com.openmason.rendering;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;

/**
 * Central manager for OpenGL buffer lifecycle and memory monitoring.
 * Tracks all buffers and vertex arrays to prevent memory leaks and provide
 * debugging information about OpenGL resource usage.
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
    private final boolean enableMemoryTracking = true;

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
        int maxHistoryEntries = 1000;
        while (bufferHistory.size() > maxHistoryEntries) {
            bufferHistory.poll();
        }
    }
    
    /**
     * Checks current memory usage and logs warnings if thresholds are exceeded.
     */
    private void checkMemoryUsage() {
        long currentMemory = getCurrentMemoryUsage();

        // 100MB
        long memoryWarningThreshold = 100 * 1024 * 1024;
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
    
    /**
     * Enumeration of buffer operations for statistics tracking.
     */
    private enum BufferOperation {
        CREATE, DESTROY, UPDATE
    }

    /**
         * Statistics record for a single buffer operation.
         */
        private record BufferStatistics(long timestamp, String bufferName, int bufferType, int dataSize,
                                        BufferOperation operation) {
    }
}