package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.core.IGPUBufferUploader;
import com.openmason.main.systems.rendering.model.gmr.core.MeshRebuildPipeline;
import com.openmason.main.systems.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopologyBuilder;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

/**
 * Handles all GPU texture I/O and UV management operations.
 * Extracted from GenericModelRenderer to satisfy Single Responsibility.
 *
 * Responsibilities:
 * - Texture state (global texture ID, per-face materials)
 * - GPU texture read/write (glTexSubImage2D, glGetTexImage)
 * - UV regeneration with vertex duplication at material seams
 * - Flood fill for custom material textures after geometry changes
 */
public class TextureGPUOperations implements ITextureGPUOperations {

    private static final Logger logger = LoggerFactory.getLogger(TextureGPUOperations.class);

    private final IVertexDataManager vertexManager;
    private final ITriangleFaceMapper faceMapper;
    private final IUniqueVertexMapper uniqueMapper;
    private final IUVCoordinateGenerator uvGenerator;
    private final IGeometryDataBuilder geometryBuilder;
    private final FaceTextureManager faceTextureManager;
    private final IGPUBufferUploader gpuUploader;
    private final MeshRebuildPipeline rebuildPipeline;

    // Texture state
    private int textureId = 0;
    private boolean useTexture = false;

    // Callback to update vertexCount on the renderer
    private final VertexCountAccess vertexCountAccess;

    /**
     * Callback for reading/writing the renderer's vertexCount.
     */
    public interface VertexCountAccess {
        int getVertexCount();
        void setVertexCount(int count);
    }

    public TextureGPUOperations(
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            IUniqueVertexMapper uniqueMapper,
            IUVCoordinateGenerator uvGenerator,
            IGeometryDataBuilder geometryBuilder,
            FaceTextureManager faceTextureManager,
            IGPUBufferUploader gpuUploader,
            MeshRebuildPipeline rebuildPipeline,
            VertexCountAccess vertexCountAccess) {
        this.vertexManager = vertexManager;
        this.faceMapper = faceMapper;
        this.uniqueMapper = uniqueMapper;
        this.uvGenerator = uvGenerator;
        this.geometryBuilder = geometryBuilder;
        this.faceTextureManager = faceTextureManager;
        this.gpuUploader = gpuUploader;
        this.rebuildPipeline = rebuildPipeline;
        this.vertexCountAccess = vertexCountAccess;
    }

