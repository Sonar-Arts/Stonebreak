package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;

/**
 * Builds a coarse {@link MmsMeshData} for one {@link FastLodChunkData}.
 *
 * <p>For each interior cell it emits one top quad of size
 * {@code cellSize × cellSize} blocks and up to four skirts facing lower
 * neighbour cells. At the finest level ({@link FastLodLevel#L0}) it additionally
 * emits a low-poly tree silhouette per cell that carries a tree sample.
 *
 * <p>Allocations are sized to the worst case <em>for the specific level</em>
 * instead of always provisioning for L0, so a L4 node allocates ~{@code 1/256}
 * the buffers of an L0 node.
 *
 * <p>Submerged cells paint a sea-level sheet (waterHeight=0, alphaFlag=0) so
 * the shader's opaque branch draws them without wave displacement.
 */
public final class FastLodMesher {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    private static final int TOP_QUAD            = 1;
    private static final int MAX_SKIRTS_PER_CELL = 4;
    private static final int TREE_QUADS_PER_CELL = 9;   // 4 trunk + 5 canopy

    private static final float CANOPY_RADIUS      = 1.5f;
    private static final float CANOPY_HALF_HEIGHT = 1.5f;

    private final TextureAtlas atlas;

    public FastLodMesher(TextureAtlas atlas) {
        this.atlas = atlas;
    }

    public MmsMeshData build(FastLodChunkData data) {
        FastLodLevel level = data.level();
        int cellSize     = level.cellSize();
        int cellsPerAxis = level.cellsPerAxis();

        int maxQuadsPerCell = TOP_QUAD + MAX_SKIRTS_PER_CELL
                + (level.emitsTrees() ? TREE_QUADS_PER_CELL : 0);
        int maxQuads   = cellsPerAxis * cellsPerAxis * maxQuadsPerCell;
        int maxVerts   = maxQuads * 4;
        int maxIndices = maxQuads * 6;

        float[] positions       = new float[maxVerts * 3];
        float[] texCoords       = new float[maxVerts * 2];
        float[] normals         = new float[maxVerts * 3];
        float[] waterFlags      = new float[maxVerts];
        float[] alphaFlags      = new float[maxVerts];
        float[] translucentFlags = new float[maxVerts];
        int[]   indices         = new int[maxIndices];

        QuadWriter w = new QuadWriter(positions, texCoords, normals,
                waterFlags, alphaFlags, translucentFlags, indices);

        int baseX = data.chunkX() * WorldConfiguration.CHUNK_SIZE;
        int baseZ = data.chunkZ() * WorldConfiguration.CHUNK_SIZE;

        for (int ix = 0; ix < cellsPerAxis; ix++) {
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                BlockType surface = data.surfaceAt(ix, iz);
                int terrainH = data.heightAt(ix, iz);
                int topY = (surface == BlockType.WATER) ? SEA_LEVEL : terrainH;

                float wx = baseX + ix * cellSize;
                float wz = baseZ + iz * cellSize;

                float[] topUV = atlas.getBlockFaceUVs(surface, BlockType.Face.TOP);
                w.topQuad(wx, topY, wz, cellSize, topUV);

                emitSkirtIfLower(w, data, ix, iz, +1, 0, surface, topY, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, -1, 0, surface, topY, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, 0, +1, surface, topY, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, 0, -1, surface, topY, wx, wz, cellSize);

                if (level.emitsTrees()) {
                    TreeSample tree = data.treeAt(ix, iz);
                    if (tree != null && surface != BlockType.WATER) {
                        // L0 has 1-block cells, so placing the tree at the cell's (wx, wz)
                        // matches the classic mesher's per-column placement.
                        emitTree(w, wx, wz, terrainH, tree);
                    }
                }
            }
        }

        if (w.idxCount == 0) {
            return MmsMeshData.empty();
        }

