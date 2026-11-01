package com.openmason.engine.diagnostics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced memory profiler for debugging memory usage and potential leaks.
 */
public class MemoryProfiler {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MemoryProfiler.class);

    private static final MemoryProfiler instance = new MemoryProfiler();
    private final MemoryMXBean memoryMXBean;
    private final Map<String, MemorySnapshot> snapshots;
    private final Map<String, Long> allocationCounters;
    
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
        logger.debug("[MEMORY SNAPSHOT] {} - Heap: {} MB, Non-heap: {} MB",
                label, snapshot.heapUsed / (1024 * 1024), snapshot.nonHeapUsed / (1024 * 1024));
    }
    
    /**
     * Compares two memory snapshots and reports the difference.
     */
    public void compareSnapshots(String beforeLabel, String afterLabel) {
        MemorySnapshot before = snapshots.get(beforeLabel);
        MemorySnapshot after = snapshots.get(afterLabel);
        
        if (before == null || after == null) {
            logger.warn("Cannot compare snapshots - one or both snapshots not found");
            return;
        }

        long heapDiff = after.heapUsed - before.heapUsed;
        long nonHeapDiff = after.nonHeapUsed - before.nonHeapUsed;
        long gcTimeDiff = after.totalGCTime - before.totalGCTime;
        long gcRunsDiff = after.totalGCRuns - before.totalGCRuns;

        logger.debug("[MEMORY COMPARISON] {} -> {}: heap {}{} MB, non-heap {}{} MB, GC time +{} ms, GC runs +{}",
                beforeLabel, afterLabel,
                heapDiff >= 0 ? "+" : "", heapDiff / (1024 * 1024),
                nonHeapDiff >= 0 ? "+" : "", nonHeapDiff / (1024 * 1024),
                gcTimeDiff, gcRunsDiff);

        if (heapDiff > 50 * 1024 * 1024) { // More than 50MB increase
            logger.warn("Significant heap memory increase detected: +{} MB ({} -> {})",
                    heapDiff / (1024 * 1024), beforeLabel, afterLabel);
        }
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