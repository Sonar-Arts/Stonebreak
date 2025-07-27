package com.openmason.test.performance;

import java.util.List;

/**
 * Result of a frame rate performance benchmark.
 */
public class FrameRateBenchmarkResult {
    private final String description;
    private final int frameCount;
    private final double totalTimeSeconds;
    private final double averageFPS;
    private final double averageFrameTime;
    private final double minFrameTime;
    private final double maxFrameTime;
    private final double standardDeviation;
    private final long memoryDelta;
    private final List<Double> frameTimes;
    
    public FrameRateBenchmarkResult(String description, int frameCount, double totalTimeSeconds,
                                   double averageFPS, double averageFrameTime, double minFrameTime,
                                   double maxFrameTime, double standardDeviation, long memoryDelta,
                                   List<Double> frameTimes) {
        this.description = description;
        this.frameCount = frameCount;
        this.totalTimeSeconds = totalTimeSeconds;
        this.averageFPS = averageFPS;
        this.averageFrameTime = averageFrameTime;
        this.minFrameTime = minFrameTime;
        this.maxFrameTime = maxFrameTime;
        this.standardDeviation = standardDeviation;
        this.memoryDelta = memoryDelta;
        this.frameTimes = frameTimes;
    }
    
    public String getDescription() { return description; }
    public int getFrameCount() { return frameCount; }
    public double getTotalTimeSeconds() { return totalTimeSeconds; }
    public double getAverageFPS() { return averageFPS; }
    public double getAverageFrameTime() { return averageFrameTime; }
    public double getMinFrameTime() { return minFrameTime; }
    public double getMaxFrameTime() { return maxFrameTime; }
    public double getStandardDeviation() { return standardDeviation; }
    public long getMemoryDelta() { return memoryDelta; }
    public List<Double> getFrameTimes() { return frameTimes; }
    
    @Override
    public String toString() {
        return String.format("%s: %.1f FPS avg (%.2f-%.2fms), %d frames in %.1fs",
                           description, averageFPS, minFrameTime, maxFrameTime, frameCount, totalTimeSeconds);
    }
}