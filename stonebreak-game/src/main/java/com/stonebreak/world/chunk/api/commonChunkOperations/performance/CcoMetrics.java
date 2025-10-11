package com.stonebreak.world.chunk.api.commonChunkOperations.performance;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CCO Metrics - Performance tracking for chunk operations
 *
 * Responsibilities:
 * - Track operation counts and timings
 * - Lock-free metric updates
 * - Calculate rates and averages
 * - Provide performance insights
 *
 * Design: Lock-free counters with minimal overhead
 * Performance: < 50ns per metric update
 *
 * Tracked Metrics:
 * - Block read/write operations
 * - Mesh generation timing
 * - Buffer upload timing
 * - State transitions
 * - Serialization operations
 */
public final class CcoMetrics {

    // Operation counters (lock-free)
    private final LongAdder blockReads = new LongAdder();
    private final LongAdder blockWrites = new LongAdder();
    private final LongAdder bulkOperations = new LongAdder();
    private final LongAdder meshGenerations = new LongAdder();
    private final LongAdder bufferUploads = new LongAdder();
    private final LongAdder stateTransitions = new LongAdder();
    private final LongAdder serializations = new LongAdder();
    private final LongAdder deserializations = new LongAdder();

    // Timing accumulators (nanoseconds)
    private final LongAdder meshGenTime = new LongAdder();
    private final LongAdder uploadTime = new LongAdder();
    private final LongAdder serializeTime = new LongAdder();
    private final LongAdder deserializeTime = new LongAdder();

    // Peak tracking
    private final AtomicLong peakMeshGenTime = new AtomicLong(0);
    private final AtomicLong peakUploadTime = new AtomicLong(0);
    private final AtomicLong peakSerializeTime = new AtomicLong(0);

    // Session start time for rate calculations
    private final long sessionStartNanos = System.nanoTime();

    /**
     * Record a block read operation
     */
    public void recordBlockRead() {
        blockReads.increment();
    }

    /**
     * Record multiple block reads
     *
     * @param count Number of reads
     */
    public void recordBlockReads(long count) {
        blockReads.add(count);
    }

    /**
     * Record a block write operation
     */
    public void recordBlockWrite() {
        blockWrites.increment();
    }

    /**
     * Record multiple block writes
     *
     * @param count Number of writes
     */
    public void recordBlockWrites(long count) {
        blockWrites.add(count);
    }

    /**
     * Record a bulk operation
     *
     * @param affectedBlocks Number of blocks affected
     */
    public void recordBulkOperation(long affectedBlocks) {
        bulkOperations.increment();
        blockWrites.add(affectedBlocks);
    }

    /**
     * Record mesh generation
     *
     * @param durationNanos Time taken in nanoseconds
     */
    public void recordMeshGeneration(long durationNanos) {
        meshGenerations.increment();
        meshGenTime.add(durationNanos);
        updatePeak(peakMeshGenTime, durationNanos);
    }

    /**
     * Record buffer upload
     *
     * @param durationNanos Time taken in nanoseconds
     */
    public void recordBufferUpload(long durationNanos) {
        bufferUploads.increment();
        uploadTime.add(durationNanos);
        updatePeak(peakUploadTime, durationNanos);
    }

    /**
     * Record state transition
     */
    public void recordStateTransition() {
        stateTransitions.increment();
    }

    /**
     * Record serialization operation
     *
     * @param durationNanos Time taken in nanoseconds
     */
    public void recordSerialization(long durationNanos) {
        serializations.increment();
        serializeTime.add(durationNanos);
        updatePeak(peakSerializeTime, durationNanos);
    }

    /**
     * Record deserialization operation
     *
     * @param durationNanos Time taken in nanoseconds
     */
    public void recordDeserialization(long durationNanos) {
        deserializations.increment();
        deserializeTime.add(durationNanos);
    }

    /**
     * Update peak value atomically
     *
     * @param peak Peak atomic long
     * @param value New value
     */
    private void updatePeak(AtomicLong peak, long value) {
        long current;
        do {
            current = peak.get();
            if (value <= current) return;
        } while (!peak.compareAndSet(current, value));
    }

    /**
     * Get total block reads
     *
     * @return Read count
     */
    public long getBlockReads() {
        return blockReads.sum();
    }

    /**
     * Get total block writes
     *
     * @return Write count
     */
    public long getBlockWrites() {
        return blockWrites.sum();
    }

    /**
     * Get total bulk operations
     *
     * @return Bulk operation count
     */
    public long getBulkOperations() {
        return bulkOperations.sum();
    }

    /**
     * Get total mesh generations
     *
     * @return Mesh generation count
     */
    public long getMeshGenerations() {
        return meshGenerations.sum();
    }

    /**
     * Get average mesh generation time
     *
     * @return Average time in microseconds (0 if no operations)
     */
    public long getAverageMeshGenTimeMicros() {
        long count = meshGenerations.sum();
        if (count == 0) return 0;
        return meshGenTime.sum() / count / 1000;
    }

    /**
     * Get peak mesh generation time
     *
     * @return Peak time in microseconds
     */
    public long getPeakMeshGenTimeMicros() {
        return peakMeshGenTime.get() / 1000;
    }