    @Override
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.useTexture = textureId > 0;
    }

    @Override
    public int getTextureId() {
        return textureId;
    }

    @Override
    public boolean isTextureActive() {
        return useTexture && textureId > 0;
    }

    @Override
    public void updateTextureRegion(int targetTextureId, int x, int y,
                                     int width, int height, byte[] rgbaBytes) {
        if (targetTextureId <= 0 || rgbaBytes == null || rgbaBytes.length == 0) {
            return;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(rgbaBytes.length);
        buffer.put(rgbaBytes);
        buffer.flip();

        glBindTexture(GL_TEXTURE_2D, targetTextureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
                        GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override
    public byte[] readTexturePixels(int gpuTextureId) {
        if (gpuTextureId <= 0) {
            return null;
        }

        glBindTexture(GL_TEXTURE_2D, gpuTextureId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

        if (width <= 0 || height <= 0) {
            glBindTexture(GL_TEXTURE_2D, 0);
            return null;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);

        byte[] pixels = new byte[width * height * 4];
        buffer.get(pixels);
        return pixels;
    }

    @Override
    public int[] getTextureDimensions(int gpuTextureId) {
        if (gpuTextureId <= 0) {
            return null;
        }

        glBindTexture(GL_TEXTURE_2D, gpuTextureId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
        glBindTexture(GL_TEXTURE_2D, 0);

        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    @Override
    public void setFaceMaterial(int faceId, int materialId) {
        faceTextureManager.assignDefaultMapping(faceId, materialId);
        regenerateUVsAndUpload();
        rebuildPipeline.markDrawBatchesDirty();
    }

    @Override
    public boolean hasCustomMaterials() {
        for (FaceTextureMapping mapping : faceTextureManager.getAllMappings()) {
            if (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void regenerateUVsAndUpload() {
        float[] vertices = vertexManager.getVertices();
        int[] indices = vertexManager.getIndices();
        float[] existingTexCoords = vertexManager.getTexCoords();
        if (vertices == null || indices == null) {
            return;
        }

        // Duplicate shared vertices at material boundaries to avoid UV conflicts
        boolean verticesDuplicated = duplicateSharedUVSeamVertices();
        if (verticesDuplicated) {
            vertices = vertexManager.getVertices();
            indices = vertexManager.getIndices();
            existingTexCoords = vertexManager.getTexCoords();
            vertexCountAccess.setVertexCount(vertices.length / 3);

            // Rebuild topology so subsequent operations see the duplicated vertices
            uniqueMapper.buildMapping(vertices);
            rebuildPipeline.setTopology(
                MeshTopologyBuilder.build(vertices, indices, faceMapper, uniqueMapper));

            // Indices changed — update EBO immediately
            gpuUploader.uploadEBO(indices);
            rebuildPipeline.markDrawBatchesDirty();
        }

        // Generate per-face UVs (conflict-free after vertex duplication)
        float[] generatedTexCoords = uvGenerator.generatePerFaceUVs(vertices, indices, faceMapper);

        // Merge: use generated UVs for ALL vertices belonging to non-default-material faces
        float[] finalTexCoords;
        if (existingTexCoords != null && existingTexCoords.length == generatedTexCoords.length) {
            finalTexCoords = existingTexCoords.clone();

            int triangleCount = indices.length / 3;
            Set<Integer> nonDefaultVertices = new HashSet<>();
            for (int t = 0; t < triangleCount; t++) {
                int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
                if (faceId < 0) continue;
                FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
                if (mapping != null
                    && (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()
                        || !mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION))) {
                    for (int v = 0; v < 3; v++) {
                        nonDefaultVertices.add(indices[t * 3 + v]);
                    }
                }
            }

            for (int idx : nonDefaultVertices) {
                finalTexCoords[idx * 2] = generatedTexCoords[idx * 2];
                finalTexCoords[idx * 2 + 1] = generatedTexCoords[idx * 2 + 1];
            }
        } else {
            finalTexCoords = generatedTexCoords;
        }

        vertexManager.setData(vertices, finalTexCoords, indices);

        if (gpuUploader.isGPUReady()) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(vertices, finalTexCoords);
            gpuUploader.uploadVBO(interleavedData);
        }

        // Flood-fill GPU textures for custom-material faces
        floodFillCustomMaterialTextures();
    }

    /**
     * For each face with a custom material, flood-fill transparent pixels
     * by propagating the nearest opaque pixel color, then re-upload.
     */
    private void floodFillCustomMaterialTextures() {
        Set<Integer> processedTextures = new HashSet<>();

        for (FaceTextureMapping mapping : faceTextureManager.getAllMappings()) {
            if (mapping.materialId() == MaterialDefinition.DEFAULT.materialId()) {
                continue;
            }
            MaterialDefinition material = faceTextureManager.getMaterial(mapping.materialId());
            if (material == null || material.textureId() <= 0) {
                continue;
            }
            int texId = material.textureId();
            if (!processedTextures.add(texId)) {
                continue;
            }

            byte[] pixels = readTexturePixels(texId);
            int[] dims = getTextureDimensions(texId);
            if (pixels == null || dims == null || dims[0] <= 0 || dims[1] <= 0) {
                continue;
            }

            // Check if any transparent pixels exist before doing work
            boolean hasTransparent = false;
            for (int i = 3; i < pixels.length; i += 4) {
                if ((pixels[i] & 0xFF) == 0) {
                    hasTransparent = true;
                    break;
                }
            }
            if (!hasTransparent) {
                continue;
            }

            // Convert to packed int array for BFS flood fill
            int w = dims[0];
            int h = dims[1];
            int[] packed = new int[w * h];
            for (int i = 0; i < packed.length; i++) {
                int off = i * 4;
                int r = pixels[off] & 0xFF;
                int g = pixels[off + 1] & 0xFF;
                int b = pixels[off + 2] & 0xFF;
                int a = pixels[off + 3] & 0xFF;
                packed[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }

            // BFS flood fill from opaque edges into transparent pixels
            Deque<int[]> queue = new ArrayDeque<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((packed[y * w + x] >>> 24) == 0) continue;
                    if ((x > 0     && (packed[y * w + (x - 1)] >>> 24) == 0)
                     || (x < w - 1 && (packed[y * w + (x + 1)] >>> 24) == 0)
                     || (y > 0     && (packed[(y - 1) * w + x] >>> 24) == 0)
                     || (y < h - 1 && (packed[(y + 1) * w + x] >>> 24) == 0)) {
                        queue.add(new int[]{x, y});
                    }
                }
            }
            while (!queue.isEmpty()) {
                int[] pos = queue.poll();
                int px = pos[0], py = pos[1];
                int color = packed[py * w + px];
                int[][] neighbors = {{px-1,py},{px+1,py},{px,py-1},{px,py+1}};
                for (int[] n : neighbors) {
                    int nx = n[0], ny = n[1];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h
                            && (packed[ny * w + nx] >>> 24) == 0) {
                        packed[ny * w + nx] = color;
                        queue.add(new int[]{nx, ny});
                    }
                }
            }

            // Convert back to RGBA bytes and re-upload
            byte[] filled = new byte[w * h * 4];
            for (int i = 0; i < packed.length; i++) {
                int c = packed[i];
                int off = i * 4;
                filled[off]     = (byte) (c & 0xFF);
                filled[off + 1] = (byte) ((c >> 8) & 0xFF);
                filled[off + 2] = (byte) ((c >> 16) & 0xFF);
                filled[off + 3] = (byte) ((c >> 24) & 0xFF);
            }

            ByteBuffer buffer = BufferUtils.createByteBuffer(filled.length);
            buffer.put(filled);
            buffer.flip();
            glBindTexture(GL_TEXTURE_2D, texId);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h,
                           GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    /**
     * Duplicate mesh vertices shared between faces with different materials.
     * Prevents UV coordinate conflicts at material boundary seams.
     *
     * @return true if any vertices were duplicated
     */
    private boolean duplicateSharedUVSeamVertices() {
        int[] indices = vertexManager.getIndices();
        float[] vertices = vertexManager.getVertices();
        if (indices == null || vertices == null) {
            return false;
        }

        int triangleCount = indices.length / 3;

        Map<Integer, Map<Integer, List<int[]>>> vertexMaterialRefs = new LinkedHashMap<>();

        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            if (faceId < 0) continue;

            FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
            int materialId = (mapping != null) ? mapping.materialId() : MaterialDefinition.DEFAULT.materialId();

            for (int v = 0; v < 3; v++) {
                int meshIdx = indices[t * 3 + v];
                vertexMaterialRefs
                    .computeIfAbsent(meshIdx, k -> new LinkedHashMap<>())
                    .computeIfAbsent(materialId, k -> new ArrayList<>())
                    .add(new int[]{t, v});
            }
        }

        List<Map.Entry<Integer, Map<Integer, List<int[]>>>> conflicts = new ArrayList<>();
        int additionalVertices = 0;
        for (Map.Entry<Integer, Map<Integer, List<int[]>>> entry : vertexMaterialRefs.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry);
                additionalVertices += entry.getValue().size() - 1;
            }
        }

        if (conflicts.isEmpty()) {
            return false;
        }

        int currentVertexCount = vertices.length / 3;
        vertexManager.expandVertexArrays(additionalVertices);
        vertices = vertexManager.getVertices();

        int[] newIndices = indices.clone();
        int nextNewVertex = currentVertexCount;

        for (Map.Entry<Integer, Map<Integer, List<int[]>>> conflict : conflicts) {
            int originalIdx = conflict.getKey();
            boolean first = true;

            for (Map.Entry<Integer, List<int[]>> materialEntry : conflict.getValue().entrySet()) {
                if (first) {
                    first = false;
                    continue;
                }

                int newIdx = nextNewVertex++;
                vertices[newIdx * 3] = vertices[originalIdx * 3];
                vertices[newIdx * 3 + 1] = vertices[originalIdx * 3 + 1];
                vertices[newIdx * 3 + 2] = vertices[originalIdx * 3 + 2];

                for (int[] triRef : materialEntry.getValue()) {
                    newIndices[triRef[0] * 3 + triRef[1]] = newIdx;
                }
            }
        }

        vertexManager.setIndices(newIndices);

        logger.debug("Duplicated {} vertices at {} material boundary seams",
            additionalVertices, conflicts.size());
        return true;
    }
}
