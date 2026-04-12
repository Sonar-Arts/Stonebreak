package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.sbo.SBORenderData;

import java.util.List;

/**
 * Result of chunk mesh generation containing both atlas and SBO meshes.
 *
 * <p>The atlas mesh is rendered with the game's texture atlas (legacy blocks).
 * SBO entries are rendered with per-face SBO textures (one entry per SBO block type).
 *
 * @param atlasMesh  mesh data for atlas-textured blocks (legacy cubes, crosses, water)
 * @param sboEntries per-block-type SBO mesh data and face batches, may be null or empty
 */
public record ChunkMeshResult(MmsMeshData atlasMesh, List<SBOEntry> sboEntries) {

    /**
     * A single SBO block type's mesh data and face batches.
     *
     * @param meshData  the mesh data for this block type
     * @param batches   per-face render batches (texture IDs + index ranges)
     */
    public record SBOEntry(MmsMeshData meshData, SBORenderData.FaceBatch[] batches) {}

    /** Convenience constructor for atlas-only results. */
    public ChunkMeshResult(MmsMeshData atlasMesh) {
        this(atlasMesh, null);
    }

    /** Whether this result contains any SBO geometry. */
    public boolean hasSBOMesh() {
        return sboEntries != null && !sboEntries.isEmpty();
    }
}
