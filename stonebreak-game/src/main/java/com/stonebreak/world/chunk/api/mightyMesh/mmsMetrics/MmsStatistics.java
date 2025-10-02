package com.stonebreak.world.chunk.api.mightyMesh.mmsMetrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mighty Mesh System - Performance statistics collector.
 *
 * Tracks mesh generation performance metrics for monitoring and optimization.
 *
 * Design Philosophy:
 * - Thread-safe: Atomic counters for concurrent updates
 * - Low overhead: Simple counter increments
 * - KISS: Focused on essential metrics
 *
 * @since MMS 1.0
 */
public class MmsStatistics {

    // Mesh generation counters
    private final AtomicLong meshesGenerated = new AtomicLong(0);
    private final AtomicLong meshesUploaded = new AtomicLong(0);
    private final AtomicLong meshesFailed = new AtomicLong(0);

    // Vertex/triangle counters
    private final AtomicLong totalVertices = new AtomicLong(0);
    private final AtomicLong totalTriangles = new AtomicLong(0);

    // Timing statistics
    private final AtomicLong totalGenerationTimeMs = new AtomicLong(0);
    private final AtomicLong totalUploadTimeMs = new AtomicLong(0);

    // Memory statistics
    private final AtomicLong totalMemoryBytes = new AtomicLong(0);
    private final AtomicLong peakMemoryBytes = new AtomicLong(0);

    /**
     * Records a successful mesh generation.
     *
     * @param vertexCount Number of vertices generated
     * @param triangleCount Number of triangles generated
     * @param generationTimeMs Time taken to generate (milliseconds)
     * @param memoryBytes Memory usage (bytes)
     */
    public void recordMeshGeneration(int vertexCount, int triangleCount,
                                     long generationTimeMs, long memoryBytes) {
        meshesGenerated.incrementAndGet();
        totalVertices.addAndGet(vertexCount);
        totalTriangles.addAndGet(triangleCount);
        totalGenerationTimeMs.addAndGet(generationTimeMs);
        totalMemoryBytes.addAndGet(memoryBytes);

        // Update peak memory
        updatePeakMemory(memoryBytes);
    }

    /**
     * Records a successful mesh upload to GPU.
     *
     * @param uploadTimeMs Time taken to upload (milliseconds)
     */
    public void recordMeshUpload(long uploadTimeMs) {
        meshesUploaded.incrementAndGet();
        totalUploadTimeMs.addAndGet(uploadTimeMs);
    }

    /**
     * Records a failed mesh generation.
     */
    public void recordMeshFailure() {
        meshesFailed.incrementAndGet();
    }

    /**
     * Records mesh disposal (memory freed).
     *
     * @param memoryBytes Memory freed (bytes)
     */
    public void recordMeshDisposal(long memoryBytes) {
        totalMemoryBytes.addAndGet(-memoryBytes);
    }

    /**
     * Updates peak memory if current value exceeds it.
     *
     * @param currentMemory Current memory usage
     */
    private void updatePeakMemory(long currentMemory) {
        long peak;
        do {
            peak = peakMemoryBytes.get();
            if (currentMemory <= peak) {
                break;
            }
        } while (!peakMemoryBytes.compareAndSet(peak, currentMemory));
    }

    /**
     * Resets all statistics to zero.
     */
    public void reset() {
        meshesGenerated.set(0);
        meshesUploaded.set(0);
        meshesFailed.set(0);
        totalVertices.set(0);
        totalTriangles.set(0);
        totalGenerationTimeMs.set(0);
        totalUploadTimeMs.set(0);
        totalMemoryBytes.set(0);
        peakMemoryBytes.set(0);
    }

    // === Getters ===

    public long getMeshesGenerated() {
        return meshesGenerated.get();
    }

    public long getMeshesUploaded() {
        return meshesUploaded.get();
    }

    public long getMeshesFailed() {
        return meshesFailed.get();
    }

    public long getTotalVertices() {
        return totalVertices.get();
    }

    public long getTotalTriangles() {
        return totalTriangles.get();
    }

    public long getTotalGenerationTimeMs() {
        return totalGenerationTimeMs.get();
    }

    public long getTotalUploadTimeMs() {
        return totalUploadTimeMs.get();
    }

    public long getTotalMemoryBytes() {
        return totalMemoryBytes.get();
    }

    public long getPeakMemoryBytes() {
        return peakMemoryBytes.get();
    }

    /**
     * Calculates average generation time per mesh.
     *
     * @return Average time in milliseconds, or 0 if no meshes generated
     */
    public double getAverageGenerationTimeMs() {
        long count = meshesGenerated.get();
        return count > 0 ? (double) totalGenerationTimeMs.get() / count : 0.0;
    }

    /**
     * Calculates average upload time per mesh.
     *
     * @return Average time in milliseconds, or 0 if no meshes uploaded
     */
    public double getAverageUploadTimeMs() {
        long count = meshesUploaded.get();
        return count > 0 ? (double) totalUploadTimeMs.get() / count : 0.0;
    }

    /**
     * Calculates average vertices per mesh.
     *
     * @return Average vertex count, or 0 if no meshes generated
     */
    public double getAverageVerticesPerMesh() {
        long count = meshesGenerated.get();
        return count > 0 ? (double) totalVertices.get() / count : 0.0;
    }

    /**
     * Calculates average triangles per mesh.
     *
     * @return Average triangle count, or 0 if no meshes generated
     */
    public double getAverageTrianglesPerMesh() {
        long count = meshesGenerated.get();
        return count > 0 ? (double) totalTriangles.get() / count : 0.0;
    }

    /**
     * Calculates success rate for mesh generation.
     *
     * @return Success rate (0.0 to 1.0)
     */
    public double getSuccessRate() {
        long total = meshesGenerated.get() + meshesFailed.get();
        return total > 0 ? (double) meshesGenerated.get() / total : 1.0;
    }

    @Override
    public String toString() {
        return String.format(
            "MmsStatistics{meshes=%d, uploaded=%d, failed=%d, vertices=%d, triangles=%d, " +
            "avgGenTime=%.2fms, avgUploadTime=%.2fms, memory=%d bytes, peak=%d bytes, successRate=%.2f%%}",
            meshesGenerated.get(), meshesUploaded.get(), meshesFailed.get(),
            totalVertices.get(), totalTriangles.get(),
            getAverageGenerationTimeMs(), getAverageUploadTimeMs(),
            totalMemoryBytes.get(), peakMemoryBytes.get(),
            getSuccessRate() * 100.0
        );
    }
}
