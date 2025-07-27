package com.openmason.test.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Performance benchmarking utility for Phase 3 testing.
 * 
 * Provides comprehensive performance measurement and validation tools
 * for testing OpenMason's 3D viewport, camera system, and rendering
 * performance under various conditions.
 */
public class PerformanceBenchmark {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceBenchmark.class);
    
    // Performance targets for validation
    public static final double TARGET_FPS = 60.0;
    public static final double MINIMUM_FPS = 30.0;
    public static final double CRITICAL_FPS = 15.0;
    public static final long MAXIMUM_FRAME_TIME_MS = 33; // 30 FPS
    public static final long TARGET_FRAME_TIME_MS = 16; // 60 FPS
    
    // Memory thresholds
    public static final long MEMORY_WARNING_THRESHOLD = 200 * 1024 * 1024; // 200MB
    public static final long MEMORY_CRITICAL_THRESHOLD = 500 * 1024 * 1024; // 500MB
    
    private final List<BenchmarkResult> results = new ArrayList<>();
    private final String benchmarkName;
    
    public PerformanceBenchmark(String benchmarkName) {
        this.benchmarkName = benchmarkName;
    }
    
    /**
     * Measures the execution time of a single operation.
     * 
     * @param operation The operation to measure
     * @param description Description of the operation
     * @return Benchmark result
     */
    public BenchmarkResult measureSingle(Runnable operation, String description) {
        return measureSingle(() -> {
            operation.run();
            return null;
        }, description);
    }
    
    /**
     * Measures the execution time of a single operation with return value.
     * 
     * @param operation The operation to measure
     * @param description Description of the operation
     * @param <T> Return type
     * @return Benchmark result
     */
    public <T> BenchmarkResult measureSingle(Callable<T> operation, String description) {
        logger.debug("Starting benchmark: {}", description);
        
        // Warm up JVM
        try {
            operation.call();
        } catch (Exception e) {
            logger.warn("Warmup failed for {}: {}", description, e.getMessage());
        }
        
        // Measure execution
        Instant start = Instant.now();
        long memoryBefore = getUsedMemory();
        
        T result = null;
        Exception error = null;
        
        try {
            result = operation.call();
        } catch (Exception e) {
            error = e;
            logger.warn("Benchmark operation failed: {}", e.getMessage());
        }
        
        Instant end = Instant.now();
        long memoryAfter = getUsedMemory();
        
        Duration duration = Duration.between(start, end);
        long memoryDelta = memoryAfter - memoryBefore;
        
        BenchmarkResult benchmarkResult = new BenchmarkResult(
            description,
            duration.toNanos() / 1_000_000.0, // Convert to milliseconds
            memoryDelta,
            error == null,
            error != null ? error.getMessage() : null
        );
        
        results.add(benchmarkResult);
        
        logger.debug("Benchmark completed: {} - {:.2f}ms, Memory: {} bytes", 
                    description, benchmarkResult.getExecutionTimeMs(), memoryDelta);
        
        return benchmarkResult;
    }
    
    /**
     * Measures frame rate performance over a period of time.
     * 
     * @param frameRenderer Function that renders a single frame
     * @param durationSeconds How long to measure for
     * @param description Description of the test
     * @return Frame rate benchmark result
     */
    public FrameRateBenchmarkResult measureFrameRate(Runnable frameRenderer, 
                                                    double durationSeconds, 
                                                    String description) {
        logger.debug("Starting frame rate benchmark: {} for {:.1f}s", description, durationSeconds);
        
        List<Double> frameTimes = new ArrayList<>();
        long startTime = System.nanoTime();
        long endTime = startTime + (long) (durationSeconds * 1_000_000_000L);
        long memoryBefore = getUsedMemory();
        
        int frameCount = 0;
        long lastFrameTime = startTime;
        
        while (System.nanoTime() < endTime) {
            long frameStart = System.nanoTime();
            
            try {
                frameRenderer.run();
            } catch (Exception e) {
                logger.warn("Frame rendering failed: {}", e.getMessage());
            }
            
            long frameEnd = System.nanoTime();
            double frameTimeMs = (frameEnd - frameStart) / 1_000_000.0;
            frameTimes.add(frameTimeMs);
            
            frameCount++;
            lastFrameTime = frameEnd;
            
            // Simulate frame pacing
            try {
                Thread.sleep(1); // Minimum frame spacing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        long totalTime = lastFrameTime - startTime;
        long memoryAfter = getUsedMemory();
        
        // Calculate statistics
        double totalTimeSeconds = totalTime / 1_000_000_000.0;
        double averageFPS = frameCount / totalTimeSeconds;
        double averageFrameTime = frameTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double minFrameTime = frameTimes.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maxFrameTime = frameTimes.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        
        // Calculate variance
        double variance = frameTimes.stream()
            .mapToDouble(time -> Math.pow(time - averageFrameTime, 2))
            .average().orElse(0.0);
        double standardDeviation = Math.sqrt(variance);
        
        FrameRateBenchmarkResult result = new FrameRateBenchmarkResult(
            description,
            frameCount,
            totalTimeSeconds,
            averageFPS,
            averageFrameTime,
            minFrameTime,
            maxFrameTime,
            standardDeviation,
            memoryAfter - memoryBefore,
            new ArrayList<>(frameTimes)
        );
        
        logger.info("Frame rate benchmark completed: {} - {:.1f} FPS avg, {:.2f}ms avg frame time", 
                   description, averageFPS, averageFrameTime);
        
        return result;
    }
    
    /**
     * Measures memory allocation performance.
     * 
     * @param operation Operation to measure
     * @param iterations Number of iterations
     * @param description Description of the test
     * @return Memory benchmark result
     */
    public MemoryBenchmarkResult measureMemory(Runnable operation, int iterations, String description) {
        logger.debug("Starting memory benchmark: {} for {} iterations", description, iterations);
        
        // Force garbage collection before measurement
        forceGC();
        long memoryBefore = getUsedMemory();
        
        Instant start = Instant.now();
        
        for (int i = 0; i < iterations; i++) {
            try {
                operation.run();
            } catch (Exception e) {
                logger.warn("Memory benchmark iteration {} failed: {}", i, e.getMessage());
            }
        }
        
        Instant end = Instant.now();
        long memoryAfter = getUsedMemory();
        
        // Force GC to measure retained memory
        forceGC();
        long memoryAfterGC = getUsedMemory();
        
        Duration duration = Duration.between(start, end);
        
        MemoryBenchmarkResult result = new MemoryBenchmarkResult(
            description,
            iterations,
            duration.toNanos() / 1_000_000.0,
            memoryAfter - memoryBefore,
            memoryAfterGC - memoryBefore
        );
        
        logger.debug("Memory benchmark completed: {} - {:.2f}ms total, {} bytes allocated, {} bytes retained", 
                    description, result.getExecutionTimeMs(), result.getTotalAllocated(), result.getRetainedMemory());
        
        return result;
    }
    
    /**
     * Validates that performance meets minimum requirements.
     * 
     * @param frameRateResult Frame rate benchmark result
     * @return Performance validation result
     */
    public PerformanceValidationResult validatePerformance(FrameRateBenchmarkResult frameRateResult) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // Check FPS requirements
        if (frameRateResult.getAverageFPS() < CRITICAL_FPS) {
            issues.add(String.format("Critical: Average FPS %.1f is below critical threshold %.1f", 
                       frameRateResult.getAverageFPS(), CRITICAL_FPS));
        } else if (frameRateResult.getAverageFPS() < MINIMUM_FPS) {
            warnings.add(String.format("Warning: Average FPS %.1f is below minimum threshold %.1f", 
                         frameRateResult.getAverageFPS(), MINIMUM_FPS));
        }
        
        // Check frame time consistency
        if (frameRateResult.getStandardDeviation() > 10.0) {
            warnings.add(String.format("Warning: High frame time variance %.2fms indicates inconsistent performance", 
                         frameRateResult.getStandardDeviation()));
        }
        
        // Check worst-case frame times
        if (frameRateResult.getMaxFrameTime() > MAXIMUM_FRAME_TIME_MS) {
            warnings.add(String.format("Warning: Maximum frame time %.2fms exceeds threshold %dms", 
                         frameRateResult.getMaxFrameTime(), MAXIMUM_FRAME_TIME_MS));
        }
        
        // Check memory usage
        if (frameRateResult.getMemoryDelta() > MEMORY_CRITICAL_THRESHOLD) {
            issues.add(String.format("Critical: Memory usage increase %s exceeds critical threshold %s", 
                       formatBytes(frameRateResult.getMemoryDelta()), formatBytes(MEMORY_CRITICAL_THRESHOLD)));
        } else if (frameRateResult.getMemoryDelta() > MEMORY_WARNING_THRESHOLD) {
            warnings.add(String.format("Warning: Memory usage increase %s exceeds warning threshold %s", 
                         formatBytes(frameRateResult.getMemoryDelta()), formatBytes(MEMORY_WARNING_THRESHOLD)));
        }
        
        PerformanceLevel level;
        if (!issues.isEmpty()) {
            level = PerformanceLevel.CRITICAL;
        } else if (!warnings.isEmpty()) {
            level = PerformanceLevel.WARNING;
        } else if (frameRateResult.getAverageFPS() >= TARGET_FPS) {
            level = PerformanceLevel.EXCELLENT;
        } else {
            level = PerformanceLevel.ACCEPTABLE;
        }
        
        return new PerformanceValidationResult(level, issues, warnings);
    }
    
    /**
     * Gets all benchmark results collected so far.
     * 
     * @return List of benchmark results
     */
    public List<BenchmarkResult> getResults() {
        return new ArrayList<>(results);
    }
    
    /**
     * Clears all collected results.
     */
    public void clearResults() {
        results.clear();
    }
    
    /**
     * Gets the name of this benchmark suite.
     * 
     * @return Benchmark name
     */
    public String getBenchmarkName() {
        return benchmarkName;
    }
    
    // Private utility methods
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    private void forceGC() {
        System.gc();
        System.runFinalization();
        try {
            Thread.sleep(100); // Allow GC to complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
    
    // Enum for performance levels
    public enum PerformanceLevel {
        EXCELLENT, ACCEPTABLE, WARNING, CRITICAL
    }
}