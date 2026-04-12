package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ITextureCoordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Processes SBO mesh data into pre-computed {@link BlockStamp}s for efficient
 * chunk mesh generation.
 *
 * <p>For each SBO block type, pre-computes flat normals, de-indexes the mesh,
 * remaps UVs from SBO-local [0,1] space to atlas UV space, and organizes
 * geometry per-face into ready-to-stamp data.
 *
 * <p>At mesh generation time, each block instance just copies the pre-baked
 * stamp data with a position offset — no UV remapping, no triangle bucketing.
 *
 * <p>Cached by block ID so processing happens once at startup, not per-chunk.
 */
public class SBOMeshProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SBOMeshProcessor.class);

    /**
     * Static mapping from GMR face ID to MMS face ID.
     * GMR: 0=FRONT(+Z), 1=BACK(-Z), 2=LEFT(-X), 3=RIGHT(+X), 4=TOP(+Y), 5=BOTTOM(-Y)
     * MMS: 0=TOP(+Y), 1=BOTTOM(-Y), 2=NORTH(-Z), 3=SOUTH(+Z), 4=EAST(+X), 5=WEST(-X)
     */
    private static final int[] GMR_TO_MMS_FACE = {
            3, // GMR 0 (FRONT +Z)  → MMS 3 (SOUTH +Z)
            2, // GMR 1 (BACK -Z)   → MMS 2 (NORTH -Z)
            5, // GMR 2 (LEFT -X)   → MMS 5 (WEST -X)
            4, // GMR 3 (RIGHT +X)  → MMS 4 (EAST +X)
            0, // GMR 4 (TOP +Y)    → MMS 0 (TOP +Y)
            1  // GMR 5 (BOTTOM -Y) → MMS 1 (BOTTOM -Y)
    };

    /**
     * Pre-baked vertex data for one face of an SBO block type.
     * Positions are relative to block origin (0,0,0); atlas UVs already applied.
     *
     * @param positions  vertex positions (x,y,z interleaved), relative to origin
     * @param normals    flat normals (nx,ny,nz interleaved)
     * @param atlasUVs   atlas-remapped UVs (u,v interleaved)
     * @param vertexCount number of vertices (positions.length / 3)
     */
    public record FaceStamp(float[] positions, float[] normals, float[] atlasUVs, int vertexCount) {}

    /**
     * All 6 face stamps for one SBO block type. faces[0..5] indexed by MMS face ID.
     */
    public record BlockStamp(FaceStamp[] faces) {}

    /** Cached block stamps keyed by block type ID. */
    private final Map<Integer, BlockStamp> stampCache = new HashMap<>();

    /** Tracks which block types have been processed (even if stamp is empty). */
    private final Set<Integer> processedTypes = new HashSet<>();

    /**
     * Process and cache an SBO block's mesh data into a pre-computed {@link BlockStamp}.
     *
     * @param blockType    the block type this SBO defines
     * @param sbo          the parsed SBO result containing mesh data
     * @param uvProvider   texture coordinate provider for atlas UV lookups
     * @return true if successfully processed
     */
    public boolean process(IBlockType blockType, SBOParseResult sbo, ITextureCoordProvider uvProvider) {
        ParsedMeshData meshData = sbo.meshData();
        if (meshData == null || !meshData.hasGeometry()) {
            logger.warn("SBO for {} has no mesh data", blockType.getName());
            return false;
        }

        // Compute flat normals and de-index
        SBONormalComputer.ProcessedMesh processed = SBONormalComputer.compute(
                meshData.vertices(),
                meshData.texCoords(),
                meshData.indices()
        );

        // Remap face IDs from GMR to MMS convention
        int[] remappedFaceIds = null;
        if (meshData.triangleToFaceId() != null) {
            int[] originalFaceIds = meshData.triangleToFaceId();
            remappedFaceIds = new int[originalFaceIds.length];
            for (int i = 0; i < originalFaceIds.length; i++) {
                remappedFaceIds[i] = GMR_TO_MMS_FACE[Math.clamp(originalFaceIds[i], 0, 5)];
            }
        }

        // Build per-face atlas-remapped stamps
        BlockStamp stamp = buildBlockStamp(blockType, processed, remappedFaceIds, uvProvider);
        stampCache.put(blockType.getId(), stamp);
        processedTypes.add(blockType.getId());

        // Log mesh info
        int totalVerts = 0;
        for (FaceStamp face : stamp.faces()) {
            totalVerts += face.vertexCount();
        }
        logger.info("Processed SBO stamp for {}: {} total vertices across {} faces, {} triangles",
                blockType.getName(), totalVerts, 6, processed.triangleCount());

        return true;
    }

    /**
     * Build a BlockStamp by bucketing triangles per face and remapping UVs to atlas space.
     */
    private BlockStamp buildBlockStamp(IBlockType blockType, SBONormalComputer.ProcessedMesh mesh,
                                        int[] faceIds, ITextureCoordProvider uvProvider) {
        float[] verts = mesh.vertices();
        float[] norms = mesh.normals();
        float[] uvs = mesh.texCoords();
        int triangleCount = mesh.triangleCount();

        // Count triangles per face
        int[] faceCounts = new int[6];
        for (int tri = 0; tri < triangleCount; tri++) {
            int faceId = (faceIds != null && tri < faceIds.length) ? faceIds[tri] : 0;
            if (faceId < 0 || faceId >= 6) faceId = 0;
            faceCounts[faceId]++;
        }

        // Allocate per-face arrays
        float[][] facePositions = new float[6][];
        float[][] faceNormals = new float[6][];
        float[][] faceUVs = new float[6][];
        int[] faceInsert = new int[6]; // insertion cursor per face

        for (int f = 0; f < 6; f++) {
            int vertCount = faceCounts[f] * 3; // 3 verts per triangle
            facePositions[f] = new float[vertCount * 3];
            faceNormals[f] = new float[vertCount * 3];
            faceUVs[f] = new float[vertCount * 2];
        }

        // Get atlas UV bounds per face: [u1, v1, u2, v2]
        float[][] atlasUVBounds = new float[6][];
        for (int f = 0; f < 6; f++) {
            atlasUVBounds[f] = uvProvider.getBlockFaceUVs(blockType, f);
            if (atlasUVBounds[f] == null || atlasUVBounds[f].length < 4) {
                // Fallback to full UV range if no atlas entry
                atlasUVBounds[f] = new float[]{0f, 0f, 1f, 1f};
                logger.warn("No atlas UVs for {} face {}, using full range", blockType.getName(), f);
            }
        }

        // Fill per-face arrays with position/normal/remapped-UV data
        for (int tri = 0; tri < triangleCount; tri++) {
            int faceId = (faceIds != null && tri < faceIds.length) ? faceIds[tri] : 0;
            if (faceId < 0 || faceId >= 6) faceId = 0;

            float au1 = atlasUVBounds[faceId][0];
            float av1 = atlasUVBounds[faceId][1];
            float au2 = atlasUVBounds[faceId][2];
            float av2 = atlasUVBounds[faceId][3];

            for (int v = 0; v < 3; v++) {
                int srcIdx = tri * 3 + v;
                int pOff = srcIdx * 3;
                int tOff = srcIdx * 2;

                int dstVert = faceInsert[faceId];
                int dstPOff = dstVert * 3;
                int dstTOff = dstVert * 2;

                // Copy positions (relative to origin)
                facePositions[faceId][dstPOff] = verts[pOff];
                facePositions[faceId][dstPOff + 1] = verts[pOff + 1];
                facePositions[faceId][dstPOff + 2] = verts[pOff + 2];

                // Copy normals
                faceNormals[faceId][dstPOff] = norms[pOff];
                faceNormals[faceId][dstPOff + 1] = norms[pOff + 1];
                faceNormals[faceId][dstPOff + 2] = norms[pOff + 2];

                // Remap UVs from SBO [0,1] to atlas bounds
                float uSbo = uvs[tOff];
                float vSbo = uvs[tOff + 1];
                faceUVs[faceId][dstTOff] = au1 + uSbo * (au2 - au1);
                faceUVs[faceId][dstTOff + 1] = av1 + vSbo * (av2 - av1);

                faceInsert[faceId]++;
            }
        }

        // Build FaceStamp records
        FaceStamp[] stamps = new FaceStamp[6];
        for (int f = 0; f < 6; f++) {
            stamps[f] = new FaceStamp(facePositions[f], faceNormals[f], faceUVs[f], faceCounts[f] * 3);
        }

        return new BlockStamp(stamps);
    }

    /**
     * Get the pre-computed block stamp for a block type.
     *
     * @param blockType the block type
     * @return the block stamp, or null if not processed
     */
    public BlockStamp getBlockStamp(IBlockType blockType) {
        return stampCache.get(blockType.getId());
    }

    /**
     * Check if a block type has a processed SBO mesh.
     */
    public boolean hasMesh(IBlockType blockType) {
        return stampCache.containsKey(blockType.getId());
    }

    /**
     * Get the number of processed block types.
     */
    public int size() {
        return stampCache.size();
    }
}
