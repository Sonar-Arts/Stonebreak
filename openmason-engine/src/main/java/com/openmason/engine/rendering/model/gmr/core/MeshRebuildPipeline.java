package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.engine.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.engine.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.engine.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopologyBuilder;
import com.openmason.engine.rendering.model.gmr.uv.IVertexDataManager;
import com.openmason.engine.rendering.model.ModelBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Shared post-mutation rebuild pipeline.
 * Encapsulates the rebuild sequence that was previously copy-pasted 6 times
 * across GenericModelRenderer mutation methods.
 *
 * Sequence: rebuild mappings → rebuild topology → regenerate UVs →
 *           upload GPU buffers → invalidate caches → notify listeners.
 */
public class MeshRebuildPipeline implements IMeshRebuildPipeline {

    private static final Logger logger = LoggerFactory.getLogger(MeshRebuildPipeline.class);

    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final ITriangleFaceMapper faceMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final IGeometryDataBuilder geometryBuilder;
    private final IGPUBufferUploader gpuUploader;

    // UV regeneration callback — set by GMR after TextureGPUOperations is wired
    private Runnable uvRegenerator;

    // Renderer state callbacks — set by GMR to update its protected fields
    private Consumer<int[]> vertexCountSetter;

    // Shared mutable state
    private MeshTopology topology;
    private boolean drawBatchesDirty = true;

    // Cached model bounds — nulled on geometry changes, recomputed lazily by GMR
    private Runnable boundsInvalidator;

    public MeshRebuildPipeline(
            IVertexDataManager vertexManager,
            IUniqueVertexMapper uniqueMapper,
            ITriangleFaceMapper faceMapper,
            IMeshChangeNotifier changeNotifier,
            IGeometryDataBuilder geometryBuilder,
            IGPUBufferUploader gpuUploader) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.faceMapper = faceMapper;
        this.changeNotifier = changeNotifier;
        this.geometryBuilder = geometryBuilder;
        this.gpuUploader = gpuUploader;
    }

    /**
     * Set the UV regeneration callback.
     * Called during rebuild to regenerate per-face UVs and upload to GPU.
     *
     * @param uvRegenerator Runnable that performs UV regeneration
     */
    public void setUVRegenerator(Runnable uvRegenerator) {
        this.uvRegenerator = uvRegenerator;
    }

    /**
     * Set the bounds invalidation callback.
     * Called during rebuild to clear cached model bounds.
     *
     * @param boundsInvalidator Runnable that nulls the cached bounds
     */
    public void setBoundsInvalidator(Runnable boundsInvalidator) {
        this.boundsInvalidator = boundsInvalidator;
    }

    @Override
    public void rebuildFull(int newVertexCount, int newIndexCount) {
        invalidateBounds();

        // Rebuild unique vertex mapping
        float[] vertices = vertexManager.getVertices();
        if (vertices != null) {
            uniqueMapper.buildMapping(vertices);
        }

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertices, vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Upload GPU buffers
        if (gpuUploader.isGPUReady()) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertices, vertexManager.getTexCoords());
            gpuUploader.uploadVBO(interleavedData);

            int[] indices = vertexManager.getIndices();
            if (indices != null) {
                gpuUploader.uploadEBO(indices);
            }
        }

        drawBatchesDirty = true;

        // Regenerate UVs (may trigger additional VBO upload)
        if (uvRegenerator != null) {
            uvRegenerator.run();
        }

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.trace("Full rebuild complete: {} vertices, {} indices",
            newVertexCount, newIndexCount);
    }

    @Override
    public void rebuildPositionsOnly() {
        invalidateBounds();

        // Rebuild unique vertex mapping
        float[] vertices = vertexManager.getVertices();
        if (vertices != null) {
            uniqueMapper.buildMapping(vertices);
        }

        // Rebuild topology to reflect new vertex positions
        this.topology = MeshTopologyBuilder.build(
            vertices, vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Upload VBO only (indices unchanged)
        if (gpuUploader.isGPUReady()) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertices, vertexManager.getTexCoords());
            gpuUploader.uploadVBO(interleavedData);
        }

        drawBatchesDirty = true;

        // Regenerate UVs for updated geometry
        if (uvRegenerator != null) {
            uvRegenerator.run();
        }

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.trace("Position-only rebuild complete");
    }

    @Override
    public MeshTopology getTopology() {
        return topology;
    }

    @Override
    public void setTopology(MeshTopology topology) {
        this.topology = topology;
    }

    @Override
    public void markDrawBatchesDirty() {
        drawBatchesDirty = true;
    }

    @Override
    public boolean isDrawBatchesDirty() {
        return drawBatchesDirty;
    }

    /**
     * Clear the dirty flag after batches are rebuilt.
     */
    public void clearDrawBatchesDirty() {
        drawBatchesDirty = false;
    }

    private void invalidateBounds() {
        if (boundsInvalidator != null) {
            boundsInvalidator.run();
        }
    }
}
