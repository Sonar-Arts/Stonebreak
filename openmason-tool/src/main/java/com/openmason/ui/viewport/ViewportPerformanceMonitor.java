package com.openmason.ui.viewport;

import com.openmason.rendering.PerformanceOptimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors and tracks performance metrics for the 3D viewport.
 * 
 * Responsible for:
 * - FPS (Frames Per Second) calculation and tracking
 * - Frame timing statistics
 * - Error count tracking
 * - Performance statistics collection
 * - Performance overlay data preparation
 * - Integration with PerformanceOptimizer
 */
public class ViewportPerformanceMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportPerformanceMonitor.class);
    
    // Performance tracking
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong lastFPSUpdate = new AtomicLong(System.currentTimeMillis());
    private volatile double currentFPS = 0.0;
    private volatile long lastFrameTime = 0;
    
    // Error tracking
    private final AtomicLong errorCount = new AtomicLong(0);
    private volatile Throwable lastError;
    
    // Performance optimizer integration
    private PerformanceOptimizer performanceOptimizer;
    
    // State tracking
    private volatile boolean initialized = false;
    private volatile boolean renderingEnabled = false;
    private volatile boolean disposed = false;
    
    // FPS calculation settings
    private static final long FPS_UPDATE_INTERVAL_MS = 1000; // Update FPS every second
    private static final int FPS_HISTORY_SIZE = 60;
    
    // FPS history for smoothing
    private final double[] fpsHistory = new double[FPS_HISTORY_SIZE];
    private int fpsHistoryIndex = 0;
    private boolean fpsHistoryFull = false;
    
    /**
     * Initialize the performance monitor.
     */
    public void initialize(PerformanceOptimizer performanceOptimizer) {
        this.performanceOptimizer = performanceOptimizer;
        this.initialized = true;
        
        logger.debug("ViewportPerformanceMonitor initialized");
    }
    
    /**
     * Update frame timing and calculate FPS.
     */
    public void updateFrameTiming() {
        if (!initialized) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long currentFrameCount = frameCount.incrementAndGet();
        
        // Calculate frame time
        if (lastFrameTime > 0) {
            long frameDelta = currentTime - lastFrameTime;
            
            // Update FPS periodically
            long timeSinceLastFPSUpdate = currentTime - lastFPSUpdate.get();
            if (timeSinceLastFPSUpdate >= FPS_UPDATE_INTERVAL_MS) {
                calculateFPS(currentTime, currentFrameCount);
                lastFPSUpdate.set(currentTime);
            }
        }
        
        lastFrameTime = currentTime;
    }
    
    /**
     * Calculate current FPS and update history.
     */
    private void calculateFPS(long currentTime, long currentFrameCount) {
        try {
            long timeDelta = currentTime - lastFPSUpdate.get();
            
            if (timeDelta > 0) {
                // Calculate instantaneous FPS
                double instantFPS = 1000.0 / timeDelta;
                
                // Add to history
                fpsHistory[fpsHistoryIndex] = instantFPS;
                fpsHistoryIndex = (fpsHistoryIndex + 1) % FPS_HISTORY_SIZE;
                if (fpsHistoryIndex == 0) {
                    fpsHistoryFull = true;
                }
                
                // Calculate smoothed FPS
                currentFPS = calculateSmoothedFPS();
                
                logger.trace("FPS updated: instant={:.1f}, smoothed={:.1f}", instantFPS, currentFPS);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to calculate FPS", e);
        }
    }
    
    /**
     * Calculate smoothed FPS from history.
     */
    private double calculateSmoothedFPS() {
        int sampleCount = fpsHistoryFull ? FPS_HISTORY_SIZE : fpsHistoryIndex;
        if (sampleCount == 0) return 0.0;
        
        double sum = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            sum += fpsHistory[i];
        }
        
        return sum / sampleCount;
    }
    
    /**
     * Record an error occurrence.
     */
    public void recordError(Throwable error) {
        errorCount.incrementAndGet();
        lastError = error;
        
        logger.debug("Error recorded: {} (total errors: {})", 
            error != null ? error.getClass().getSimpleName() : "null", 
            errorCount.get());
    }
    
    /**
     * Set rendering enabled state.
     */
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
        
        if (enabled) {
            logger.debug("Rendering enabled - performance monitoring active");
        } else {
            logger.debug("Rendering disabled - performance monitoring paused");
        }
    }
    
    /**
     * Get current rendering statistics.
     */
    public RenderingStatistics getStatistics() {
        Object bufferManagerStats = null;
        
        // Get buffer manager stats if available
        if (performanceOptimizer != null) {
            try {
                // This would need to be adapted based on actual PerformanceOptimizer API
                bufferManagerStats = "PerformanceOptimizer integration active";
            } catch (Exception e) {
                logger.trace("Failed to get buffer manager stats", e);
            }
        }
        
        return new RenderingStatistics(
            frameCount.get(),
            currentFPS,
            lastFrameTime,
            errorCount.get(),
            initialized,
            renderingEnabled,
            disposed,
            bufferManagerStats
        );
    }
    
    /**
     * Get performance statistics from the performance optimizer.
     */
    public PerformanceOptimizer.PerformanceStatistics getPerformanceStatistics() {
        if (performanceOptimizer != null) {
            try {
                return performanceOptimizer.getStatistics();
            } catch (Exception e) {
                logger.warn("Failed to get performance statistics", e);
            }
        }
        return null;
    }
    
    /**
     * Get performance summary from the performance optimizer.
     */
    public PerformanceOptimizer.PerformanceSummary getPerformanceSummary() {
        if (performanceOptimizer != null) {
            try {
                return performanceOptimizer.getSummary();
            } catch (Exception e) {
                logger.warn("Failed to get performance summary", e);
            }
        }
        return null;
    }
    
    /**
     * Get performance overlay data.
     */
    public Map<String, Object> getPerformanceOverlayData() {
        Map<String, Object> data = new HashMap<>();
        
        data.put("fps", String.format("%.1f", currentFPS));
        data.put("frameCount", frameCount.get());
        data.put("errorCount", errorCount.get());
        data.put("renderingEnabled", renderingEnabled);
        data.put("lastFrameTime", lastFrameTime);
        
        // Add performance optimizer data if available
        if (performanceOptimizer != null) {
            try {
                PerformanceOptimizer.PerformanceStatistics stats = performanceOptimizer.getStatistics();
                if (stats != null) {
                    data.put("optimizerStats", stats);
                }
            } catch (Exception e) {
                logger.trace("Failed to get optimizer stats for overlay", e);
            }
        }
        
        return data;
    }
    
    /**
     * Reset performance statistics.
     */
    public void resetStatistics() {
        frameCount.set(0);
        errorCount.set(0);
        currentFPS = 0.0;
        lastFrameTime = 0;
        lastError = null;
        lastFPSUpdate.set(System.currentTimeMillis());
        
        // Clear FPS history
        fpsHistoryIndex = 0;
        fpsHistoryFull = false;
        
        logger.debug("Performance statistics reset");
    }
    
    /**
     * Dispose of performance monitoring resources.
     */
    public void dispose() {
        disposed = true;
        initialized = false;
        renderingEnabled = false;
        
        resetStatistics();
        
        logger.debug("ViewportPerformanceMonitor disposed");
    }
    
    // Getters
    public double getCurrentFPS() {
        return currentFPS;
    }
    
    public long getFrameCount() {
        return frameCount.get();
    }
    
    public long getErrorCount() {
        return errorCount.get();
    }
    
    public Throwable getLastError() {
        return lastError;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public boolean isRenderingEnabled() {
        return renderingEnabled;
    }
    
    public boolean isDisposed() {
        return disposed;
    }
    
    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceOptimizer;
    }
    
    /**
     * Get performance monitor state for debugging.
     */
    public String getPerformanceState() {
        return String.format("Performance: FPS=%.1f, Frames=%d, Errors=%d, Enabled=%s",
            currentFPS, frameCount.get(), errorCount.get(), renderingEnabled);
    }
    
    /**
     * Statistics class for rendering performance data.
     */
    public static class RenderingStatistics {
        private final long frameCount;
        private final double currentFPS;
        private final long lastFrameTime;
        private final long errorCount;
        private final boolean initialized;
        private final boolean renderingEnabled;
        private final boolean disposed;
        private final Object bufferManagerStats;
        
        public RenderingStatistics(long frameCount, double currentFPS, long lastFrameTime,
                                 long errorCount, boolean initialized, boolean renderingEnabled,
                                 boolean disposed, Object bufferManagerStats) {
            this.frameCount = frameCount;
            this.currentFPS = currentFPS;
            this.lastFrameTime = lastFrameTime;
            this.errorCount = errorCount;
            this.initialized = initialized;
            this.renderingEnabled = renderingEnabled;
            this.disposed = disposed;
            this.bufferManagerStats = bufferManagerStats;
        }
        
        // Getters
        public long getFrameCount() { return frameCount; }
        public double getCurrentFPS() { return currentFPS; }
        public long getLastFrameTime() { return lastFrameTime; }
        public long getErrorCount() { return errorCount; }
        public boolean isInitialized() { return initialized; }
        public boolean isRenderingEnabled() { return renderingEnabled; }
        public boolean isDisposed() { return disposed; }
        public Object getBufferManagerStats() { return bufferManagerStats; }
        
        /**
         * Convert statistics to a map for easy serialization.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("frameCount", frameCount);
            map.put("currentFPS", currentFPS);
            map.put("lastFrameTime", lastFrameTime);
            map.put("errorCount", errorCount);
            map.put("initialized", initialized);
            map.put("renderingEnabled", renderingEnabled);
            map.put("disposed", disposed);
            map.put("bufferManagerStats", bufferManagerStats);
            return map;
        }
        
        @Override
        public String toString() {
            return String.format("RenderingStatistics{frameCount=%d, FPS=%.1f, errors=%d, enabled=%s}",
                frameCount, currentFPS, errorCount, renderingEnabled);
        }
    }
}