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

    /** Standard cube has 24 vertices (4 per face * 6 faces) */
    private static final int STANDARD_CUBE_VERTEX_COUNT = 24;

    /** Standard cube has 36 indices (2 triangles per face * 3 indices * 6 faces) */
    private static final int STANDARD_CUBE_INDEX_COUNT = 36;

    /** Standard cube has 12 triangles (2 per face * 6 faces) */
    private static final int STANDARD_CUBE_TRIANGLE_COUNT = 12;

    /**
     * Extract mesh data from a GenericModelRenderer for saving.
     * Returns null if the mesh is a standard unmodified cube.
     *
     * @param renderer the renderer to extract data from
     * @return MeshData with current state, or null for standard cube
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
            return null;
        }

        // Check if this is a standard unmodified cube
        if (isStandardCube(vertices, indices, renderer)) {
            logger.debug("Standard cube geometry, no custom mesh data needed");
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

        logger.debug("Extracted mesh data: {} vertices, {} indices, uvMode={}",
                verticesCopy.length / 3,
                indicesCopy != null ? indicesCopy.length : 0,
                uvModeStr);

        return new OMOFormat.MeshData(verticesCopy, texCoordsCopy, indicesCopy, faceIdCopy, uvModeStr);
    }

    /**
     * Check if the renderer contains a standard unmodified cube.
     *
     * @param vertices vertex array
     * @param indices index array
     * @param renderer the renderer to check
     * @return true if standard cube, false if modified
     */
    private boolean isStandardCube(float[] vertices, int[] indices, GenericModelRenderer renderer) {
        // Check vertex and index counts
        boolean isStandardCube = (vertices.length == STANDARD_CUBE_VERTEX_COUNT * 3) &&
                (indices != null && indices.length == STANDARD_CUBE_INDEX_COUNT);

        if (!isStandardCube) {
            return false;
        }

        // Check if face mapping is still standard (0,0,1,1,2,2,3,3,4,4,5,5)
        int[] triangleToFaceId = renderer.getTriangleToFaceMapping();
        if (triangleToFaceId == null || triangleToFaceId.length != STANDARD_CUBE_TRIANGLE_COUNT) {
            return true; // No mapping or wrong size, assume standard
        }

        for (int i = 0; i < triangleToFaceId.length; i++) {
            if (triangleToFaceId[i] != i / 2) {
                return false; // Modified face mapping
            }
        }

        return true;
    }

    /**
     * Check if a renderer has custom mesh data (not a standard cube).
     *
     * @param renderer the renderer to check
     * @return true if mesh has been modified
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
