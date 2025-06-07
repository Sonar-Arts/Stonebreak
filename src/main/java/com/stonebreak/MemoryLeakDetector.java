package com.stonebreak;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Advanced memory leak detection system for the game.
 * Monitors various game components and detects potential memory leaks.
 */
public class MemoryLeakDetector {
    
    private static final MemoryLeakDetector instance = new MemoryLeakDetector();
    private final ScheduledExecutorService scheduler;
    private final MemoryProfiler profiler;
    
    // Monitoring state
    private long lastHeapUsage = 0;
    private int consecutiveIncreases = 0;
    private static final int LEAK_THRESHOLD = 5; // Consider leak after 5 consecutive increases
    private static final long MIN_INCREASE_MB = 10; // Minimum 10MB increase to count
    
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
        System.out.println("Starting memory leak detection...");
        
        // Take initial snapshot
        profiler.takeSnapshot("leak_detector_start");
        lastHeapUsage = getCurrentHeapUsage();
        
        // Schedule periodic checks every 30 seconds
        scheduler.scheduleAtFixedRate(this::performLeakCheck, 30, 30, TimeUnit.SECONDS);
        
        // Schedule detailed analysis every 5 minutes
        scheduler.scheduleAtFixedRate(this::performDetailedAnalysis, 300, 300, TimeUnit.SECONDS);
    }
    
    /**
     * Stops the memory leak detection monitoring.
     */
    public void stopMonitoring() {
        System.out.println("Stopping memory leak detection...");
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
            System.out.printf("[LEAK DETECTOR] Heap increased by %d MB (consecutive increases: %d)%n", 
                             increaseMB, consecutiveIncreases);
            
            if (consecutiveIncreases >= LEAK_THRESHOLD) {
                reportPotentialLeak();
                consecutiveIncreases = 0; // Reset to avoid spam
            }
        } else {
            consecutiveIncreases = 0; // Reset counter on stable/decreasing memory
        }
        
        lastHeapUsage = currentHeapUsage;
        profiler.checkMemoryPressure();
    }
    
    /**
     * Performs detailed analysis including chunk and resource tracking.
     */
    private void performDetailedAnalysis() {
        System.out.println("[LEAK DETECTOR] Performing detailed analysis...");
        
        profiler.takeSnapshot("detailed_analysis_" + System.currentTimeMillis());
        profiler.reportDetailedMemoryStats();
        
        // Check game-specific metrics
        analyzeGameResources();
        
        // Suggest garbage collection if memory is high
        long heapUsed = getCurrentHeapUsage();
        long heapMax = getMaxHeapUsage();
        double usagePercent = (double) heapUsed / heapMax * 100;
        
        if (usagePercent > 70) {
            System.out.println("[LEAK DETECTOR] High memory usage detected, suggesting GC...");
            System.gc();
            
            // Check if GC helped
            try {
                Thread.sleep(2000); // Wait for GC to complete
                long afterGC = getCurrentHeapUsage();
                long freedMB = (heapUsed - afterGC) / (1024 * 1024);
                System.out.printf("[LEAK DETECTOR] GC freed %d MB%n", freedMB);
                
                if (freedMB < 50) { // Less than 50MB freed
                    System.err.println("⚠️  WARNING: GC freed very little memory. Possible memory leak!");
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
            
            System.out.printf("[RESOURCE ANALYSIS] Chunks: %d loaded, %d pending mesh, %d pending GL%n",
                             loadedChunks, pendingMesh, pendingGL);
            
            // Check for concerning patterns
            if (loadedChunks > 1000) {
                System.err.println("⚠️  WARNING: Very high number of loaded chunks! Possible chunk leak.");
            }
            
            if (pendingMesh > 100) {
                System.err.println("⚠️  WARNING: High number of pending mesh builds. Thread pool may be overwhelmed.");
            }
            
            if (pendingGL > 50) {
                System.err.println("⚠️  WARNING: High number of pending GL uploads. Main thread may be bottlenecked.");
            }
        }
        
        // Report allocation statistics
        profiler.reportAllocations();
    }
    
    /**
     * Reports a potential memory leak with recommendations.
     */
    private void reportPotentialLeak() {
        System.err.println("🚨 POTENTIAL MEMORY LEAK DETECTED!");
        System.err.println("Heap usage has been consistently increasing over time.");
        System.err.println("Recommendations:");
        System.err.println("1. Check chunk loading/unloading logic");
        System.err.println("2. Verify OpenGL resources are being cleaned up");
        System.err.println("3. Look for growing collections or caches");
        System.err.println("4. Check for unclosed resources or listeners");
        
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
        System.out.println("[LEAK DETECTOR] Manual leak analysis triggered...");
        profiler.takeSnapshot("manual_analysis_before");
        
        // Force GC and measure impact
        long beforeGC = getCurrentHeapUsage();
        System.gc();
        
        try {
            Thread.sleep(2000); // Wait for GC
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long afterGC = getCurrentHeapUsage();
        profiler.takeSnapshot("manual_analysis_after_gc");
        
        long freedMB = (beforeGC - afterGC) / (1024 * 1024);
        System.out.printf("[LEAK DETECTOR] Manual GC freed %d MB%n", freedMB);
        
        profiler.compareSnapshots("manual_analysis_before", "manual_analysis_after_gc");
        analyzeGameResources();
    }
    
    private long getCurrentHeapUsage() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    
    private long getMaxHeapUsage() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }
}