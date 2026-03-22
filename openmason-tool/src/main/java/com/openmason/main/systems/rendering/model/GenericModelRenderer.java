package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.gmr.core.DrawBatchManager;
import com.openmason.main.systems.rendering.model.gmr.core.IGPUBufferUploader;
import com.openmason.main.systems.rendering.model.gmr.core.MeshMutationCoordinator;
import com.openmason.main.systems.rendering.model.gmr.core.MeshRebuildPipeline;
import com.openmason.main.systems.rendering.model.gmr.core.MeshSerializationAdapter;
import com.openmason.main.systems.rendering.model.gmr.core.VertexDataManager;
import com.openmason.main.systems.rendering.model.gmr.extraction.FaceTriangleQuery;
import com.openmason.main.systems.rendering.model.gmr.extraction.GMREdgeExtractor;
import com.openmason.main.systems.rendering.model.gmr.extraction.GMRFaceExtractor;
import com.openmason.main.systems.rendering.model.gmr.geometry.GeometryDataBuilder;
import com.openmason.main.systems.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.TriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.UniqueVertexMapper;
import com.openmason.main.systems.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.main.systems.rendering.model.gmr.notification.MeshChangeNotifier;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.rendering.model.gmr.uv.*;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Generic model renderer supporting arbitrary geometry.
 * Coordinator class that wires and delegates to focused subsystems:
 *
 * <ul>
 *   <li>{@link MeshMutationCoordinator} — vertex updates, subdivision, edge/face operations</li>
 *   <li>{@link TextureGPUOperations} — texture I/O, UV regeneration, material management</li>
 *   <li>{@link DrawBatchManager} — per-material draw batch grouping</li>
 *   <li>{@link MeshSerializationAdapter} — OMO load/save, snapshot restore</li>
 *   <li>{@link MeshRebuildPipeline} — shared post-mutation rebuild sequence</li>
 * </ul>
 *
 * Extends BaseRenderer for consistent OpenGL initialization and rendering.
 */
public class GenericModelRenderer extends BaseRenderer {

    // Core subsystems (dependency injection via interfaces)
    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final ITriangleFaceMapper faceMapper;
    private final IGeometryDataBuilder geometryBuilder;
    private final IModelStateManager stateManager;
    private final FaceTextureManager faceTextureManager;

    // Extractors
    private final GMRFaceExtractor faceExtractor;
    private final GMREdgeExtractor edgeExtractor;

    // Coordinated subsystems
    private final MeshRebuildPipeline rebuildPipeline;
    private final MeshMutationCoordinator mutationCoordinator;
    private final TextureGPUOperations textureOps;
    private final DrawBatchManager drawBatchManager;
    private final MeshSerializationAdapter serializationAdapter;

    // Cached model bounds (AABB) — nulled on geometry changes, recomputed lazily
    private ModelBounds cachedBounds;

    // Cached identity matrix to avoid per-frame allocation in setUniforms
    private static final org.joml.Matrix4f IDENTITY_MATRIX = new org.joml.Matrix4f();

    /**
     * Create a GenericModelRenderer with default subsystems.
     */
    public GenericModelRenderer() {
        this(new FaceTextureManager());
    }

    /**
     * Create a GenericModelRenderer with a specific FaceTextureManager.
     */
    private GenericModelRenderer(FaceTextureManager faceTextureManager) {
        this(
            new VertexDataManager(),
            new UniqueVertexMapper(),
            new MeshChangeNotifier(),
            new TriangleFaceMapper(),
            new SubdivisionProcessor(),
            new GeometryDataBuilder(),
            new PerFaceUVCoordinateGenerator(faceTextureManager),
            new ModelStateManager(),
            new GMRFaceExtractor(),
            new GMREdgeExtractor(),
            faceTextureManager
        );
    }

