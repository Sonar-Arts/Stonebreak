package com.openmason.ui;

import com.openmason.texture.TextureVariantManager;
import javafx.application.Platform;
import javafx.beans.property.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Performance monitoring utility for OpenMason Phase 5.
 * Provides real-time performance metrics and UI-friendly property bindings.
 * 
 * Features:
 * - Real-time texture switching performance monitoring
 * - Cache hit/miss ratio tracking
 * - Memory usage monitoring
 * - JavaFX property bindings for reactive UI updates
 * - Configurable update intervals
 * - Performance alerts and warnings
 */
public class PerformanceMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    // Update configuration
    private static final long UPDATE_INTERVAL_MS = 1000; // 1 second
    private static final long PERFORMANCE_WARNING_THRESHOLD = 200; // ms
    private static final double CACHE_WARNING_THRESHOLD = 0.7; // 70% hit rate
    
    // Monitoring targets
    private TextureVariantManager textureManager;
    private MainController mainController;
    
    // UI Properties
    private final StringProperty statusMessage = new SimpleStringProperty("Monitoring...");
    private final DoubleProperty averageSwitchTime = new SimpleDoubleProperty(0.0);
    private final DoubleProperty cacheHitRate = new SimpleDoubleProperty(0.0);
    private final LongProperty totalSwitches = new SimpleLongProperty(0);
    private final LongProperty cacheHits = new SimpleLongProperty(0);
    private final LongProperty cacheMisses = new SimpleLongProperty(0);
    private final BooleanProperty performanceWarning = new SimpleBooleanProperty(false);
    private final BooleanProperty cacheWarning = new SimpleBooleanProperty(false);
    
    // Monitoring state
    private ScheduledExecutorService monitoringExecutor;
    private boolean monitoring = false;
    private long lastUpdateTime = 0;
    
    /**
     * Create a new PerformanceMonitor.
     */
    public PerformanceMonitor() {
        // Initialize monitoring executor
        monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerformanceMonitor-" + System.currentTimeMillis());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        logger.info("PerformanceMonitor initialized");
    }
    
    /**
     * Set the texture manager to monitor.
     */
    public void setTextureManager(TextureVariantManager textureManager) {
        this.textureManager = textureManager;
        logger.debug("TextureManager set for monitoring");
    }
    
    /**
     * Set the main controller to monitor.
     */
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        logger.debug("MainController set for monitoring");
    }
    
    /**
     * Start performance monitoring.
     */
    public void startMonitoring() {
        if (monitoring) {
            logger.warn("Performance monitoring already started");
            return;
        }
        
        monitoring = true;
        lastUpdateTime = System.currentTimeMillis();
        
        // Schedule periodic updates
        monitoringExecutor.scheduleAtFixedRate(
            this::updateMetrics, 
            0, 
            UPDATE_INTERVAL_MS, 
            TimeUnit.MILLISECONDS
        );
        
        logger.info("Performance monitoring started (update interval: {}ms)", UPDATE_INTERVAL_MS);
        Platform.runLater(() -> statusMessage.set("Monitoring active"));
    }
    
    /**
     * Stop performance monitoring.
     */
    public void stopMonitoring() {
        if (!monitoring) {
            return;
        }
        
        monitoring = false;
        
        if (monitoringExecutor != null && !monitoringExecutor.isShutdown()) {
            monitoringExecutor.shutdown();
            try {
                if (!monitoringExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitoringExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("Performance monitoring stopped");
        Platform.runLater(() -> statusMessage.set("Monitoring stopped"));
    }
    
    /**
     * Update performance metrics from monitored components.
     */
    private void updateMetrics() {
        try {
            long currentTime = System.currentTimeMillis();
            
            // Update texture manager metrics
            if (textureManager != null) {
                updateTextureManagerMetrics();
            }
            
            // Update main controller metrics
            if (mainController != null) {
                updateMainControllerMetrics();
            }
            
            // Check for performance warnings
            checkPerformanceWarnings();
            
            // Update status
            Platform.runLater(() -> {
                statusMessage.set(String.format("Monitoring active - Avg: %.0fms, Cache: %.1f%%", 
                    averageSwitchTime.get(), cacheHitRate.get() * 100));
            });
            
            lastUpdateTime = currentTime;
            
        } catch (Exception e) {
            logger.error("Error updating performance metrics", e);
            Platform.runLater(() -> statusMessage.set("Monitoring error: " + e.getMessage()));
        }
    }
    
    /**
     * Update metrics from TextureVariantManager.
     */
    private void updateTextureManagerMetrics() {
        try {
            Map<String, Object> stats = textureManager.getPerformanceStats();
            
            // Extract metrics
            long hits = (Long) stats.get("cacheHits");
            long misses = (Long) stats.get("cacheMisses");
            long total = hits + misses;
            double hitRate = total > 0 ? (double) hits / total : 0.0;
            
            String avgSwitchTimeStr = (String) stats.get("averageSwitchTime");
            double avgTime = 0.0;
            if (avgSwitchTimeStr != null && avgSwitchTimeStr.endsWith("ms")) {
                try {
                    avgTime = Double.parseDouble(avgSwitchTimeStr.replace("ms", ""));
                } catch (NumberFormatException e) {
                    logger.debug("Could not parse average switch time: {}", avgSwitchTimeStr);
                }
            }
            
            // Make variables effectively final for lambda
            final long finalHits = hits;
            final long finalMisses = misses;
            final long finalTotal = total;
            final double finalHitRate = hitRate;
            final double finalAvgTime = avgTime;
            
            // Update properties on JavaFX thread
            Platform.runLater(() -> {
                cacheHits.set(finalHits);
                cacheMisses.set(finalMisses);
                totalSwitches.set(finalTotal);
                cacheHitRate.set(finalHitRate);
                averageSwitchTime.set(finalAvgTime);
            });
            
        } catch (Exception e) {
            logger.error("Error updating TextureManager metrics", e);
        }
    }
    
    /**
     * Update metrics from MainController.
     */
    private void updateMainControllerMetrics() {
        try {
            Map<String, Object> metrics = mainController.getPerformanceMetrics();
            
            // Could extract additional metrics like FPS, memory usage, etc.
            // For now, we focus on texture-related metrics from PropertyPanelController
            
        } catch (Exception e) {
            logger.error("Error updating MainController metrics", e);
        }
    }
    
    /**
     * Check for performance warnings and update warning flags.
     */
    private void checkPerformanceWarnings() {
        boolean perfWarning = averageSwitchTime.get() > PERFORMANCE_WARNING_THRESHOLD;
        boolean cacheWarn = cacheHitRate.get() < CACHE_WARNING_THRESHOLD && totalSwitches.get() > 10;
        
        Platform.runLater(() -> {
            performanceWarning.set(perfWarning);
            cacheWarning.set(cacheWarn);
        });
        
        // Log warnings
        if (perfWarning) {
            logger.warn("Performance warning: Average switch time {:.0f}ms exceeds threshold ({}ms)", 
                averageSwitchTime.get(), PERFORMANCE_WARNING_THRESHOLD);
        }
        
        if (cacheWarn) {
            logger.warn("Cache warning: Hit rate {:.1f}% below threshold ({:.0f}%)", 
                cacheHitRate.get() * 100, CACHE_WARNING_THRESHOLD * 100);
        }
    }
    
    /**
     * Get a summary of current performance metrics.
     */
    public String getPerformanceSummary() {
        return String.format(
            "Performance Summary: Avg Switch=%.0fms, Cache=%.1f%% (%d/%d hits), Total=%d switches",
            averageSwitchTime.get(),
            cacheHitRate.get() * 100,
            cacheHits.get(),
            totalSwitches.get(),
            totalSwitches.get()
        );
    }
    
    /**
     * Reset all performance counters.
     */
    public void resetCounters() {
        Platform.runLater(() -> {
            averageSwitchTime.set(0.0);
            cacheHitRate.set(0.0);
            totalSwitches.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
            performanceWarning.set(false);
            cacheWarning.set(false);
            statusMessage.set("Counters reset");
        });
        
        logger.info("Performance counters reset");
    }
    
    // JavaFX Property Accessors for UI Binding
    
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }
    
    public DoubleProperty averageSwitchTimeProperty() {
        return averageSwitchTime;
    }
    
    public DoubleProperty cacheHitRateProperty() {
        return cacheHitRate;
    }
    
    public LongProperty totalSwitchesProperty() {
        return totalSwitches;
    }
    
    public LongProperty cacheHitsProperty() {
        return cacheHits;
    }
    
    public LongProperty cacheMissesProperty() {
        return cacheMisses;
    }
    
    public BooleanProperty performanceWarningProperty() {
        return performanceWarning;
    }
    
    public BooleanProperty cacheWarningProperty() {
        return cacheWarning;
    }
    
    // Getters for values
    
    public String getStatusMessage() {
        return statusMessage.get();
    }
    
    public double getAverageSwitchTime() {
        return averageSwitchTime.get();
    }
    
    public double getCacheHitRate() {
        return cacheHitRate.get();
    }
    
    public long getTotalSwitches() {
        return totalSwitches.get();
    }
    
    public long getCacheHits() {
        return cacheHits.get();
    }
    
    public long getCacheMisses() {
        return cacheMisses.get();
    }
    
    public boolean hasPerformanceWarning() {
        return performanceWarning.get();
    }
    
    public boolean hasCacheWarning() {
        return cacheWarning.get();
    }
    
    public boolean isMonitoring() {
        return monitoring;
    }
    
    /**
     * Shutdown the performance monitor and cleanup resources.
     */
    public void shutdown() {
        stopMonitoring();
        logger.info("PerformanceMonitor shutdown complete");
    }
}