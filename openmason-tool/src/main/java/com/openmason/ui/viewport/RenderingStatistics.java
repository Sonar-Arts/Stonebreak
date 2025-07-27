package com.openmason.ui.viewport;

import com.openmason.rendering.BufferManager;

/**
 * Comprehensive statistics class for monitoring OpenMason 3D viewport performance.
 * 
 * This class provides detailed information about rendering performance, resource usage,
 * and system health to support debugging and optimization efforts.
 * 
 * Key Metrics:
 * - Frame rate and timing statistics
 * - Error tracking and handling
 * - Resource usage monitoring
 * - System state information
 * - Integration with BufferManager statistics
 */
public class RenderingStatistics {
    
    // Frame statistics
    private final long totalFrameCount;
    private final double currentFPS;
    private final long lastFrameTime;
    
    // Error tracking
    private final long totalErrorCount;
    
    // System state
    private final boolean initialized;
    private final boolean renderingEnabled;
    private final boolean disposed;
    
    // Resource management integration
    private final BufferManager.BufferManagerStatistics bufferStats;
    
    // Derived statistics
    private final double averageFrameTime;
    private final long uptimeMillis;
    
    /**
     * Creates a new RenderingStatistics snapshot.
     * 
     * @param totalFrameCount Total number of frames rendered
     * @param currentFPS Current frames per second
     * @param lastFrameTime Timestamp of the last rendered frame
     * @param totalErrorCount Total number of errors encountered
     * @param initialized Whether the viewport is initialized
     * @param renderingEnabled Whether rendering is currently enabled
     * @param disposed Whether the viewport has been disposed
     * @param bufferStats Buffer manager statistics (may be null)
     */
    public RenderingStatistics(long totalFrameCount, double currentFPS, long lastFrameTime,
                             long totalErrorCount, boolean initialized, boolean renderingEnabled,
                             boolean disposed, BufferManager.BufferManagerStatistics bufferStats) {
        this.totalFrameCount = totalFrameCount;
        this.currentFPS = currentFPS;
        this.lastFrameTime = lastFrameTime;
        this.totalErrorCount = totalErrorCount;
        this.initialized = initialized;
        this.renderingEnabled = renderingEnabled;
        this.disposed = disposed;
        this.bufferStats = bufferStats;
        
        // Calculate derived statistics
        this.averageFrameTime = currentFPS > 0 ? 1000.0 / currentFPS : 0.0;
        this.uptimeMillis = lastFrameTime > 0 ? System.currentTimeMillis() - (lastFrameTime - (long)(totalFrameCount * averageFrameTime)) : 0;
    }
    
    /**
     * Gets the total number of frames rendered since initialization.
     * 
     * @return Total frame count
     */
    public long getTotalFrameCount() {
        return totalFrameCount;
    }
    
    /**
     * Gets the current frames per second.
     * 
     * @return Current FPS
     */
    public double getCurrentFPS() {
        return currentFPS;
    }
    
    /**
     * Gets the timestamp of the last rendered frame.
     * 
     * @return Last frame timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * Gets the average time per frame in milliseconds.
     * 
     * @return Average frame time in ms
     */
    public double getAverageFrameTime() {
        return averageFrameTime;
    }
    
    /**
     * Gets the total number of errors encountered.
     * 
     * @return Total error count
     */
    public long getTotalErrorCount() {
        return totalErrorCount;
    }
    
    /**
     * Gets the viewport uptime in milliseconds.
     * 
     * @return Uptime in milliseconds
     */
    public long getUptimeMillis() {
        return uptimeMillis;
    }
    
    /**
     * Checks if the viewport is initialized.
     * 
     * @return True if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Checks if rendering is currently enabled.
     * 
     * @return True if rendering is enabled
     */
    public boolean isRenderingEnabled() {
        return renderingEnabled;
    }
    
    /**
     * Checks if the viewport has been disposed.
     * 
     * @return True if disposed
     */
    public boolean isDisposed() {
        return disposed;
    }
    
    /**
     * Gets the buffer manager statistics.
     * 
     * @return Buffer manager statistics, or null if not available
     */
    public BufferManager.BufferManagerStatistics getBufferStats() {
        return bufferStats;
    }
    
    /**
     * Calculates the overall health score of the viewport (0.0 to 1.0).
     * 
     * @return Health score where 1.0 is perfect health
     */
    public double getHealthScore() {
        if (disposed) {
            return 0.0;
        }
        
        if (!initialized || !renderingEnabled) {
            return 0.2;
        }
        
        double score = 1.0;
        
        // Penalize for low FPS
        if (currentFPS < 30) {
            score *= 0.7;
        } else if (currentFPS < 60) {
            score *= 0.9;
        }
        
        // Penalize for errors
        if (totalErrorCount > 0) {
            score *= Math.max(0.5, 1.0 - (totalErrorCount * 0.01));
        }
        
        // Penalize for high memory usage
        if (bufferStats != null) {
            long memoryMB = bufferStats.currentMemoryUsage / (1024 * 1024);
            if (memoryMB > 500) {
                score *= 0.8;
            } else if (memoryMB > 200) {
                score *= 0.9;
            }
        }
        
        return Math.max(0.0, Math.min(1.0, score));
    }
    
