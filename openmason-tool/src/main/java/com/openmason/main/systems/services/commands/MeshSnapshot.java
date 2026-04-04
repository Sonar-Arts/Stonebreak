package com.openmason.main.systems.services.commands;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of the full mesh state for snapshot-based undo/redo.
 * Captures vertex positions, texture coordinates, indices, face mapping,
 * and per-face texture assignments.
 *
 * <p>All arrays are deep-copied on capture and on restore — the snapshot
 * is completely independent of the live renderer state.
 *
 * @param vertices          Vertex positions (x,y,z interleaved)
 * @param texCoords         Texture coordinates (u,v interleaved), may be null
 * @param indices           Triangle indices
 * @param triangleToFaceId  Triangle-to-face mapping
 * @param faceMappings      Per-face texture mappings (faceId → mapping)
 * @param materials         Registered materials (materialId → definition)
 */
public record MeshSnapshot(
    float[] vertices,
    float[] texCoords,
    int[] indices,
    int[] triangleToFaceId,
    Map<Integer, FaceTextureMapping> faceMappings,
    Map<Integer, MaterialDefinition> materials
) {

    /**
     * Capture the current state of a GenericModelRenderer and its FaceTextureManager.
     *
     * @param gmr The renderer to snapshot
     * @return An independent snapshot of all mesh + texture state
     */
    public static MeshSnapshot capture(GenericModelRenderer gmr) {
        float[] verts = gmr.getAllMeshVertexPositions();
        float[] tc = gmr.getTexCoords();
        int[] idx = gmr.getIndices();
        int[] faceMap = gmr.getTriangleToFaceMapping();

        FaceTextureManager ftm = gmr.getFaceTextureManager();

        Map<Integer, FaceTextureMapping> mappings = new HashMap<>();
        for (FaceTextureMapping m : ftm.getAllMappings()) {
            mappings.put(m.faceId(), m);
        }

        Map<Integer, MaterialDefinition> mats = new HashMap<>();
        for (MaterialDefinition md : ftm.getAllMaterials()) {
            mats.put(md.materialId(), md);
        }

        return new MeshSnapshot(
            verts != null ? verts.clone() : null,
            tc != null ? tc.clone() : null,
            idx != null ? idx.clone() : null,
            faceMap != null ? faceMap.clone() : null,
            mappings,
            mats
        );
    }

    /**
     * Restore this snapshot's state into the given renderer.
     * Delegates to {@link GenericModelRenderer#restoreFromSnapshot}.
     *
     * @param gmr The renderer to restore into
     */
    public void restore(GenericModelRenderer gmr) {
        gmr.restoreFromSnapshot(
            vertices != null ? vertices.clone() : null,
            texCoords != null ? texCoords.clone() : null,
            indices != null ? indices.clone() : null,
            triangleToFaceId != null ? triangleToFaceId.clone() : null,
            new HashMap<>(faceMappings),
            new HashMap<>(materials)
        );
    }
}
