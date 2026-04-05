package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.SBONormalComputer;
import com.openmason.engine.voxel.sbo.SBORenderData;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry provider for SBO-defined blocks.
 *
 * <p>Thread-safe: per-chunk state is stored in {@link ChunkBuildContext} objects
 * held on a ThreadLocal, not on the provider itself.
 */
public class MmsSBOBlockProvider implements MmsBlockGeometryProvider {

    private final SBOMeshProcessor meshProcessor;
    private final MmsFaceCullingService cullingService;

    /** Thread-local per-chunk build context. */
    private final ThreadLocal<ChunkBuildContext> buildContext = ThreadLocal.withInitial(ChunkBuildContext::new);

    /** Per-chunk mutable state for collecting SBO block positions. */
    public static class ChunkBuildContext {
        final List<int[]> pendingBlocks = new ArrayList<>();
        IBlockType pendingBlockType;
        int chunkX, chunkZ;
        final int[] faceIndexOffsets = new int[6];
        final int[] faceIndexCounts = new int[6];

        void reset() {
            pendingBlocks.clear();
            pendingBlockType = null;
            for (int i = 0; i < 6; i++) {
                faceIndexOffsets[i] = 0;
                faceIndexCounts[i] = 0;
            }
        }
    }

    public MmsSBOBlockProvider(SBOMeshProcessor meshProcessor, MmsFaceCullingService cullingService) {
        this.meshProcessor = meshProcessor;
        this.cullingService = cullingService;
    }

    @Override
    public boolean handles(IBlockType blockType) {
        return meshProcessor.hasMesh(blockType);
    }

    /**
     * Reset for a new chunk build (thread-safe via ThreadLocal).
     */
    public void resetForChunk() {
        buildContext.get().reset();
    }

    @Override
    public void addBlockGeometry(MmsMeshBuilder builder, IBlockType blockType,
                                 int lx, int ly, int lz, int chunkX, int chunkZ,
                                 CcoChunkData chunkData) {
        ChunkBuildContext ctx = buildContext.get();
        ctx.pendingBlocks.add(new int[]{lx, ly, lz});
        ctx.pendingBlockType = blockType;
        ctx.chunkX = chunkX;
        ctx.chunkZ = chunkZ;
    }

    /**
     * Flush collected SBO blocks into the mesh builder, sorted by face.
     */
    public void flushToBuilder(MmsMeshBuilder builder, CcoChunkData chunkData) {
        ChunkBuildContext ctx = buildContext.get();
        if (ctx.pendingBlocks.isEmpty() || ctx.pendingBlockType == null) return;

        SBONormalComputer.ProcessedMesh mesh = meshProcessor.getMesh(ctx.pendingBlockType);
        if (mesh == null) return;

        int[] originalFaceIds = meshProcessor.getTriangleToFaceId(ctx.pendingBlockType);
        int cs = CcoBounds.getConfig().chunkSize();

        float[] verts = mesh.vertices();
        float[] norms = mesh.normals();
        float[] uvs = mesh.texCoords();
        int triangleCount = mesh.triangleCount();

        // Emit triangles sorted by face ID with face culling.
        // Face IDs have been remapped from GMR convention to MMS convention
        // by SBOMeshProcessor using the known GMR→MMS face mapping.
        for (int face = 0; face < 6; face++) {
            int indexOffset = builder.getIndexCount();
            int trianglesForFace = 0;

            for (int[] pos : ctx.pendingBlocks) {
                int lx = pos[0], ly = pos[1], lz = pos[2];
                float worldX = lx + ctx.chunkX * cs + 0.5f;
                float worldY = ly + 0.5f;
                float worldZ = lz + ctx.chunkZ * cs + 0.5f;

                // Cull face if neighbor in that direction is opaque
                if (cullingService != null &&
                    !cullingService.shouldRenderFace(ctx.pendingBlockType, lx, ly, lz, face, chunkData)) {
                    continue;
                }

                for (int tri = 0; tri < triangleCount; tri++) {
                    int faceId = (originalFaceIds != null && tri < originalFaceIds.length)
                            ? originalFaceIds[tri] : 0;
                    if (faceId < 0 || faceId >= 6) faceId = 0;
                    if (faceId != face) continue;

                    int baseVertex = builder.getVertexCount();

                    for (int v = 0; v < 3; v++) {
                        int i = tri * 3 + v;
                        int vOff = i * 3;
                        int tOff = i * 2;

                        builder.addVertex(
                                verts[vOff] + worldX,
                                verts[vOff + 1] + worldY,
                                verts[vOff + 2] + worldZ,
                                uvs[tOff], uvs[tOff + 1],
                                norms[vOff], norms[vOff + 1], norms[vOff + 2],
                                0.0f, 0.0f
                        );
                    }

                    builder.addIndex(baseVertex);
                    builder.addIndex(baseVertex + 1);
                    builder.addIndex(baseVertex + 2);
                    trianglesForFace++;
                }
            }

            ctx.faceIndexOffsets[face] = indexOffset;
            ctx.faceIndexCounts[face] = trianglesForFace * 3;
        }
    }

    /**
     * Build face batches for per-face texture rendering.
     */
    public SBORenderData.FaceBatch[] buildFaceBatches(IBlockType blockType) {
        ChunkBuildContext ctx = buildContext.get();
        var faceTextures = meshProcessor.getFaceTextures(blockType);
        SBORenderData.FaceBatch[] batches = new SBORenderData.FaceBatch[6];

        for (int face = 0; face < 6; face++) {
            int texId = faceTextures.getOrDefault(face, 0);
            batches[face] = new SBORenderData.FaceBatch(texId, ctx.faceIndexOffsets[face], ctx.faceIndexCounts[face]);
        }

        return batches;
    }

    public IBlockType getPendingBlockType() {
        return buildContext.get().pendingBlockType;
    }

    public SBOMeshProcessor getMeshProcessor() {
        return meshProcessor;
    }
}
