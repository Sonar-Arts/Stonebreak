package com.openmason.test.performance;

/**
 * Result of a single performance benchmark measurement.
 */
public class BenchmarkResult {
    private final String description;
    private final double executionTimeMs;
    private final long memoryDelta;
    private final boolean successful;
    private final String errorMessage;
    
    public BenchmarkResult(String description, double executionTimeMs, long memoryDelta, 
                          boolean successful, String errorMessage) {
        this.description = description;
        this.executionTimeMs = executionTimeMs;
        this.memoryDelta = memoryDelta;
        this.successful = successful;
        this.errorMessage = errorMessage;
    }
    
    public String getDescription() { return description; }
    public double getExecutionTimeMs() { return executionTimeMs; }
    public long getMemoryDelta() { return memoryDelta; }
    public boolean isSuccessful() { return successful; }
    public String getErrorMessage() { return errorMessage; }
    
    @Override
    public String toString() {
        return String.format("%s: %.2fms, Memory: %d bytes, Success: %s", 
                           description, executionTimeMs, memoryDelta, successful);
    }
}