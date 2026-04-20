package com.stonebreak.world.lod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;

/**
 * Builds a coarse {@link MmsMeshData} for one {@link LodChunk}. Emits a single
 * top quad per column plus downward skirts toward lower neighbours, all in
 * world-space so the render pass can use an identity model matrix.
 *
 * Submerged columns paint a static sheet at sea level using the water atlas UV
 * with waterHeight=0, so the main shader's opaque branch draws them as flat
 * coloured geometry without wave displacement or transparency.
 *
 * Vertex layout matches {@link com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle}:
 *   pos[3] + tex[2] + normal[3] + waterHeight + alphaTest + translucent.
 */
public final class LodMesher {
    private static final int SIZE = LodChunk.CHUNK_SIZE;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    // Max quads per column: top(1) + 4 skirts + tree contribution (4 trunk sides + 5 canopy faces).
    private static final int MAX_QUADS_PER_COLUMN = 14;
    private static final int MAX_QUADS = SIZE * SIZE * MAX_QUADS_PER_COLUMN;
    private static final int MAX_VERTS = MAX_QUADS * 4;
    private static final int MAX_INDICES = MAX_QUADS * 6;

    // Distant-canopy footprint and vertical extent in blocks (centered on column).
    private static final float CANOPY_RADIUS = 1.5f;
    private static final float CANOPY_HALF_HEIGHT = 1.5f;

    private final TextureAtlas atlas;

    public LodMesher(TextureAtlas atlas) {
        this.atlas = atlas;
    }

    public MmsMeshData build(LodChunk chunk) {
        float[] positions = new float[MAX_VERTS * 3];
        float[] texCoords = new float[MAX_VERTS * 2];
        float[] normals = new float[MAX_VERTS * 3];
        float[] waterFlags = new float[MAX_VERTS];
        float[] alphaFlags = new float[MAX_VERTS];
        float[] translucentFlags = new float[MAX_VERTS];
        int[] indices = new int[MAX_INDICES];

        QuadWriter w = new QuadWriter(positions, texCoords, normals,
                waterFlags, alphaFlags, translucentFlags, indices);

        int baseX = chunk.getChunkX() * SIZE;
        int baseZ = chunk.getChunkZ() * SIZE;

        for (int ix = 0; ix < SIZE; ix++) {
            for (int iz = 0; iz < SIZE; iz++) {
                BlockType surface = chunk.surfaceAt(ix, iz);
                int terrainH = chunk.heightAt(ix, iz);
                int topY = (surface == BlockType.WATER) ? SEA_LEVEL : terrainH;

                float wx = baseX + ix;
                float wz = baseZ + iz;

                // Top quad
                float[] topUV = atlas.getBlockFaceUVs(surface, BlockType.Face.TOP);
                w.topQuad(wx, topY, wz, topUV);

                // Skirts toward each lower neighbour
                emitSkirtIfLower(w, chunk, ix, iz, +1, 0, surface, topY, wx, wz);
                emitSkirtIfLower(w, chunk, ix, iz, -1, 0, surface, topY, wx, wz);
                emitSkirtIfLower(w, chunk, ix, iz, 0, +1, surface, topY, wx, wz);
                emitSkirtIfLower(w, chunk, ix, iz, 0, -1, surface, topY, wx, wz);

                // Tree silhouette, if the deterministic probe placed one here.
                TreeSample tree = chunk.treeAt(ix, iz);
                if (tree != null && surface != BlockType.WATER) {
                    emitTree(w, wx, wz, terrainH, tree);
                }
            }
        }

        int vertCount = w.vertCount;
        int idxCount = w.idxCount;

        if (idxCount == 0) {
            return MmsMeshData.empty();
        }

        return new MmsMeshData(
                Arrays.copyOf(positions, vertCount * 3),
                Arrays.copyOf(texCoords, vertCount * 2),
                Arrays.copyOf(normals, vertCount * 3),
                Arrays.copyOf(waterFlags, vertCount),
                Arrays.copyOf(alphaFlags, vertCount),
                Arrays.copyOf(translucentFlags, vertCount),
                Arrays.copyOf(indices, idxCount),
                idxCount
        );
    }