        return new MmsMeshData(
                Arrays.copyOf(positions, w.vertCount * 3),
                Arrays.copyOf(texCoords, w.vertCount * 2),
                Arrays.copyOf(normals, w.vertCount * 3),
                Arrays.copyOf(waterFlags, w.vertCount),
                Arrays.copyOf(alphaFlags, w.vertCount),
                Arrays.copyOf(translucentFlags, w.vertCount),
                Arrays.copyOf(indices, w.idxCount),
                w.idxCount
        );
    }

    private void emitSkirtIfLower(QuadWriter w, FastLodChunkData data,
                                  int ix, int iz, int dx, int dz,
                                  BlockType surface, int topY,
                                  float wx, float wz, int cellSize) {
        int nH = data.heightAt(ix + dx, iz + dz);
        int nTopY = (nH < SEA_LEVEL) ? SEA_LEVEL : nH;
        if (nTopY >= topY) {
            return;
        }
        float[] sideUV = atlas.getBlockFaceUVs(surface, faceForDirection(dx, dz));
        w.skirtQuad(wx, wz, cellSize, dx, dz, topY, nTopY, sideUV);
    }

    private static BlockType.Face faceForDirection(int dx, int dz) {
        if (dx > 0) return BlockType.Face.SIDE_EAST;
        if (dx < 0) return BlockType.Face.SIDE_WEST;
        if (dz > 0) return BlockType.Face.SIDE_SOUTH;
        return BlockType.Face.SIDE_NORTH;
    }

    /** L0 tree silhouette — identical geometry to the legacy mesher. */
    private void emitTree(QuadWriter w, float wx, float wz, int terrainH, TreeSample tree) {
        float trunkBase = terrainH;
        float trunkTop  = terrainH + tree.trunkHeight();
        float cx = wx + 0.5f;
        float cz = wz + 0.5f;

        float[] trunkUV = atlas.getBlockFaceUVs(tree.kind().trunkBlock(), BlockType.Face.SIDE_NORTH);
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

        float[] leafTopUV  = atlas.getBlockFaceUVs(tree.kind().leavesBlock(), BlockType.Face.TOP);
        float[] leafSideUV = atlas.getBlockFaceUVs(tree.kind().leavesBlock(), BlockType.Face.SIDE_NORTH);
        float canopyMidY = trunkTop + 1f;
        float canopyTop  = canopyMidY + CANOPY_HALF_HEIGHT;
        float canopyBot  = canopyMidY - CANOPY_HALF_HEIGHT;
        float xMin = cx - CANOPY_RADIUS, xMax = cx + CANOPY_RADIUS;
        float zMin = cz - CANOPY_RADIUS, zMax = cz + CANOPY_RADIUS;

        w.axisAlignedQuad(xMin, canopyTop, zMin, xMax, canopyTop, zMin,
                          xMax, canopyTop, zMax, xMin, canopyTop, zMax,
                          0, 1, 0, leafTopUV, 1f);
        w.axisAlignedQuad(xMax, canopyTop, zMin, xMax, canopyTop, zMax,
                          xMax, canopyBot, zMax, xMax, canopyBot, zMin,
                          1, 0, 0, leafSideUV, 1f);
        w.axisAlignedQuad(xMin, canopyTop, zMax, xMin, canopyTop, zMin,
                          xMin, canopyBot, zMin, xMin, canopyBot, zMax,
                          -1, 0, 0, leafSideUV, 1f);
        w.axisAlignedQuad(xMax, canopyTop, zMax, xMin, canopyTop, zMax,
                          xMin, canopyBot, zMax, xMax, canopyBot, zMax,
                          0, 0, 1, leafSideUV, 1f);
        w.axisAlignedQuad(xMin, canopyTop, zMin, xMax, canopyTop, zMin,
                          xMax, canopyBot, zMin, xMin, canopyBot, zMin,
                          0, 0, -1, leafSideUV, 1f);
    }

    /** Packs interleaved vertex/index writes into target arrays. */
    private static final class QuadWriter {
        final float[] pos, tex, nrm, water, alpha, translucent;
        final int[] idx;
        int vertCount = 0;
        int idxCount  = 0;

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

        /** Axis-aligned top quad covering cellSize × cellSize blocks starting at (wx, wz). */
        void topQuad(float wx, float y, float wz, int cellSize, float[] uv) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            int v0 = pushVert(wx, y, wz,     uv[0], uv[1], 0, 1, 0);
                    pushVert(x1, y, wz,     uv[2], uv[1], 0, 1, 0);
                    pushVert(x1, y, z1,     uv[2], uv[3], 0, 1, 0);
                    pushVert(wx, y, z1,     uv[0], uv[3], 0, 1, 0);
            pushQuadIndices(v0);
        }

        /** Skirt face on one cell edge descending from topY to bottomY. */
        void skirtQuad(float wx, float wz, int cellSize,
                       int dx, int dz, int topY, int bottomY, float[] uv) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            float fTop = topY, fBot = bottomY;
            if (dx > 0) {
                int v0 = pushVert(x1, fTop, wz, uv[0], uv[1], 1, 0, 0);
                        pushVert(x1, fTop, z1, uv[2], uv[1], 1, 0, 0);
                        pushVert(x1, fBot, z1, uv[2], uv[3], 1, 0, 0);
                        pushVert(x1, fBot, wz, uv[0], uv[3], 1, 0, 0);
                pushQuadIndices(v0);
            } else if (dx < 0) {
                int v0 = pushVert(wx, fTop, z1, uv[0], uv[1], -1, 0, 0);
                        pushVert(wx, fTop, wz, uv[2], uv[1], -1, 0, 0);
                        pushVert(wx, fBot, wz, uv[2], uv[3], -1, 0, 0);
                        pushVert(wx, fBot, z1, uv[0], uv[3], -1, 0, 0);
                pushQuadIndices(v0);
            } else if (dz > 0) {
                int v0 = pushVert(x1, fTop, z1, uv[0], uv[1], 0, 0, 1);
                        pushVert(wx, fTop, z1, uv[2], uv[1], 0, 0, 1);
                        pushVert(wx, fBot, z1, uv[2], uv[3], 0, 0, 1);
                        pushVert(x1, fBot, z1, uv[0], uv[3], 0, 0, 1);
                pushQuadIndices(v0);
            } else {
                int v0 = pushVert(wx, fTop, wz, uv[0], uv[1], 0, 0, -1);
                        pushVert(x1, fTop, wz, uv[2], uv[1], 0, 0, -1);
                        pushVert(x1, fBot, wz, uv[2], uv[3], 0, 0, -1);
                        pushVert(wx, fBot, wz, uv[0], uv[3], 0, 0, -1);
                pushQuadIndices(v0);
            }
        }

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
            int p3 = vi * 3, p2 = vi * 2;
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
