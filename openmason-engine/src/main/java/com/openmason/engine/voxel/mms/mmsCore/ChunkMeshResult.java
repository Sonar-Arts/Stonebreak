package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.sbo.SBORenderData;

import java.util.List;

/**
 * Result of chunk mesh generation containing atlas, water and SBO meshes.
 *
 * <p>The atlas mesh is rendered with the game's texture atlas (legacy blocks).
 * The water mesh carries all water geometry and is drawn by a dedicated water
 * renderer with its own shader — its vertex attributes reuse the atlas layout
 * slots with water semantics (tex = face-local UV, flags.x = surface height,
 * flags.y = falling, flags.z = source). SBO entries are rendered with per-face
 * SBO textures (one entry per SBO block type).
 *
 * @param atlasMesh  mesh data for atlas-textured blocks (legacy cubes, crosses)
 * @param waterMesh  water geometry for the dedicated water renderer, may be null/empty
 * @param sboEntries per-block-type SBO mesh data and face batches, may be null or empty
 */
public record ChunkMeshResult(MmsMeshData atlasMesh, MmsMeshData waterMesh, List<SBOEntry> sboEntries) {

    /**
     * A single SBO block type's mesh data and face batches.
     *
     * @param meshData  the mesh data for this block type
     * @param batches   per-face render batches (texture IDs + index ranges)
     */
    public record SBOEntry(MmsMeshData meshData, SBORenderData.FaceBatch[] batches) {}

    /** Convenience constructor for atlas-only results. */
    public ChunkMeshResult(MmsMeshData atlasMesh) {
        this(atlasMesh, null, null);
    }

    /** Convenience constructor for results without water geometry. */
    public ChunkMeshResult(MmsMeshData atlasMesh, List<SBOEntry> sboEntries) {
        this(atlasMesh, null, sboEntries);
    }

    /** Whether this result contains any SBO geometry. */
    public boolean hasSBOMesh() {
        return sboEntries != null && !sboEntries.isEmpty();
    }

    /** Whether this result contains any water geometry. */
    public boolean hasWaterMesh() {
        return waterMesh != null && waterMesh.getVertexCount() > 0;
    }
}
