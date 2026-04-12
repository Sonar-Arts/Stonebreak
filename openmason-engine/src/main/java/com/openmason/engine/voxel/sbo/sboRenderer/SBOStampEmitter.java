package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;

/**
 * Emits pre-computed SBO stamp geometry into a chunk mesh builder.
 *
 * <p>For each SBO block instance, looks up the pre-baked {@link BlockStamp},
 * iterates its 6 faces, applies face culling via an {@link SBOCullingPolicy},
 * and copies the stamp vertex/index data into the {@link MmsMeshBuilder}
 * with a world-space position offset.
 *
 * <p>No per-vertex UV computation happens here — atlas UVs are already
 * baked into the stamp at initialization time by
 * {@link com.openmason.engine.voxel.sbo.SBOMeshProcessor}.
 */
public class SBOStampEmitter {

    private final SBOStampCache cache;
    private final SBOCullingPolicy cullingPolicy;

    /**
     * Creates an emitter with the given stamp cache and culling policy.
     *
     * @param cache         cache of pre-computed block stamps
     * @param cullingPolicy strategy for deciding which faces to render
     */
    public SBOStampEmitter(SBOStampCache cache, SBOCullingPolicy cullingPolicy) {
        this.cache = cache;
        this.cullingPolicy = cullingPolicy;
    }

    /**
     * Emit an SBO block's geometry into the mesh builder.
     *
     * <p>Looks up the block's pre-computed stamp, then for each of the 6 faces:
     * checks the culling policy, and if visible, copies the stamp's position,
     * normal, and UV data with a world-space offset into the builder.
     *
     * @param builder   the chunk mesh builder to emit vertices/indices into
     * @param blockType the SBO block type
     * @param lx        local X coordinate within the chunk (for culling)
     * @param ly        local Y coordinate within the chunk (for culling)
     * @param lz        local Z coordinate within the chunk (for culling)
     * @param worldX    world-space X position for this block
     * @param worldY    world-space Y position for this block
     * @param worldZ    world-space Z position for this block
     * @param chunkData chunk data for face culling neighbor lookups
     */
    public void emitBlock(MmsMeshBuilder builder, IBlockType blockType,
                          int lx, int ly, int lz,
                          float worldX, float worldY, float worldZ,
                          CcoChunkData chunkData) {

        BlockStamp stamp = cache.get(blockType);
        if (stamp == null) return;

        for (int face = 0; face < SBOFaceConventions.FACE_COUNT; face++) {
            if (!cullingPolicy.shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue;
            }

            FaceStamp faceStamp = stamp.faces()[face];
            if (faceStamp.vertexCount() == 0) continue;

            emitFaceStamp(builder, faceStamp, worldX, worldY, worldZ);
        }
    }

    /**
     * Emit a single face stamp's geometry into the builder.
     */
    private void emitFaceStamp(MmsMeshBuilder builder, FaceStamp faceStamp,
                               float worldX, float worldY, float worldZ) {
        float[] pos = faceStamp.positions();
        float[] nrm = faceStamp.normals();
        float[] uv = faceStamp.atlasUVs();
        int triCount = faceStamp.vertexCount() / 3;

        for (int tri = 0; tri < triCount; tri++) {
            int baseVertex = builder.getVertexCount();

            for (int v = 0; v < 3; v++) {
                int vi = tri * 3 + v;
                int pOff = vi * 3;
                int tOff = vi * 2;

                builder.addVertex(
                        pos[pOff] + worldX,
                        pos[pOff + 1] + worldY,
                        pos[pOff + 2] + worldZ,
                        uv[tOff], uv[tOff + 1],
                        nrm[pOff], nrm[pOff + 1], nrm[pOff + 2],
                        0.0f, 0.0f
                );
            }

            builder.addIndex(baseVertex);
            builder.addIndex(baseVertex + 1);
            builder.addIndex(baseVertex + 2);
        }
    }

    /**
     * Check if a block type has SBO geometry in this emitter's cache.
     *
     * @param blockType the block type to check
     * @return true if the cache contains a stamp for this type
     */
    public boolean hasBlock(IBlockType blockType) {
        return cache.has(blockType);
    }

    /**
     * Get the stamp cache backing this emitter.
     */
    public SBOStampCache getCache() {
        return cache;
    }
}