    /**
     * Emits a low-poly silhouette for a distant tree: a 4-sided trunk column and
     * a flat canopy quad centered on the trunk's top. Uses the tree's real trunk
     * and leaves block textures so variants visibly differ across biomes.
     */
    private void emitTree(QuadWriter w, float wx, float wz, int terrainH, TreeSample tree) {
        float trunkBase = terrainH;
        float trunkTop = terrainH + tree.trunkHeight();
        float cx = wx + 0.5f;
        float cz = wz + 0.5f;

        float[] trunkUV = atlas.getBlockFaceUVs(tree.kind().trunkBlock(), BlockType.Face.SIDE_NORTH);
        // Trunk: 4 axis-aligned side faces. Opaque wood — no alpha test needed.
        w.axisAlignedQuad(cx + 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkTop, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz - 0.5f,
                          1, 0, 0, trunkUV, 0f);
        w.axisAlignedQuad(cx - 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkTop, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz + 0.5f,
                          -1, 0, 0, trunkUV, 0f);
        w.axisAlignedQuad(cx + 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkBase, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz + 0.5f,
                          0, 0, 1, trunkUV, 0f);
        w.axisAlignedQuad(cx - 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkBase, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz - 0.5f,
                          0, 0, -1, trunkUV, 0f);

        // Canopy: small 5-face box (top + 4 sides) sitting above the trunk. Flag as
        // alpha-tested so the leaves texture's transparent pixels are discarded —
        // without this, the opaque branch paints transparent texels as solid black.
        float[] leafTopUV = atlas.getBlockFaceUVs(tree.kind().leavesBlock(), BlockType.Face.TOP);
        float[] leafSideUV = atlas.getBlockFaceUVs(tree.kind().leavesBlock(), BlockType.Face.SIDE_NORTH);
        float canopyMidY = trunkTop + 1f;
        float canopyTop = canopyMidY + CANOPY_HALF_HEIGHT;
        float canopyBot = canopyMidY - CANOPY_HALF_HEIGHT;
        float xMin = cx - CANOPY_RADIUS;
        float xMax = cx + CANOPY_RADIUS;
        float zMin = cz - CANOPY_RADIUS;
        float zMax = cz + CANOPY_RADIUS;

        // Top face
        w.axisAlignedQuad(xMin, canopyTop, zMin,
                          xMax, canopyTop, zMin,
                          xMax, canopyTop, zMax,
                          xMin, canopyTop, zMax,
                          0, 1, 0, leafTopUV, 1f);
        // +X face
        w.axisAlignedQuad(xMax, canopyTop, zMin,
                          xMax, canopyTop, zMax,
                          xMax, canopyBot, zMax,
                          xMax, canopyBot, zMin,
                          1, 0, 0, leafSideUV, 1f);
        // -X face
        w.axisAlignedQuad(xMin, canopyTop, zMax,
                          xMin, canopyTop, zMin,
                          xMin, canopyBot, zMin,
                          xMin, canopyBot, zMax,
                          -1, 0, 0, leafSideUV, 1f);
        // +Z face
        w.axisAlignedQuad(xMax, canopyTop, zMax,
                          xMin, canopyTop, zMax,
                          xMin, canopyBot, zMax,
                          xMax, canopyBot, zMax,
                          0, 0, 1, leafSideUV, 1f);
        // -Z face
        w.axisAlignedQuad(xMin, canopyTop, zMin,
                          xMax, canopyTop, zMin,
                          xMax, canopyBot, zMin,
                          xMin, canopyBot, zMin,
                          0, 0, -1, leafSideUV, 1f);
    }

    private void emitSkirtIfLower(QuadWriter w, LodChunk chunk,
                                  int ix, int iz, int dx, int dz,
                                  BlockType surface, int topY, float wx, float wz) {
        int nH = chunk.heightAt(ix + dx, iz + dz);
        int nTopY = (nH < SEA_LEVEL) ? SEA_LEVEL : nH;
        if (nTopY >= topY) {
            return;
        }
        float[] sideUV = atlas.getBlockFaceUVs(surface, faceForDirection(dx, dz));
        w.skirtQuad(wx, wz, dx, dz, topY, nTopY, sideUV);
    }

    private static BlockType.Face faceForDirection(int dx, int dz) {
        if (dx > 0) return BlockType.Face.SIDE_EAST;
        if (dx < 0) return BlockType.Face.SIDE_WEST;
        if (dz > 0) return BlockType.Face.SIDE_SOUTH;
        return BlockType.Face.SIDE_NORTH;
    }

    /** Packs interleaved vertex/index writes into the target arrays. */
    private static final class QuadWriter {
        final float[] pos, tex, nrm, water, alpha, translucent;
        final int[] idx;
        int vertCount = 0;
        int idxCount = 0;

        QuadWriter(float[] pos, float[] tex, float[] nrm,
                   float[] water, float[] alpha, float[] translucent, int[] idx) {
            this.pos = pos;
            this.tex = tex;
            this.nrm = nrm;
            this.water = water;
            this.alpha = alpha;
            this.translucent = translucent;
            this.idx = idx;
        }

