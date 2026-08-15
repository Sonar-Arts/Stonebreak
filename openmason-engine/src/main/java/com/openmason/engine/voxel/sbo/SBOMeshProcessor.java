package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampCache;
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

    /** Area of one whole cell boundary plane — a full cube face. */
    private static final float FULL_FACE_AREA = 1.0f;
    /** Slack on the covered-area test, so float accumulation can't unset a full face. */
    private static final float AREA_EPSILON = 1e-3f;

    /**
     * Pre-baked vertex data for one face of an SBO block type.
     * Positions are relative to block origin (0,0,0); atlas UVs already applied.
     *
     * @param positions  vertex positions (x,y,z interleaved), relative to origin
     * @param normals    flat normals (nx,ny,nz interleaved)
     * @param atlasUVs   tile-local UVs (u,v interleaved) — full unit square per face
     * @param layers     texture-array layer index per vertex
     * @param vertexCount number of vertices (positions.length / 3)
     */
    public record FaceStamp(float[] positions, float[] normals, float[] atlasUVs,
                            float[] layers, int vertexCount) {}

    /**
     * Pre-baked geometry for one SBO block type, split by whether a neighbour
     * can hide it.
     *
     * <p>A cube's geometry is entirely {@code faces}: every triangle sits on a
     * cell boundary plane, so the usual neighbour cull applies. A shaped block
     * (stairs, slabs) also carries {@code interior} geometry — treads, risers —
     * that lives inside the cell and is therefore <em>never</em> culled.
     *
     * @param faces        boundary-flush geometry, indexed by MMS face id; cullable
     * @param interior     in-cell geometry, indexed by the MMS face it points along;
     *                     always emitted. The index still selects the face's texture
     *                     layer, UV rectangle and light-sampling direction.
     * @param occludesFace per MMS face, true when the flush geometry fully covers
     *                     that boundary plane — i.e. the block can hide a
     *                     neighbour's facing side
     */
    public record BlockStamp(FaceStamp[] faces, FaceStamp[] interior, boolean[] occludesFace) {}

    /** Cached block stamps keyed by block type ID. */
    private final Map<Integer, BlockStamp> stampCache = new HashMap<>();

    /** Tracks which block types have been processed (even if stamp is empty). */
    private final Set<Integer> processedTypes = new HashSet<>();

    /**
     * Process and cache an SBO block's mesh data into a pre-computed {@link BlockStamp}.
     * Stores the stamp in this processor's internal cache.
     *
     * @param blockType    the block type this SBO defines
     * @param sbo          the parsed SBO result containing mesh data
     * @param uvProvider   texture coordinate provider for atlas UV lookups
     * @return true if successfully processed
     */
    public boolean process(IBlockType blockType, SBOParseResult sbo,
                           ITextureCoordProvider uvProvider, ILayerIndexProvider layerProvider) {
        return process(blockType, sbo, uvProvider, layerProvider, null);
    }

    /**
     * Process an SBO block's mesh data into a pre-computed {@link BlockStamp}.
     * Stores the stamp in both this processor's internal cache and the provided external cache.
     *
     * @param blockType      the block type this SBO defines
     * @param sbo            the parsed SBO result containing mesh data
     * @param uvProvider     texture coordinate provider for atlas UV lookups
     * @param externalCache  optional external stamp cache to also store into (may be null)
     * @return true if successfully processed
     */
    public boolean process(IBlockType blockType, SBOParseResult sbo,
                           ITextureCoordProvider uvProvider, ILayerIndexProvider layerProvider,
                           SBOStampCache externalCache) {
        // Process the default (no-state) mesh.
        BlockStamp defaultStamp = processOne(blockType, null, sbo.meshData(), uvProvider, layerProvider);
        if (defaultStamp == null) {
            logger.warn("SBO for {} has no mesh data", blockType.getName());
            return false;
        }
        stampCache.put(blockType.getId(), defaultStamp);
        processedTypes.add(blockType.getId());
        if (externalCache != null) {
            externalCache.put(blockType, defaultStamp);
        }

        // Process every embedded state-variant mesh (1.3+). Each variant has
        // its own geometry AND its own materials list — both contribute to
        // a distinct (blockType, stateName) cache entry.
        int variantCount = 0;
        if (sbo.hasStates() && externalCache != null) {
            for (Map.Entry<String, OMOReader.ReadResult> entry : sbo.stateOmoData().entrySet()) {
                String stateName = entry.getKey();
                OMOReader.ReadResult variant = entry.getValue();
                if (variant == null || variant.meshData() == null) continue;
                BlockStamp variantStamp = processOne(blockType, stateName, variant.meshData(), uvProvider, layerProvider);
                if (variantStamp == null) continue;
                externalCache.put(blockType, stateName, variantStamp);
                variantCount++;
            }
        }

        int totalVerts = 0;
        int interiorVerts = 0;
        for (FaceStamp face : defaultStamp.faces()) totalVerts += face.vertexCount();
        for (FaceStamp face : defaultStamp.interior()) interiorVerts += face.vertexCount();
        logger.info("Processed SBO stamp for {}: {} boundary + {} interior default-state vertices, {} state variants",
                blockType.getName(), totalVerts, interiorVerts, variantCount);

        return true;
    }

    /** Builds one BlockStamp from a ParsedMeshData. {@code stateName} may be
     *  {@code null} for the default (no-state) mesh. Returns null if no geometry. */
    private BlockStamp processOne(IBlockType blockType, String stateName, ParsedMeshData meshData,
                                   ITextureCoordProvider uvProvider, ILayerIndexProvider layerProvider) {
        if (meshData == null || !meshData.hasGeometry()) return null;

        // Face assignment comes from the geometry, not the authored GMR face id:
        // that id only spans 0..5 and cannot describe a model with more faces
        // (a stair has ten), so anything past the sixth face used to be clamped
        // into the bottom bucket and culled away by the block underneath.
        SBONormalComputer.ProcessedMesh processed = SBONormalComputer.compute(
                meshData.vertices(),
                meshData.texCoords(),
                meshData.indices()
        );

        return buildBlockStamp(blockType, stateName, processed, uvProvider, layerProvider);
    }

    /**
     * Build a BlockStamp by bucketing triangles per face and remapping UVs to atlas space.
     * Boundary-flush and interior triangles go to separate bucket sets so the emitter can
     * cull the former and always draw the latter.
     */
    private BlockStamp buildBlockStamp(IBlockType blockType, String stateName,
                                        SBONormalComputer.ProcessedMesh mesh,
                                        ITextureCoordProvider uvProvider,
                                        ILayerIndexProvider layerProvider) {
        float[] verts = mesh.vertices();
        float[] norms = mesh.normals();
        float[] uvs = mesh.texCoords();
        int[] triFaces = mesh.triangleFaces();
        boolean[] triFlush = mesh.triangleFlush();
        int triangleCount = mesh.triangleCount();

        // Bucket index: [0] = boundary-flush (cullable), [1] = interior (always drawn).
        int[][] counts = new int[2][6];
        for (int tri = 0; tri < triangleCount; tri++) {
            counts[triFlush[tri] ? 0 : 1][triFaces[tri]]++;
        }

        float[][][] positions = new float[2][6][];
        float[][][] normals = new float[2][6][];
        float[][][] texCoords = new float[2][6][];
        float[][][] layers = new float[2][6][];
        int[][] insert = new int[2][6];

        // Per-face texture-array layer and atlas UV rectangle. State-aware layer
        // lookup resolves to the variant's texture set when stateName is non-null.
        float[] faceLayer = new float[6];
        float[][] atlasUVBounds = new float[6][];
        for (int f = 0; f < 6; f++) {
            faceLayer[f] = layerProvider.getBlockFaceLayer(blockType, stateName, f);
            atlasUVBounds[f] = uvProvider.getBlockFaceUVs(blockType, f);
            if (atlasUVBounds[f] == null || atlasUVBounds[f].length < 4) {
                // Fallback to full UV range if no atlas entry
                atlasUVBounds[f] = new float[]{0f, 0f, 1f, 1f};
                logger.warn("No atlas UVs for {} face {}, using full range", blockType.getName(), f);
            }
            for (int bucket = 0; bucket < 2; bucket++) {
                int vertCount = counts[bucket][f] * 3;
                positions[bucket][f] = new float[vertCount * 3];
                normals[bucket][f] = new float[vertCount * 3];
                texCoords[bucket][f] = new float[vertCount * 2];
                layers[bucket][f] = new float[vertCount];
                java.util.Arrays.fill(layers[bucket][f], faceLayer[f]);
            }
        }

        // Flush geometry also decides whether this block can hide a neighbour:
        // sum the boundary-plane area each face covers (the projected triangle
        // area) and call the face occluding once it fills the whole unit square.
        float[] coveredArea = new float[6];

        for (int tri = 0; tri < triangleCount; tri++) {
            int face = triFaces[tri];
            int bucket = triFlush[tri] ? 0 : 1;

            float au1 = atlasUVBounds[face][0];
            float av1 = atlasUVBounds[face][1];
            float au2 = atlasUVBounds[face][2];
            float av2 = atlasUVBounds[face][3];

            if (bucket == 0) {
                coveredArea[face] += planeArea(verts, tri, SBOFaceConventions.axisOf(face));
            }

            for (int v = 0; v < 3; v++) {
                int srcIdx = tri * 3 + v;
                int pOff = srcIdx * 3;
                int tOff = srcIdx * 2;

                int dstVert = insert[bucket][face];
                int dstPOff = dstVert * 3;
                int dstTOff = dstVert * 2;

                positions[bucket][face][dstPOff] = verts[pOff];
                positions[bucket][face][dstPOff + 1] = verts[pOff + 1];
                positions[bucket][face][dstPOff + 2] = verts[pOff + 2];

                normals[bucket][face][dstPOff] = norms[pOff];
                normals[bucket][face][dstPOff + 1] = norms[pOff + 1];
                normals[bucket][face][dstPOff + 2] = norms[pOff + 2];

                // Remap UVs from SBO [0,1] to atlas bounds
                texCoords[bucket][face][dstTOff] = au1 + uvs[tOff] * (au2 - au1);
                texCoords[bucket][face][dstTOff + 1] = av1 + uvs[tOff + 1] * (av2 - av1);

                insert[bucket][face]++;
            }
        }

        FaceStamp[] flushStamps = new FaceStamp[6];
        FaceStamp[] interiorStamps = new FaceStamp[6];
        boolean[] occludes = new boolean[6];
        for (int f = 0; f < 6; f++) {
            flushStamps[f] = new FaceStamp(positions[0][f], normals[0][f], texCoords[0][f],
                    layers[0][f], counts[0][f] * 3);
            interiorStamps[f] = new FaceStamp(positions[1][f], normals[1][f], texCoords[1][f],
                    layers[1][f], counts[1][f] * 3);
            occludes[f] = coveredArea[f] >= FULL_FACE_AREA - AREA_EPSILON;
        }

        return new BlockStamp(flushStamps, interiorStamps, occludes);
    }

    /** Area of a de-indexed triangle projected onto the plane perpendicular to {@code axis}. */
    private static float planeArea(float[] verts, int tri, int axis) {
        int base = tri * 9;
        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        float ux = verts[base + 3 + u] - verts[base + u];
        float uy = verts[base + 3 + v] - verts[base + v];
        float vx = verts[base + 6 + u] - verts[base + u];
        float vy = verts[base + 6 + v] - verts[base + v];
        return Math.abs(ux * vy - uy * vx) * 0.5f;
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