    /**
     * Get total buffer uploads
     *
     * @return Upload count
     */
    public long getBufferUploads() {
        return bufferUploads.sum();
    }

    /**
     * Get average buffer upload time
     *
     * @return Average time in microseconds (0 if no operations)
     */
    public long getAverageUploadTimeMicros() {
        long count = bufferUploads.sum();
        if (count == 0) return 0;
        return uploadTime.sum() / count / 1000;
    }

    /**
     * Get peak buffer upload time
     *
     * @return Peak time in microseconds
     */
    public long getPeakUploadTimeMicros() {
        return peakUploadTime.get() / 1000;
    }

    /**
     * Get total state transitions
     *
     * @return Transition count
     */
    public long getStateTransitions() {
        return stateTransitions.sum();
    }

    /**
     * Get total serializations
     *
     * @return Serialization count
     */
    public long getSerializations() {
        return serializations.sum();
    }

    /**
     * Get average serialization time
     *
     * @return Average time in microseconds (0 if no operations)
     */
    public long getAverageSerializeTimeMicros() {
        long count = serializations.sum();
        if (count == 0) return 0;
        return serializeTime.sum() / count / 1000;
    }

    /**
     * Get peak serialization time
     *
     * @return Peak time in microseconds
     */
    public long getPeakSerializeTimeMicros() {
        return peakSerializeTime.get() / 1000;
    }

    /**
     * Get total deserializations
     *
     * @return Deserialization count
     */
    public long getDeserializations() {
        return deserializations.sum();
    }

    /**
     * Get average deserialization time
     *
     * @return Average time in microseconds (0 if no operations)
     */
    public long getAverageDeserializeTimeMicros() {
        long count = deserializations.sum();
        if (count == 0) return 0;
        return deserializeTime.sum() / count / 1000;
    }

    /**
     * Get operations per second
     *
     * @return Total operations per second
     */
    public double getOperationsPerSecond() {
        long totalOps = blockReads.sum() + blockWrites.sum() + bulkOperations.sum() +
                       meshGenerations.sum() + bufferUploads.sum();
        long elapsedNanos = System.nanoTime() - sessionStartNanos;
        if (elapsedNanos == 0) return 0;
        return totalOps * 1_000_000_000.0 / elapsedNanos;
    }

    /**
     * Get session uptime
     *
     * @return Uptime in seconds
     */
    public double getUptimeSeconds() {
        return (System.nanoTime() - sessionStartNanos) / 1_000_000_000.0;
    }

    /**
     * Reset all metrics to zero
     *
     * Thread-safety: Best effort reset, not atomic across all counters
     */
    public void reset() {
        blockReads.reset();
        blockWrites.reset();
        bulkOperations.reset();
        meshGenerations.reset();
        bufferUploads.reset();
        stateTransitions.reset();
        serializations.reset();
        deserializations.reset();

        meshGenTime.reset();
        uploadTime.reset();
        serializeTime.reset();
        deserializeTime.reset();

        peakMeshGenTime.set(0);
        peakUploadTime.set(0);
        peakSerializeTime.set(0);
    }

    /**
     * Get comprehensive metrics summary
     *
     * @return Human-readable metrics report
     */
    public String getSummary() {
        return String.format(
            "CCO Metrics Summary (%.1fs uptime):\n" +
            "  Operations:\n" +
            "    Block Reads:       %,d\n" +
            "    Block Writes:      %,d\n" +
            "    Bulk Operations:   %,d\n" +
            "    State Transitions: %,d\n" +
            "    Ops/sec:           %.1f\n" +
            "  Mesh Generation:\n" +
            "    Count:             %,d\n" +
            "    Avg Time:          %,d μs\n" +
            "    Peak Time:         %,d μs\n" +
            "  Buffer Upload:\n" +
            "    Count:             %,d\n" +
            "    Avg Time:          %,d μs\n" +
            "    Peak Time:         %,d μs\n" +
            "  Serialization:\n" +
            "    Saves:             %,d (avg %,d μs)\n" +
            "    Loads:             %,d (avg %,d μs)\n" +
            "    Peak Save Time:    %,d μs",
            getUptimeSeconds(),
            getBlockReads(),
            getBlockWrites(),
            getBulkOperations(),
            getStateTransitions(),
            getOperationsPerSecond(),
            getMeshGenerations(),
            getAverageMeshGenTimeMicros(),
            getPeakMeshGenTimeMicros(),
            getBufferUploads(),
            getAverageUploadTimeMicros(),
            getPeakUploadTimeMicros(),
            getSerializations(),
            getAverageSerializeTimeMicros(),
            getDeserializations(),
            getAverageDeserializeTimeMicros(),
            getPeakSerializeTimeMicros()
        );
    }

    /**
     * Get compact one-line summary
     *
     * @return Compact metrics string
     */
    public String getCompactSummary() {
        return String.format(
            "CCO: %,d reads, %,d writes, %,d mesh (avg %dμs), %,d uploads (avg %dμs), %.1f ops/s",
            getBlockReads(),
            getBlockWrites(),
            getMeshGenerations(),
            getAverageMeshGenTimeMicros(),
            getBufferUploads(),
            getAverageUploadTimeMicros(),
            getOperationsPerSecond()
        );
    }
}
