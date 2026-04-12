package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;

import java.util.*;

/**
 * Geometry provider for SBO-defined blocks using pre-computed stamps.
 *
 * <p>Thread-safe: per-chunk state is stored in {@link ChunkBuildContext} objects
 * held on a ThreadLocal, not on the provider itself.
 *
 * <p>Uses pre-computed {@link SBOMeshProcessor.BlockStamp} data for each SBO
 * block type. At mesh time, stamps are copied into the atlas mesh builder
 * with a position offset per block instance — no UV remapping, no triangle
 * bucketing, no per-vertex computation at mesh time.
 */
public class MmsSBOBlockProvider implements MmsBlockGeometryProvider {

    private final SBOMeshProcessor meshProcessor;
    private final MmsFaceCullingService cullingService;
    private SBOStampEmitter stampEmitter;

    /** Thread-local per-chunk build context. */
    private final ThreadLocal<ChunkBuildContext> buildContext = ThreadLocal.withInitial(ChunkBuildContext::new);

    /** Per-chunk mutable state for collecting SBO block positions, bucketed by block type. */
    public static class ChunkBuildContext {
        /** Max backing array size before shrinking on reset. */
        private static final int MAX_RETAINED_CAPACITY = 4096 * 3;

        final Map<IBlockType, IntBuffer> pendingBlocksByType = new LinkedHashMap<>();
        final ArrayDeque<IntBuffer> bufferPool = new ArrayDeque<>(4);
        int chunkX, chunkZ;

        void reset() {
            for (IntBuffer buf : pendingBlocksByType.values()) {
                buf.size = 0;
                if (buf.data.length > MAX_RETAINED_CAPACITY) {
                    buf.data = new int[64 * 3];
                }
                bufferPool.offer(buf);
            }
            pendingBlocksByType.clear();
        }

        IntBuffer acquireBuffer() {
            IntBuffer buf = bufferPool.poll();
            if (buf != null) {
                buf.size = 0;
                return buf;
            }
            return new IntBuffer(64 * 3);
        }
    }

    /** Growable primitive int array for block positions. */
    static final class IntBuffer {
        int[] data;
        int size;

        IntBuffer(int initialCapacity) {
            this.data = new int[initialCapacity];
            this.size = 0;
        }

        void add(int x, int y, int z) {
            int required = size + 3;
            if (required > data.length) {
                data = Arrays.copyOf(data, data.length + (data.length >> 1) + 3);
            }
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
        }

        int blockCount() { return size / 3; }
        boolean isEmpty() { return size == 0; }
    }

    public MmsSBOBlockProvider(SBOMeshProcessor meshProcessor, MmsFaceCullingService cullingService) {
        this.meshProcessor = meshProcessor;
        this.cullingService = cullingService;
    }

    /**
     * Set the stamp emitter for delegated geometry emission.
     * When set, {@link #flushBlockType} delegates to the emitter
     * instead of performing inline stamp emission.
     *
     * @param emitter the stamp emitter from the SBORendererAPI
     */
    public void setStampEmitter(SBOStampEmitter emitter) {
        this.stampEmitter = emitter;
    }

    @Override
    public boolean handles(IBlockType blockType) {
        return meshProcessor.hasMesh(blockType);
    }

    public void resetForChunk() {
        buildContext.get().reset();
    }

    @Override
    public void addBlockGeometry(MmsMeshBuilder builder, IBlockType blockType,
                                 int lx, int ly, int lz, int chunkX, int chunkZ,
                                 CcoChunkData chunkData) {
        ChunkBuildContext ctx = buildContext.get();
        IntBuffer buf = ctx.pendingBlocksByType.get(blockType);
        if (buf == null) {
            buf = ctx.acquireBuffer();
            ctx.pendingBlocksByType.put(blockType, buf);
        }
        buf.add(lx, ly, lz);
        ctx.chunkX = chunkX;
        ctx.chunkZ = chunkZ;
    }

    /**
     * Flush collected SBO blocks into the atlas mesh builder using pre-computed stamps.
     *
     * <p>For each face of the block stamp, iterates block positions, performs face
     * culling, and copies stamp vertex data with position offset into the builder.
     * Atlas-remapped UVs are already baked into the stamp — no per-vertex computation.
     *
     * @param atlasBuilder the atlas mesh builder to emit vertices/indices into
     * @param blockType    the specific SBO block type to flush
     * @param chunkData    chunk data for face culling lookups
     */
    public void flushBlockType(MmsMeshBuilder atlasBuilder, IBlockType blockType, CcoChunkData chunkData) {
        ChunkBuildContext ctx = buildContext.get();
        IntBuffer blocks = ctx.pendingBlocksByType.get(blockType);
        if (blocks == null || blocks.isEmpty()) return;

        int cs = CcoBounds.getConfig().chunkSize();
        int blockCount = blocks.blockCount();

        // Delegate to SBOStampEmitter when available
        if (stampEmitter != null) {
            for (int b = 0; b < blockCount; b++) {
                int base = b * 3;
                int lx = blocks.data[base];
                int ly = blocks.data[base + 1];
                int lz = blocks.data[base + 2];

                float worldX = lx + ctx.chunkX * cs + 0.5f;
                float worldY = ly + 0.5f;
                float worldZ = lz + ctx.chunkZ * cs + 0.5f;

                stampEmitter.emitBlock(atlasBuilder, blockType, lx, ly, lz,
                        worldX, worldY, worldZ, chunkData);
            }
            return;
        }

        // Legacy fallback: inline emission using mesh processor directly
        SBOMeshProcessor.BlockStamp stamp = meshProcessor.getBlockStamp(blockType);
        if (stamp == null) return;

        for (int face = 0; face < 6; face++) {
            SBOMeshProcessor.FaceStamp faceStamp = stamp.faces()[face];
            if (faceStamp.vertexCount() == 0) continue;

            float[] pos = faceStamp.positions();
            float[] nrm = faceStamp.normals();
            float[] uv = faceStamp.atlasUVs();
            int triCount = faceStamp.vertexCount() / 3;

            for (int b = 0; b < blockCount; b++) {
                int base = b * 3;
                int lx = blocks.data[base];
                int ly = blocks.data[base + 1];
                int lz = blocks.data[base + 2];

                if (cullingService != null &&
                    !cullingService.shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                    continue;
                }

                float worldX = lx + ctx.chunkX * cs + 0.5f;
                float worldY = ly + 0.5f;
                float worldZ = lz + ctx.chunkZ * cs + 0.5f;

                for (int tri = 0; tri < triCount; tri++) {
                    int baseVertex = atlasBuilder.getVertexCount();

                    for (int v = 0; v < 3; v++) {
                        int vi = tri * 3 + v;
                        int pOff = vi * 3;
                        int tOff = vi * 2;

                        atlasBuilder.addVertex(
                                pos[pOff] + worldX,
                                pos[pOff + 1] + worldY,
                                pos[pOff + 2] + worldZ,
                                uv[tOff], uv[tOff + 1],
                                nrm[pOff], nrm[pOff + 1], nrm[pOff + 2],
                                0.0f, 0.0f
                        );
                    }

                    atlasBuilder.addIndex(baseVertex);
                    atlasBuilder.addIndex(baseVertex + 1);
                    atlasBuilder.addIndex(baseVertex + 2);
                }
            }
        }
    }

    /**
     * Get all unique SBO block types collected for the current chunk.
     */
    public Set<IBlockType> getPendingBlockTypes() {
        return buildContext.get().pendingBlocksByType.keySet();
    }

    public SBOMeshProcessor getMeshProcessor() {
        return meshProcessor;
    }
}
