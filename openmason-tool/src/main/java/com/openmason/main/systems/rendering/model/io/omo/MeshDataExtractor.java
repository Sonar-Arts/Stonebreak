package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.gmr.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.IVertexDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles extraction and loading of mesh data for .OMO file serialization.
 *
 * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current version extracts/loads legacy
 * texture data (raw texCoords + uvMode string). Future versions will support per-face
 * texture atlas coordinates, transformations, and flexible mapping modes.
 *
 * <p>This class is responsible for:
 * <ul>
 *   <li>Extracting current mesh state from a renderer for saving</li>
 *   <li>Loading mesh state back into a renderer after loading</li>
 *   <li>Detecting whether mesh has been modified from standard cube</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles mesh data extraction/loading</li>
 *   <li>Separation of Concerns: Renderer doesn't know about serialization</li>
 * </ul>
 *
 * @since 1.1
 */
public class MeshDataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeshDataExtractor.class);

    /**
     * Extract mesh data from a GenericModelRenderer for saving.
     * Always extracts full mesh data to make .omo files self-contained.
     *
     * @param renderer the renderer to extract data from
     * @return MeshData with current state, or null if no data available
     */
    public OMOFormat.MeshData extract(GenericModelRenderer renderer) {
        if (renderer == null) {
            logger.warn("Cannot extract mesh data from null renderer");
            return null;
        }

        float[] vertices = renderer.getAllMeshVertexPositions();
        float[] texCoords = renderer.getTexCoords();
        int[] indices = renderer.getIndices();

        // Check if we have any mesh data
        if (vertices == null || vertices.length == 0) {
            logger.warn("No vertex data available to extract");
            return null;
        }

        // Get face mapping if available
        int[] triangleToFaceId = renderer.getTriangleToFaceMapping();

        // Copy arrays to prevent external modification
        float[] verticesCopy = vertices.clone();
        float[] texCoordsCopy = texCoords != null ? texCoords.clone() : null;
        int[] indicesCopy = indices != null ? indices.clone() : null;
        int[] faceIdCopy = triangleToFaceId != null ? triangleToFaceId.clone() : null;

        String uvModeStr = renderer.getUVMode() != null ? renderer.getUVMode().name() : "FLAT";

        logger.debug("Extracted mesh data: {} vertices, {} indices, {} faces, uvMode={}",
                verticesCopy.length / 3,
                indicesCopy != null ? indicesCopy.length : 0,
                faceIdCopy != null ? getUniqueFaceCount(faceIdCopy) : "unknown",
                uvModeStr);

        return new OMOFormat.MeshData(verticesCopy, texCoordsCopy, indicesCopy, faceIdCopy, uvModeStr);
    }

    /**
     * Get the number of unique faces from a triangle-to-face mapping.
     *
     * @param triangleToFaceId the mapping array
     * @return the number of unique faces
     */
    private int getUniqueFaceCount(int[] triangleToFaceId) {
        if (triangleToFaceId == null || triangleToFaceId.length == 0) {
            return 0;
        }

        int maxFaceId = 0;
        for (int faceId : triangleToFaceId) {
            if (faceId > maxFaceId) {
                maxFaceId = faceId;
            }
        }
        return maxFaceId + 1; // Face IDs are 0-based
    }

    /**
     * Check if a renderer has mesh data available for extraction.
     *
     * @param renderer the renderer to check
     * @return true if mesh data can be extracted
     */
    public boolean hasCustomMeshData(GenericModelRenderer renderer) {
        return extract(renderer) != null;
    }

    /**
     * Load mesh data into a GenericModelRenderer.
     * This replaces the current geometry with the loaded data.
     *
     * @param renderer the renderer to load data into
     * @param meshData the mesh data to load
     */
    public void load(GenericModelRenderer renderer, OMOFormat.MeshData meshData) {
        if (renderer == null) {
            logger.warn("Cannot load mesh data into null renderer");
            return;
        }

        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data to load");
            return;
        }

        renderer.loadMeshData(meshData);

        logger.info("Loaded custom mesh data: {} vertices, {} triangles",
                meshData.getVertexCount(), meshData.getTriangleCount());
    }
}
