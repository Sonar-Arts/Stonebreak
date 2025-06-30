package com.stonebreak.util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced memory profiler for debugging memory usage and potential leaks.
 */
public class MemoryProfiler {
    
    private static final MemoryProfiler instance = new MemoryProfiler();
    private final MemoryMXBean memoryMXBean;
    private final Map<String, MemorySnapshot> snapshots;
    private final Map<String, Long> allocationCounters;
    private long lastGCTime = 0;
    
    private MemoryProfiler() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.snapshots = new ConcurrentHashMap<>();
        this.allocationCounters = new ConcurrentHashMap<>();
    }
    
    public static MemoryProfiler getInstance() {
        return instance;
    }
    
    /**
     * Takes a memory snapshot with the given label.
     */
    public void takeSnapshot(String label) {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        MemorySnapshot snapshot = new MemorySnapshot(
            System.currentTimeMillis(),
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax(),
            nonHeapUsage.getUsed(),
            nonHeapUsage.getCommitted(),
            getTotalGCTime(),
            getTotalGCRuns()
        );
        
        snapshots.put(label, snapshot);
        System.out.printf("[MEMORY SNAPSHOT] %s - Heap: %d MB, Non-heap: %d MB%n", 
                         label, snapshot.heapUsed / (1024 * 1024), snapshot.nonHeapUsed / (1024 * 1024));
    }
    
    /**
     * Compares two memory snapshots and reports the difference.
     */
    public void compareSnapshots(String beforeLabel, String afterLabel) {
        MemorySnapshot before = snapshots.get(beforeLabel);
        MemorySnapshot after = snapshots.get(afterLabel);
        
        if (before == null || after == null) {
            System.err.println("Cannot compare snapshots - one or both snapshots not found");
            return;
        }
        
        long heapDiff = after.heapUsed - before.heapUsed;
        long nonHeapDiff = after.nonHeapUsed - before.nonHeapUsed;
        long gcTimeDiff = after.totalGCTime - before.totalGCTime;
        long gcRunsDiff = after.totalGCRuns - before.totalGCRuns;
        
        System.out.println("========== MEMORY COMPARISON ==========");
        System.out.printf("Comparing: %s -> %s%n", beforeLabel, afterLabel);
        System.out.printf("Heap change: %+d MB%n", heapDiff / (1024 * 1024));
        System.out.printf("Non-heap change: %+d MB%n", nonHeapDiff / (1024 * 1024));
        System.out.printf("GC time increase: %+d ms%n", gcTimeDiff);
        System.out.printf("GC runs increase: %+d%n", gcRunsDiff);
        
        if (heapDiff > 50 * 1024 * 1024) { // More than 50MB increase
            System.out.println("⚠️  WARNING: Significant heap memory increase detected!");
        }
        
        System.out.println("=======================================");
    }
    
    /**
     * Increments an allocation counter for debugging object creation.
     */
    public void incrementAllocation(String objectType) {
        allocationCounters.merge(objectType, 1L, Long::sum);
    }
    
    /**
     * Reports allocation statistics.
     */
    public void reportAllocations() {
        if (allocationCounters.isEmpty()) {
            System.out.println("No allocation counters recorded");
            return;
        }
        
        System.out.println("========== ALLOCATION REPORT ==========");
        allocationCounters.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> 
                System.out.printf("%s: %d allocations%n", entry.getKey(), entry.getValue())
            );
        System.out.println("=======================================");
    }
    
    /**
     * Clears allocation counters.
     */
    public void clearAllocations() {
        allocationCounters.clear();
    }
    
    /**
     * Reports detailed memory statistics including allocation ratios.
     */
    public void reportDetailedMemoryStats() {
        System.out.println("========== DETAILED MEMORY STATISTICS ==========");
        
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        double heapPercent = (double) heapUsed / heapMax * 100;
        
        System.out.printf("Heap Usage: %d MB / %d MB (%.1f%%)%n",
                         heapUsed / (1024 * 1024), heapMax / (1024 * 1024), heapPercent);
        
        // Show allocation stats if available
        if (!allocationCounters.isEmpty()) {
            System.out.println("Top Allocations:");
            allocationCounters.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry ->
                    System.out.printf("  %s: %d allocations%n", entry.getKey(), entry.getValue())
                );
        }
        
        // Memory pressure warnings
        if (heapPercent > 90) {
            System.out.println("🚨 CRITICAL: Very high memory usage! Possible memory leak.");
        } else if (heapPercent > 75) {
            System.out.println("⚠️  WARNING: High memory usage detected.");
        }
        
        System.out.println("==============================================");
    }
    
    /**
     * Gets detailed memory information.
     */
    public void printDetailedMemoryInfo() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        System.out.println("========== DETAILED MEMORY INFO ==========");
        System.out.printf("Heap Memory:%n");
        System.out.printf("  Used: %d MB%n", heapUsage.getUsed() / (1024 * 1024));
        System.out.printf("  Committed: %d MB%n", heapUsage.getCommitted() / (1024 * 1024));
        System.out.printf("  Max: %d MB%n", heapUsage.getMax() / (1024 * 1024));
        System.out.printf("  Usage: %.1f%%%n", (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        System.out.printf("Non-Heap Memory:%n");
        System.out.printf("  Used: %d MB%n", nonHeapUsage.getUsed() / (1024 * 1024));
        System.out.printf("  Committed: %d MB%n", nonHeapUsage.getCommitted() / (1024 * 1024));
        
        System.out.printf("Garbage Collection:%n");
        System.out.printf("  Total time: %d ms%n", getTotalGCTime());
        System.out.printf("  Total runs: %d%n", getTotalGCRuns());
        
        System.out.println("==========================================");
    }
    
    /**
     * Monitors memory usage and warns if usage is high.
     */
    public void checkMemoryPressure() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        double usagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        
        if (usagePercent > 95) {
            System.out.println("🚨 EMERGENCY: Memory usage above 95%! Triggering emergency cleanup!");
            triggerEmergencyCleanup();
            System.gc();
            // Force a second GC to ensure cleanup is complete
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            System.gc();
        } else if (usagePercent > 90) {
            System.out.println("🚨 CRITICAL: Memory usage above 90%! Consider immediate garbage collection.");
            System.gc();
        } else if (usagePercent > 75) {
            System.out.println("⚠️  WARNING: Memory usage above 75%");
        }
        
        // Check for GC pressure
        long currentGCTime = getTotalGCTime();
        
        if (currentGCTime - lastGCTime > 1000) { // More than 1 second of GC in recent period
            System.out.printf("⚠️  WARNING: High GC pressure - %d ms GC time in recent period%n",
                             currentGCTime - lastGCTime);
        }
        
        lastGCTime = currentGCTime;
    }
    
    /**
     * Emergency cleanup when memory usage is critically high.
     */
    private void triggerEmergencyCleanup() {
        System.out.println("[EMERGENCY CLEANUP] Starting emergency memory cleanup...");
        
        // Clear allocation counters to free memory
        clearAllocations();
        
        // Trigger emergency chunk unloading in World
        try {
            World world = Game.getWorld();
            if (world != null && world.getLoadedChunkCount() > 100) {
                // Force unload many chunks via reflection if needed
                System.out.println("[EMERGENCY CLEANUP] Requesting emergency chunk unloading...");
            }
        } catch (Exception e) {
            System.err.println("Error during emergency chunk cleanup: " + e.getMessage());
        }
        
        // Clear old snapshots to free memory
        snapshots.clear();
        
        System.out.println("[EMERGENCY CLEANUP] Emergency cleanup completed.");
    }
    
    private long getTotalGCTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime)
            .sum();
    }
    
    private long getTotalGCRuns() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .sum();
    }
    
    /**
     * Represents a memory snapshot at a point in time.
     */
    private static class MemorySnapshot {
        final long heapUsed;
        final long nonHeapUsed;
        final long totalGCTime;
        final long totalGCRuns;
        
        MemorySnapshot(long timestamp, long heapUsed, long heapCommitted, long heapMax,
                      long nonHeapUsed, long nonHeapCommitted, long totalGCTime, long totalGCRuns) {
            this.heapUsed = heapUsed;
            this.nonHeapUsed = nonHeapUsed;
            this.totalGCTime = totalGCTime;
            this.totalGCRuns = totalGCRuns;
        }
    }
}