    /**
     * Create a GenericModelRenderer with custom subsystems (for testing/extension).
     */
    public GenericModelRenderer(
            IVertexDataManager vertexManager,
            IUniqueVertexMapper uniqueMapper,
            IMeshChangeNotifier changeNotifier,
            ITriangleFaceMapper faceMapper,
            ISubdivisionProcessor subdivisionProcessor,
            IGeometryDataBuilder geometryBuilder,
            IUVCoordinateGenerator uvGenerator,
            IModelStateManager stateManager,
            GMRFaceExtractor faceExtractor,
            GMREdgeExtractor edgeExtractor,
            FaceTextureManager faceTextureManager) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.changeNotifier = changeNotifier;
        this.faceMapper = faceMapper;
        this.geometryBuilder = geometryBuilder;
        this.stateManager = stateManager;
        this.faceExtractor = faceExtractor;
        this.edgeExtractor = edgeExtractor;
        this.faceTextureManager = faceTextureManager != null ? faceTextureManager : new FaceTextureManager();

        // GPU buffer uploader — bridges BaseRenderer protected methods to subsystems
        IGPUBufferUploader gpuUploader = new IGPUBufferUploader() {
            @Override public void uploadVBO(float[] data) { updateVBO(data); }
            @Override public void uploadEBO(int[] data) { updateEBO(data); }
            @Override public boolean isGPUReady() { return initialized; }
        };

        // Renderer state access — bridges BaseRenderer protected fields to subsystems
        MeshMutationCoordinator.RendererStateAccess rendererState = new MeshMutationCoordinator.RendererStateAccess() {
            @Override public int getVertexCount() { return vertexCount; }
            @Override public void setVertexCount(int count) { vertexCount = count; }
            @Override public int getIndexCount() { return indexCount; }
            @Override public void setIndexCount(int count) { indexCount = count; }
            @Override public boolean isInitialized() { return initialized; }
        };

        // Wire rebuild pipeline
        this.rebuildPipeline = new MeshRebuildPipeline(
            vertexManager, uniqueMapper, faceMapper, changeNotifier, geometryBuilder, gpuUploader);
        this.rebuildPipeline.setBoundsInvalidator(() -> cachedBounds = null);

        // Wire texture operations
        this.textureOps = new TextureGPUOperations(
            vertexManager, faceMapper, uniqueMapper, uvGenerator, geometryBuilder,
            this.faceTextureManager, gpuUploader, rebuildPipeline,
            new TextureGPUOperations.VertexCountAccess() {
                @Override public int getVertexCount() { return vertexCount; }
                @Override public void setVertexCount(int count) { vertexCount = count; }
            });

        // Connect UV regeneration to pipeline
        this.rebuildPipeline.setUVRegenerator(textureOps::regenerateUVsAndUpload);

        // Wire draw batch manager
        this.drawBatchManager = new DrawBatchManager(vertexManager, faceMapper, this.faceTextureManager);

        // Wire mutation coordinator
        this.mutationCoordinator = new MeshMutationCoordinator(
            vertexManager, uniqueMapper, faceMapper, subdivisionProcessor,
            changeNotifier, geometryBuilder, this.faceTextureManager,
            rebuildPipeline, gpuUploader, rendererState);

        // Wire serialization adapter
        this.serializationAdapter = new MeshSerializationAdapter(
            vertexManager, faceMapper, this.faceTextureManager, stateManager,
            rebuildPipeline, textureOps, rendererState);

