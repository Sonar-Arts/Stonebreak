package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.sbo.SBORenderData;

/**
 * Result of chunk mesh generation containing both atlas and SBO meshes.
 *
 * <p>The atlas mesh is rendered with the game's texture atlas (legacy blocks).
 * The SBO mesh is rendered with per-face SBO textures (GMR blocks).
 *
 * @param atlasMesh  mesh data for atlas-textured blocks (legacy cubes, crosses, water)
 * @param sboMesh    mesh data for SBO-textured blocks (arbitrary GMR geometry), may be null
 * @param sboBatches per-face render batches for the SBO mesh (texture IDs + index ranges), may be null
 */
public record ChunkMeshResult(MmsMeshData atlasMesh, MmsMeshData sboMesh,
                               SBORenderData.FaceBatch[] sboBatches) {

    /** Convenience constructor for atlas-only results. */
    public ChunkMeshResult(MmsMeshData atlasMesh, MmsMeshData sboMesh) {
        this(atlasMesh, sboMesh, null);
    }

    /** Whether this result contains any SBO geometry. */
    public boolean hasSBOMesh() {
        return sboMesh != null && !sboMesh.isEmpty();
    }
}
