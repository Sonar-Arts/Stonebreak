package com.openmason.main.systems.rendering.model.gmr.core;

import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;

import java.util.Map;

/**
 * Interface for mesh serialization operations.
 * Handles OMO file loading/saving and undo/redo snapshot restore.
 */
public interface IMeshSerializationAdapter {

    /**
     * Load mesh state from MeshData (restored from .OMO file).
     *
     * @param meshData the mesh data to load
     */
    void loadMeshData(OMOFormat.MeshData meshData);

    /**
     * Create a MeshData snapshot from current internal state for saving to .OMO file.
     *
     * @return MeshData with current vertex/index/face data, or null if no vertex data
     */
    OMOFormat.MeshData toMeshData();

    /**
     * Restore full mesh state from a snapshot (for undo/redo).
     *
     * @param vertices          Vertex positions (x,y,z interleaved)
     * @param texCoords         Texture coordinates (u,v interleaved), may be null
     * @param indices           Triangle indices
     * @param triangleToFaceId  Triangle-to-face mapping
     * @param faceMappings      Per-face texture mappings (faceId → mapping)
     * @param materials         Registered materials (materialId → definition)
     */
    void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                             int[] triangleToFaceId,
                             Map<Integer, FaceTextureMapping> faceMappings,
                             Map<Integer, MaterialDefinition> materials);

    /**
     * Regenerate UV coordinates and upload to GPU.
     * Public entry point for the undo/redo system to refresh UVs.
     */
    void refreshUVs();
}