        logger.debug("GenericModelRenderer created with subsystems");
    }

    // =========================================================================
    // BaseRenderer OVERRIDES
    // =========================================================================

    @Override
    public String getDebugName() {
        return "GenericModelRenderer";
    }

    @Override
    public RenderPass getRenderPass() {
        return RenderPass.SCENE;
    }

    @Override
    protected GeometryData createGeometry() {
        float[] vertices = vertexManager.getVertices();
        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();

        float[] interleaved = geometryBuilder.buildInterleavedData(vertices, texCoords);
        if (interleaved.length == 0) {
            return GeometryData.nonIndexed(new float[0], 0, geometryBuilder.getStride());
        }

        if (indices != null && indices.length > 0) {
            return GeometryData.indexed(interleaved, indices, vertexCount, geometryBuilder.getStride());
        } else {
            return GeometryData.nonIndexed(interleaved, vertexCount, geometryBuilder.getStride());
        }
    }

    @Override
    protected void configureVertexAttributes() {
        glVertexAttribPointer(0, geometryBuilder.getPositionComponents(), GL_FLOAT, false,
            geometryBuilder.getStride(), geometryBuilder.getPositionOffset());
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, geometryBuilder.getTexCoordComponents(), GL_FLOAT, false,
            geometryBuilder.getStride(), geometryBuilder.getTexCoordOffset());
        glEnableVertexAttribArray(1);
    }

    @Override
    protected void doRender(ShaderProgram shader, RenderContext context) {
        if (vertexCount == 0) {
            return;
        }

        // Fast path: no custom materials — single texture, one draw call
        if (!textureOps.hasCustomMaterials()) {
            if (textureOps.isTextureActive()) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, textureOps.getTextureId());
                shader.setBool("uUseTexture", true);
                shader.setInt("uTexture", 0);
            } else {
                shader.setBool("uUseTexture", false);
            }

            if (indexCount > 0 && ebo != 0) {
                glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            } else {
                glDrawArrays(GL_TRIANGLES, 0, vertexCount);
            }
            return;
        }

        // Multi-material path: rebuild batches if dirty, then draw per batch
        if (rebuildPipeline.isDrawBatchesDirty()) {
            drawBatchManager.rebuildDrawBatches(ebo);
            rebuildPipeline.clearDrawBatchesDirty();
        }

        shader.setInt("uTexture", 0);

        for (DrawBatchManager.MaterialDrawBatch batch : drawBatchManager.getDrawBatches()) {
            int batchTextureId = batch.textureId() > 0 ? batch.textureId() : textureOps.getTextureId();

            if (batchTextureId > 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, batchTextureId);
                shader.setBool("uUseTexture", true);
            } else {
                shader.setBool("uUseTexture", false);
            }

            glDrawElements(GL_TRIANGLES, batch.indexCount(), GL_UNSIGNED_INT,
                    (long) batch.indexOffset() * 4L);
        }
    }

    @Override
    protected void setUniforms(ShaderProgram shader, RenderContext context, org.joml.Matrix4f modelMatrix) {
        shader.setMat4("uModelMatrix", IDENTITY_MATRIX);
        org.joml.Matrix4f modelViewMatrix = new org.joml.Matrix4f(context.getCamera().getViewMatrix()).mul(modelMatrix);
        shader.setMat4("uViewMatrix", modelViewMatrix);
    }

    // =========================================================================
    // UV MODE (legacy — delegates to stateManager)
    // =========================================================================

    /** @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} instead. */
    @Deprecated
    public void setUVMode(UVMode uvMode) {
        logger.debug("setUVMode({}) called — ignored, per-face UV is the only mode", uvMode);
        if (uvMode != null) {
            stateManager.setUVMode(uvMode);
        }
    }

    /** @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} instead. */
    @Deprecated
    public UVMode getUVMode() {
        return stateManager.getUVMode();
    }

    // =========================================================================
    // VERTEX POSITION MANAGEMENT (delegates to MeshMutationCoordinator)
    // =========================================================================

    public void updateVertexPosition(int globalIndex, Vector3f position) {
        mutationCoordinator.updateVertexPosition(globalIndex, position);
    }

    public void updateVertexPositions(float[] positions) {
        mutationCoordinator.updateVertexPositions(positions);
    }

    public Vector3f getVertexPosition(int globalIndex) {
        return vertexManager.getVertexPosition(globalIndex);
    }

    public int getTotalVertexCount() {
        return vertexManager.getTotalVertexCount();
    }

    public float[] getAllMeshVertexPositions() {
        return vertexManager.getAllMeshVertexPositions();
    }

    public List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon) {
        return vertexManager.findMeshVerticesAtPosition(position, epsilon);
    }

    public ModelBounds getModelBounds() {
        if (cachedBounds == null) {
            cachedBounds = ModelBoundsCalculator.compute(vertexManager.getVertices());
        }
        return cachedBounds;
    }

    // =========================================================================
    // TRIANGLE & FACE QUERIES (delegates to faceMapper / topology)
    // =========================================================================

    public int getTriangleCount() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.length / 3 : 0;
    }

    public int[] getTriangleIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    public int getOriginalFaceIdForTriangle(int triangleIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getOriginalFaceIdForTriangle(triangleIndex);
        }
        return faceMapper.getOriginalFaceIdForTriangle(triangleIndex);
    }

    public int getOriginalFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    public boolean hasTriangleToFaceMapping() {
        return faceMapper.hasMapping();
    }

    // =========================================================================
    // UNIQUE VERTEX MAPPING (delegates to uniqueMapper / topology)
    // =========================================================================

    public Vector3f getUniqueVertexPosition(int uniqueIndex) {
        return uniqueMapper.getUniqueVertexPosition(uniqueIndex, vertexManager.getVertices());
    }

    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getMeshIndicesForUniqueVertex(uniqueIndex);
        }
        return uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
    }

    public int getUniqueIndexForMeshVertex(int meshIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getUniqueIndexForMeshVertex(meshIndex);
        }
        return uniqueMapper.getUniqueIndexForMeshVertex(meshIndex);
    }

    public float[] getAllUniqueVertexPositions() {
        return uniqueMapper.getAllUniqueVertexPositions(vertexManager.getVertices());
    }

    public MeshTopology getTopology() {
        return rebuildPipeline.getTopology();
    }

    // =========================================================================
    // CHANGE NOTIFICATION (delegates to changeNotifier)
    // =========================================================================

    public void addMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.addListener(listener);
    }

    public void removeMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.removeListener(listener);
    }

    // =========================================================================
    // MESH MUTATIONS (delegates to MeshMutationCoordinator)
    // =========================================================================

    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        return mutationCoordinator.applyEdgeSubdivisionByPosition(midpointPosition, endpoint1, endpoint2);
    }

    public int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t) {
        return mutationCoordinator.subdivideEdgeAtParameter(uniqueVertexA, uniqueVertexB, t);
    }

    public boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB) {
        return mutationCoordinator.insertEdgeBetweenVertices(uniqueVertexA, uniqueVertexB);
    }

    public boolean deleteFace(int faceId) {
        return mutationCoordinator.deleteFace(faceId);
    }

    public boolean createFaceFromVertices(int[] selectedUniqueVertices) {
        return mutationCoordinator.createFaceFromVertices(selectedUniqueVertices);
    }

    public boolean createFaceFromVertices(int[] selectedUniqueVertices, int activeMaterialId) {
        return mutationCoordinator.createFaceFromVertices(selectedUniqueVertices, activeMaterialId);
    }

    // =========================================================================
    // TEXTURE MANAGEMENT (delegates to TextureGPUOperations)
    // =========================================================================

    public void setTexture(int textureId) {
        textureOps.setTexture(textureId);
    }

    public int getTextureId() {
        return textureOps.getTextureId();
    }

    public void updateTextureRegion(int targetTextureId, int x, int y,
                                    int width, int height, byte[] rgbaBytes) {
        textureOps.updateTextureRegion(targetTextureId, x, y, width, height, rgbaBytes);
    }

    public byte[] readTexturePixels(int gpuTextureId) {
        return textureOps.readTexturePixels(gpuTextureId);
    }

    public int[] getTextureDimensions(int gpuTextureId) {
        return textureOps.getTextureDimensions(gpuTextureId);
    }

    public void setFaceMaterial(int faceId, int materialId) {
        textureOps.setFaceMaterial(faceId, materialId);
    }

    public FaceTextureManager getFaceTextureManager() {
        return faceTextureManager;
    }

    public void markDrawBatchesDirty() {
        rebuildPipeline.markDrawBatchesDirty();
    }

    // =========================================================================
    // SERIALIZATION (delegates to MeshSerializationAdapter)
    // =========================================================================

    public void loadMeshData(OMOFormat.MeshData meshData) {
        serializationAdapter.loadMeshData(meshData);
    }

    public OMOFormat.MeshData toMeshData() {
        return serializationAdapter.toMeshData();
    }

    public void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                                     int[] triangleToFaceId,
                                     Map<Integer, FaceTextureMapping> faceMappings,
                                     Map<Integer, MaterialDefinition> materials) {
        serializationAdapter.restoreFromSnapshot(vertices, texCoords, indices,
            triangleToFaceId, faceMappings, materials);
    }

    public void refreshUVs() {
        serializationAdapter.refreshUVs();
    }

    // =========================================================================
    // DATA ACCESS & EXTRACTION
    // =========================================================================

    public float[] getTexCoords() {
        float[] texCoords = vertexManager.getTexCoords();
        return texCoords != null ? texCoords.clone() : null;
    }

    public int[] getIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    public int[] getTriangleToFaceMapping() {
        return faceMapper.getMappingCopy();
    }

    public GMRFaceExtractor.FaceExtractionResult extractFaceData() {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return faceExtractor.extractFaceData(vertexManager.getVertices(), topology);
        }
        return faceExtractor.extractFaceData(
            vertexManager.getVertices(), vertexManager.getIndices(), faceMapper);
    }

    public float[][] extractFacePolygonFromUVs(int faceId) {
        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();
        if (texCoords == null || indices == null) {
            return null;
        }

        FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
        if (mapping == null) {
            return null;
        }
        FaceTextureMapping.UVRegion uvRegion = mapping.uvRegion();

        List<Integer> triangles = FaceTriangleQuery.findTrianglesForFace(faceId, indices, faceMapper);
        if (triangles.isEmpty()) {
            return null;
        }
        Integer[] boundaryIndices = FaceTriangleQuery.extractFaceVertexIndices(triangles, indices);
        if (boundaryIndices.length < 3) {
            return null;
        }

        float regionW = uvRegion.width();
        float regionH = uvRegion.height();
        if (regionW < 1e-6f || regionH < 1e-6f) {
            return null;
        }

        float[] localX = new float[boundaryIndices.length];
        float[] localY = new float[boundaryIndices.length];
        for (int i = 0; i < boundaryIndices.length; i++) {
            int idx = boundaryIndices[i];
            float u = texCoords[idx * 2];
            float v = texCoords[idx * 2 + 1];
            localX[i] = Math.clamp((u - uvRegion.u0()) / regionW, 0.0f, 1.0f);
            localY[i] = Math.clamp((v - uvRegion.v0()) / regionH, 0.0f, 1.0f);
        }

        return new float[][]{localX, localY};
    }

    public float[] extractEdgePositions() {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return edgeExtractor.extractEdgePositions(vertexManager.getVertices(), topology);
        }
        return edgeExtractor.extractEdgePositions(
            vertexManager.getVertices(), vertexManager.getIndices(), faceMapper);
    }

    public int getFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    public int getEdgeCount() {
        int upperBound = faceMapper.getFaceIdUpperBound();
        int totalEdges = 0;
        for (int i = 0; i < upperBound; i++) {
            totalEdges += faceMapper.getEdgeCountForFace(i);
        }
        return totalEdges;
    }

    public int getUniqueVertexCount() {
        return uniqueMapper.getUniqueVertexCount();
    }
}
