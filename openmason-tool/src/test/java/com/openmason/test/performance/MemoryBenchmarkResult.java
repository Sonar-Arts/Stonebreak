package com.openmason.test.performance;

/**
 * Result of a memory allocation performance benchmark.
 */
public class MemoryBenchmarkResult {
    private final String description;
    private final int iterations;
    private final double executionTimeMs;
    private final long totalAllocated;
    private final long retainedMemory;
    
    public MemoryBenchmarkResult(String description, int iterations, double executionTimeMs,
                                long totalAllocated, long retainedMemory) {
        this.description = description;
        this.iterations = iterations;
        this.executionTimeMs = executionTimeMs;
        this.totalAllocated = totalAllocated;
        this.retainedMemory = retainedMemory;
    }
    
    public String getDescription() { return description; }
    public int getIterations() { return iterations; }
    public double getExecutionTimeMs() { return executionTimeMs; }
    public long getTotalAllocated() { return totalAllocated; }
    public long getRetainedMemory() { return retainedMemory; }
    
    public double getAllocationRate() {
        return executionTimeMs > 0 ? totalAllocated / executionTimeMs : 0;
    }
    
    @Override
    public String toString() {
        return String.format("%s: %d iterations, %.2fms, %d allocated, %d retained",
                           description, iterations, executionTimeMs, totalAllocated, retainedMemory);
    }
}