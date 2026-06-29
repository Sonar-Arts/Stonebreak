package com.stonebreak.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced memory leak detection system for the game.
 * Monitors various game components and detects potential memory leaks.
 */
public class MemoryLeakDetector {

    private static final Logger logger = LoggerFactory.getLogger(MemoryLeakDetector.class);

    private static final MemoryLeakDetector instance = new MemoryLeakDetector();
    private final ScheduledExecutorService scheduler;
    private final MemoryProfiler profiler;
    
    // Monitoring state (ZGC-optimized thresholds)
    private long lastHeapUsage = 0;
    private int consecutiveIncreases = 0;
    private static final int LEAK_THRESHOLD = 8; // Consider leak after 8 consecutive increases (less aggressive)
    private static final long MIN_INCREASE_MB = 25; // Minimum 25MB increase to count (higher threshold)
    
    private MemoryLeakDetector() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryLeakDetector");
            t.setDaemon(true);
            return t;
        });
        this.profiler = MemoryProfiler.getInstance();
    }
    
    public static MemoryLeakDetector getInstance() {
        return instance;
    }
    
    /**
     * Starts the memory leak detection monitoring.
     */
    public void startMonitoring() {
        logger.debug("Starting memory leak detection...");
        
        // Take initial snapshot
        profiler.takeSnapshot("leak_detector_start");
        lastHeapUsage = getCurrentHeapUsage();
        
        // Schedule periodic checks every 5 minutes (ZGC-optimized)
        scheduler.scheduleAtFixedRate(this::performLeakCheck, 300, 300, TimeUnit.SECONDS);
        
        // Schedule detailed analysis every 15 minutes
        scheduler.scheduleAtFixedRate(this::performDetailedAnalysis, 900, 900, TimeUnit.SECONDS);
    }
    
    /**
     * Stops the memory leak detection monitoring.
     */
    public void stopMonitoring() {
        logger.debug("Stopping memory leak detection...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Performs a basic leak check by monitoring heap usage trends.
     */
    private void performLeakCheck() {
        long currentHeapUsage = getCurrentHeapUsage();
        long increaseMB = (currentHeapUsage - lastHeapUsage) / (1024 * 1024);
        
        if (increaseMB >= MIN_INCREASE_MB) {
            consecutiveIncreases++;
            logger.debug("[LEAK DETECTOR] Heap increased by {} MB (consecutive increases: {})",
                             increaseMB, consecutiveIncreases);
            
            if (consecutiveIncreases >= LEAK_THRESHOLD) {
                reportPotentialLeak();
                consecutiveIncreases = 0; // Reset to avoid spam
            }
        } else {
            consecutiveIncreases = 0; // Reset counter on stable/decreasing memory
        }
        
        lastHeapUsage = currentHeapUsage;
    }
    
    /**
     * Performs detailed analysis including chunk and resource tracking.
     */
    private void performDetailedAnalysis() {
        logger.debug("[LEAK DETECTOR] Performing detailed analysis...");
        
        profiler.takeSnapshot("detailed_analysis_" + System.currentTimeMillis());
        profiler.reportDetailedMemoryStats();
        
        // Check game-specific metrics
        analyzeGameResources();
        
        // Suggest garbage collection if memory is high
        long heapUsed = getCurrentHeapUsage();
        long heapMax = getMaxHeapUsage();
        double usagePercent = (double) heapUsed / heapMax * 100;
        
        if (usagePercent > 85) {
            logger.debug("[LEAK DETECTOR] High memory usage detected - monitoring for potential leaks...");
            // With ZGC, let garbage collection happen naturally
            // Just monitor and report - don't force GC
            
            // Wait a moment to see if ZGC naturally reclaims memory
            try {
                Thread.sleep(1000); // Brief pause to allow natural GC
                long afterWait = getCurrentHeapUsage();
                long changeMB = (heapUsed - afterWait) / (1024 * 1024);
                
                if (changeMB > 0) {
                    logger.debug("[LEAK DETECTOR] ZGC naturally reclaimed {} MB", changeMB);
                } else if (changeMB < -10) {
                    logger.debug("[LEAK DETECTOR] Memory usage increased by {} MB - monitoring for leaks", Math.abs(changeMB));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Analyzes game-specific resources for potential leaks.
     */
    private void analyzeGameResources() {
        World world = Game.getWorld();
        if (world != null) {
            int loadedChunks = world.getLoadedChunkCount();
            int pendingMesh = world.getPendingMeshBuildCount();
            int pendingGL = world.getPendingGLUploadCount();
            
            logger.debug("[RESOURCE ANALYSIS] Chunks: {} loaded, {} pending mesh, {} pending GL",
                             loadedChunks, pendingMesh, pendingGL);

            // Check for concerning patterns
            if (loadedChunks > 1000) {
                logger.warn("Very high number of loaded chunks ({})! Possible chunk leak.", loadedChunks);
            }

            if (pendingMesh > 100) {
                logger.warn("High number of pending mesh builds ({}). Thread pool may be overwhelmed.", pendingMesh);
            }

            if (pendingGL > 50) {
                logger.warn("High number of pending GL uploads ({}). Main thread may be bottlenecked.", pendingGL);
            }
        }
        
        // Report allocation statistics
        profiler.reportAllocations();
    }
    
    /**
     * Reports a potential memory leak with recommendations.
     */
    private void reportPotentialLeak() {
        logger.warn("POTENTIAL MEMORY LEAK DETECTED! Heap usage has been consistently increasing over time. "
                + "Recommendations: 1. Check chunk loading/unloading logic; 2. Verify OpenGL resources are being "
                + "cleaned up; 3. Look for growing collections or caches; 4. Check for unclosed resources or listeners.");
        
        // Take emergency snapshot for analysis
        profiler.takeSnapshot("potential_leak_" + System.currentTimeMillis());
        profiler.reportDetailedMemoryStats();
        
        // Try to get more details about what's consuming memory
        analyzeGameResources();
    }
    
    /**
     * Manually triggers a comprehensive leak analysis.
     */
    public void triggerLeakAnalysis() {
        logger.debug("[LEAK DETECTOR] Manual leak analysis triggered...");
        profiler.takeSnapshot("manual_analysis_before");
        
        // Monitor memory without forcing GC (ZGC-optimized approach)
        long beforeMonitor = getCurrentHeapUsage();
        
        try {
            Thread.sleep(3000); // Wait to observe natural memory behavior
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long afterMonitor = getCurrentHeapUsage();
        profiler.takeSnapshot("manual_analysis_after_monitor");
        
        long changeMB = (beforeMonitor - afterMonitor) / (1024 * 1024);
        if (changeMB > 0) {
            logger.debug("[LEAK DETECTOR] ZGC naturally reclaimed {} MB during analysis", changeMB);
        } else {
            logger.debug("[LEAK DETECTOR] Memory usage changed by {}{} MB - analyzing patterns",
                    -changeMB >= 0 ? "+" : "", -changeMB);
        }
        
        profiler.compareSnapshots("manual_analysis_before", "manual_analysis_after_monitor");
        analyzeGameResources();
    }
    
    private long getCurrentHeapUsage() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    
    private long getMaxHeapUsage() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }
}