    /**
     * Gets a human-readable summary of the viewport status.
     * 
     * @return Status summary string
     */
    public String getStatusSummary() {
        if (disposed) {
            return "Disposed";
        }
        
        if (!initialized) {
            return "Initializing";
        }
        
        if (!renderingEnabled) {
            return "Disabled";
        }
        
        if (totalErrorCount > 0) {
            return String.format("Running with %d errors", totalErrorCount);
        }
        
        if (currentFPS >= 60) {
            return "Excellent";
        } else if (currentFPS >= 30) {
            return "Good";
        } else if (currentFPS > 0) {
            return "Poor Performance";
        } else {
            return "No Rendering";
        }
    }
    
    /**
     * Formats the current FPS for display.
     * 
     * @return Formatted FPS string
     */
    public String getFormattedFPS() {
        if (currentFPS == 0) {
            return "0 FPS";
        }
        return String.format("%.1f FPS", currentFPS);
    }
    
    /**
     * Formats the uptime for display.
     * 
     * @return Formatted uptime string
     */
    public String getFormattedUptime() {
        if (uptimeMillis < 1000) {
            return uptimeMillis + "ms";
        } else if (uptimeMillis < 60000) {
            return String.format("%.1fs", uptimeMillis / 1000.0);
        } else if (uptimeMillis < 3600000) {
            return String.format("%.1fm", uptimeMillis / 60000.0);
        } else {
            return String.format("%.1fh", uptimeMillis / 3600000.0);
        }
    }
    
    /**
     * Creates a detailed text report of all statistics.
     * 
     * @return Detailed statistics report
     */
    public String generateDetailedReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("=== OpenMason 3D Viewport Statistics ===\n");
        report.append("Status: ").append(getStatusSummary()).append("\n");
        report.append("Health Score: ").append(String.format("%.1f%%", getHealthScore() * 100)).append("\n");
        report.append("Uptime: ").append(getFormattedUptime()).append("\n");
        report.append("\n");
        
        report.append("--- Rendering Performance ---\n");
        report.append("Current FPS: ").append(getFormattedFPS()).append("\n");
        report.append("Average Frame Time: ").append(String.format("%.2fms", averageFrameTime)).append("\n");
        report.append("Total Frames: ").append(totalFrameCount).append("\n");
        report.append("Error Count: ").append(totalErrorCount).append("\n");
        report.append("\n");
        
        report.append("--- System State ---\n");
        report.append("Initialized: ").append(initialized).append("\n");
        report.append("Rendering Enabled: ").append(renderingEnabled).append("\n");
        report.append("Disposed: ").append(disposed).append("\n");
        report.append("\n");
        
        if (bufferStats != null) {
            report.append("--- Buffer Management ---\n");
            report.append("Active Buffers: ").append(bufferStats.activeBufferCount).append("\n");
            report.append("Active VAOs: ").append(bufferStats.activeVertexArrayCount).append("\n");
            report.append("Memory Usage: ").append(formatBytes(bufferStats.currentMemoryUsage)).append("\n");
            report.append("Total Allocated: ").append(formatBytes(bufferStats.totalMemoryAllocated)).append("\n");
            report.append("Total Deallocated: ").append(formatBytes(bufferStats.totalMemoryDeallocated)).append("\n");
        } else {
            report.append("--- Buffer Management ---\n");
            report.append("Buffer statistics not available\n");
        }
        
        return report.toString();
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
    
    @Override
    public String toString() {
        return String.format(
            "RenderingStatistics{frames=%d, fps=%.1f, errors=%d, status=%s, health=%.1f%%}",
            totalFrameCount, currentFPS, totalErrorCount, getStatusSummary(), getHealthScore() * 100
        );
    }
    
    /**
     * Creates a compact statistics object suitable for logging.
     * 
     * @return Compact statistics representation
     */
    public CompactStats toCompactStats() {
        return new CompactStats(
            totalFrameCount,
            currentFPS,
            totalErrorCount,
            getHealthScore(),
            initialized && renderingEnabled && !disposed
        );
    }
    
    /**
     * Compact statistics representation for efficient logging and monitoring.
     */
    public static class CompactStats {
        public final long frames;
        public final double fps;
        public final long errors;
        public final double health;
        public final boolean active;
        
        public CompactStats(long frames, double fps, long errors, double health, boolean active) {
            this.frames = frames;
            this.fps = fps;
            this.errors = errors;
            this.health = health;
            this.active = active;
        }
        
        @Override
        public String toString() {
            return String.format("frames:%d fps:%.1f errors:%d health:%.1f%% active:%b",
                frames, fps, errors, health * 100, active);
        }
    }
}