        void topQuad(float wx, float y, float wz, float[] uv) {
            // CCW viewed from +Y
            int v0 = pushVert(wx,     y, wz,     uv[0], uv[1], 0, 1, 0);
                    pushVert(wx + 1f, y, wz,     uv[2], uv[1], 0, 1, 0);
                    pushVert(wx + 1f, y, wz + 1f, uv[2], uv[3], 0, 1, 0);
                    pushVert(wx,     y, wz + 1f, uv[0], uv[3], 0, 1, 0);
            pushQuadIndices(v0);
        }

        void skirtQuad(float wx, float wz, int dx, int dz,
                       int topY, int bottomY, float[] uv) {
            float fTop = topY;
            float fBot = bottomY;
            if (dx > 0) {
                // +X face at x = wx+1, CCW from +X
                int v0 = pushVert(wx + 1f, fTop, wz,     uv[0], uv[1], 1, 0, 0);
                        pushVert(wx + 1f, fTop, wz + 1f, uv[2], uv[1], 1, 0, 0);
                        pushVert(wx + 1f, fBot, wz + 1f, uv[2], uv[3], 1, 0, 0);
                        pushVert(wx + 1f, fBot, wz,     uv[0], uv[3], 1, 0, 0);
                pushQuadIndices(v0);
            } else if (dx < 0) {
                int v0 = pushVert(wx, fTop, wz + 1f, uv[0], uv[1], -1, 0, 0);
                        pushVert(wx, fTop, wz,     uv[2], uv[1], -1, 0, 0);
                        pushVert(wx, fBot, wz,     uv[2], uv[3], -1, 0, 0);
                        pushVert(wx, fBot, wz + 1f, uv[0], uv[3], -1, 0, 0);
                pushQuadIndices(v0);
            } else if (dz > 0) {
                int v0 = pushVert(wx + 1f, fTop, wz + 1f, uv[0], uv[1], 0, 0, 1);
                        pushVert(wx,     fTop, wz + 1f, uv[2], uv[1], 0, 0, 1);
                        pushVert(wx,     fBot, wz + 1f, uv[2], uv[3], 0, 0, 1);
                        pushVert(wx + 1f, fBot, wz + 1f, uv[0], uv[3], 0, 0, 1);
                pushQuadIndices(v0);
            } else {
                int v0 = pushVert(wx,     fTop, wz, uv[0], uv[1], 0, 0, -1);
                        pushVert(wx + 1f, fTop, wz, uv[2], uv[1], 0, 0, -1);
                        pushVert(wx + 1f, fBot, wz, uv[2], uv[3], 0, 0, -1);
                        pushVert(wx,     fBot, wz, uv[0], uv[3], 0, 0, -1);
                pushQuadIndices(v0);
            }
        }

        /**
         * Writes a free-form quad from 4 explicit CCW-from-front corners with a
         * shared normal. {@code alphaFlag}=1 marks the quad for the shader's
         * alpha-test discard path (e.g. leaves). UV order: p0→(u1,v1),
         * p1→(u2,v1), p2→(u2,v2), p3→(u1,v2).
         */
        void axisAlignedQuad(float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz, float[] uv, float alphaFlag) {
            int v0 = pushVert(x0, y0, z0, uv[0], uv[1], nx, ny, nz, alphaFlag);
                    pushVert(x1, y1, z1, uv[2], uv[1], nx, ny, nz, alphaFlag);
                    pushVert(x2, y2, z2, uv[2], uv[3], nx, ny, nz, alphaFlag);
                    pushVert(x3, y3, z3, uv[0], uv[3], nx, ny, nz, alphaFlag);
            pushQuadIndices(v0);
        }

        private int pushVert(float x, float y, float z,
                             float u, float v,
                             float nx, float ny, float nz) {
            return pushVert(x, y, z, u, v, nx, ny, nz, 0f);
        }

        private int pushVert(float x, float y, float z,
                             float u, float v,
                             float nx, float ny, float nz, float alphaFlag) {
            int vi = vertCount;
            int p3 = vi * 3;
            int p2 = vi * 2;
            pos[p3] = x; pos[p3 + 1] = y; pos[p3 + 2] = z;
            tex[p2] = u; tex[p2 + 1] = v;
            nrm[p3] = nx; nrm[p3 + 1] = ny; nrm[p3 + 2] = nz;
            water[vi] = 0f;
            alpha[vi] = alphaFlag;
            translucent[vi] = 0f;
            vertCount = vi + 1;
            return vi;
        }

        private void pushQuadIndices(int v0) {
            idx[idxCount++] = v0;
            idx[idxCount++] = v0 + 1;
            idx[idxCount++] = v0 + 2;
            idx[idxCount++] = v0;
            idx[idxCount++] = v0 + 2;
            idx[idxCount++] = v0 + 3;
        }
    }
}
