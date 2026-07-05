package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
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
 * <p>Textures come from the {@link BlockTextureArray}: each quad spans one
 * layer (unit-square UVs) with a per-vertex layer index.
 *
 * <p>Land top quads carry per-vertex normals derived from the height-field
 * gradient (central difference over the margin-extended heights), so distant
 * hills receive real sun diffuse instead of reading as flat-lit plateaus. The
 * corner normals are shared between adjacent cells, giving smooth shading
 * across cells and — because the margin samples the neighbouring chunk's
 * terrain — approximately across chunk borders too.
 *
 * <p>Submerged cells paint a sea-level sheet flagged as LOD water
 * (waterFlag=1 → the world shader's procedural distant-water branch) at
 * {@code SEA_LEVEL - 0.125}, matching the native water surface height so the
 * boundary between near water meshes and the LOD sheet has no lip.
 */
public final class FastLodMesher {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    /**
     * Native water tops sit at blockBase + 0.875 (see shaders/water/water.vert);
     * worldgen fills water below {@code SEA_LEVEL}, so the visible surface is
     * SEA_LEVEL - 1 + 0.875. The LOD sea sheet uses the same height.
     */
    private static final float SEA_SURFACE_Y = SEA_LEVEL - 0.125f;

    private static final int TOP_QUAD            = 1;
    private static final int MAX_SKIRTS_PER_CELL = 4;
    private static final int TREE_QUADS_PER_CELL = 9;   // 4 trunk + 5 canopy

    private static final float CANOPY_RADIUS      = 1.5f;
    private static final float CANOPY_HALF_HEIGHT = 1.5f;

    private final BlockTextureArray textureArray;

    public FastLodMesher(BlockTextureArray textureArray) {
        this.textureArray = textureArray;
    }

    /**
     * Mesh plus its exact vertex Y bounds, tracked while quads are written.
     * The bounds feed per-node frustum culling — derived from the pushed
     * vertices themselves (skirts, sea sheets and tree canopies included) so
     * they can never drift out of sync with the geometry.
     */
    public record Result(MmsMeshData mesh, float minY, float maxY) {
        public static Result empty() {
            return new Result(MmsMeshData.empty(), 0f, 0f);
        }
    }

    public Result build(FastLodChunkData data) {
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
        float[] layerIndices    = new float[maxVerts];
        int[]   indices         = new int[maxIndices];

        QuadWriter w = new QuadWriter(positions, texCoords, normals,
                waterFlags, alphaFlags, translucentFlags, layerIndices, indices);

        // Height-gradient normals shared at cell corners ((cellsPerAxis+1)² grid).
        float[] cornerNormals = computeCornerNormals(data, cellsPerAxis, cellSize);

        int baseX = data.chunkX() * WorldConfiguration.CHUNK_SIZE;
        int baseZ = data.chunkZ() * WorldConfiguration.CHUNK_SIZE;

        for (int ix = 0; ix < cellsPerAxis; ix++) {
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                BlockType surface = data.surfaceAt(ix, iz);
                int terrainH = data.heightAt(ix, iz);
                boolean isWater = (surface == BlockType.WATER);
                // Integer top used for skirt comparisons — identical decisions
                // to the pre-water-lip mesher. The emitted geometry uses the
                // float sea-surface height instead.
                int topY = isWater ? SEA_LEVEL : terrainH;
                float emitY = isWater ? SEA_SURFACE_Y : terrainH;
                float waterFlag = isWater ? 1f : 0f;

                float wx = baseX + ix * cellSize;
                float wz = baseZ + iz * cellSize;

                int topLayer = textureArray.getBlockFaceLayer(surface, BlockType.Face.TOP.getIndex());
                if (isWater) {
                    w.topQuadFlat(wx, emitY, wz, cellSize, topLayer, waterFlag);
                } else {
                    w.topQuadSmooth(wx, emitY, wz, cellSize, topLayer,
                            cornerNormals, ix, iz, cellsPerAxis);
                }

                emitSkirtIfLower(w, data, ix, iz, +1, 0, surface, topY, emitY, waterFlag, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, -1, 0, surface, topY, emitY, waterFlag, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, 0, +1, surface, topY, emitY, waterFlag, wx, wz, cellSize);
                emitSkirtIfLower(w, data, ix, iz, 0, -1, surface, topY, emitY, waterFlag, wx, wz, cellSize);

                if (level.emitsTrees()) {
                    TreeSample tree = data.treeAt(ix, iz);
                    if (tree != null && !isWater) {
                        emitTree(w, wx, wz, terrainH, tree);
                    }
                }
            }
        }

        if (w.idxCount == 0) {
            return Result.empty();
        }

        float[] light = new float[w.vertCount];
        Arrays.fill(light, 1.0f);

        MmsMeshData mesh = new MmsMeshData(
                Arrays.copyOf(positions, w.vertCount * 3),
                Arrays.copyOf(texCoords, w.vertCount * 2),
                Arrays.copyOf(normals, w.vertCount * 3),
                Arrays.copyOf(waterFlags, w.vertCount),
                Arrays.copyOf(alphaFlags, w.vertCount),
                Arrays.copyOf(translucentFlags, w.vertCount),
                light,
                Arrays.copyOf(layerIndices, w.vertCount),
                Arrays.copyOf(indices, w.idxCount),
                w.idxCount
        );
        return new Result(mesh, w.minY, w.maxY);
    }

    /**
     * Smoothed per-corner normals from the height-field gradient. Corner
     * (cx, cz) sits between cells {cx-1, cx} × {cz-1, cz}; the central
     * difference over those four heights lies entirely within the sampler's
     * one-cell margin, so no neighbour-node reads are needed.
     */
    private static float[] computeCornerNormals(FastLodChunkData data, int cellsPerAxis, int cellSize) {
        int cornersPerAxis = cellsPerAxis + 1;
        float[] out = new float[cornersPerAxis * cornersPerAxis * 3];
        float invTwoCell = 1f / (2f * cellSize);
        for (int cx = 0; cx <= cellsPerAxis; cx++) {
            for (int cz = 0; cz <= cellsPerAxis; cz++) {
                float hPxNz = data.heightAt(cx, cz - 1);
                float hPxPz = data.heightAt(cx, cz);
                float hNxNz = data.heightAt(cx - 1, cz - 1);
                float hNxPz = data.heightAt(cx - 1, cz);
                float gx = (hPxNz + hPxPz - hNxNz - hNxPz) * invTwoCell;
                float gz = (hNxPz + hPxPz - hNxNz - hPxNz) * invTwoCell;
                float nx = -gx, ny = 1f, nz = -gz;
                float invLen = (float) (1.0 / Math.sqrt(nx * nx + 1.0 + nz * nz));
                int o = (cx * cornersPerAxis + cz) * 3;
                out[o]     = nx * invLen;
                out[o + 1] = ny * invLen;
                out[o + 2] = nz * invLen;
            }
        }
        return out;
    }

    private void emitSkirtIfLower(QuadWriter w, FastLodChunkData data,
                                  int ix, int iz, int dx, int dz,
                                  BlockType surface, int topY, float emitTopY, float waterFlag,
                                  float wx, float wz, int cellSize) {
        int nH = data.heightAt(ix + dx, iz + dz);
        int nTopY = (nH < SEA_LEVEL) ? SEA_LEVEL : nH;
        if (nTopY >= topY) {
            return;
        }
        // Neighbour columns below sea level are covered by a sea sheet at the
        // float surface height — end the skirt there so no sliver gap opens.
        float emitBottomY = (nH < SEA_LEVEL) ? SEA_SURFACE_Y : nH;
        int sideLayer = textureArray.getBlockFaceLayer(surface, faceForDirection(dx, dz).getIndex());
        w.skirtQuad(wx, wz, cellSize, dx, dz, emitTopY, emitBottomY, sideLayer, waterFlag);
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

        int trunkLayer = textureArray.getBlockFaceLayer(tree.kind().trunkBlock(),
                BlockType.Face.SIDE_NORTH.getIndex());
        w.axisAlignedQuad(cx + 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkTop, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz - 0.5f,
                          1, 0, 0, trunkLayer, 0f);
        w.axisAlignedQuad(cx - 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkTop, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz + 0.5f,
                          -1, 0, 0, trunkLayer, 0f);
        w.axisAlignedQuad(cx + 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkTop, cz + 0.5f,
                          cx - 0.5f, trunkBase, cz + 0.5f,
                          cx + 0.5f, trunkBase, cz + 0.5f,
                          0, 0, 1, trunkLayer, 0f);
        w.axisAlignedQuad(cx - 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkTop, cz - 0.5f,
                          cx + 0.5f, trunkBase, cz - 0.5f,
                          cx - 0.5f, trunkBase, cz - 0.5f,
                          0, 0, -1, trunkLayer, 0f);

        int leafTopLayer  = textureArray.getBlockFaceLayer(tree.kind().leavesBlock(),
                BlockType.Face.TOP.getIndex());
        int leafSideLayer = textureArray.getBlockFaceLayer(tree.kind().leavesBlock(),
                BlockType.Face.SIDE_NORTH.getIndex());
        float canopyMidY = trunkTop + 1f;
        float canopyTop  = canopyMidY + CANOPY_HALF_HEIGHT;
        float canopyBot  = canopyMidY - CANOPY_HALF_HEIGHT;
        float xMin = cx - CANOPY_RADIUS, xMax = cx + CANOPY_RADIUS;
        float zMin = cz - CANOPY_RADIUS, zMax = cz + CANOPY_RADIUS;

        w.axisAlignedQuad(xMin, canopyTop, zMin, xMax, canopyTop, zMin,
                          xMax, canopyTop, zMax, xMin, canopyTop, zMax,
                          0, 1, 0, leafTopLayer, 1f);
        w.axisAlignedQuad(xMax, canopyTop, zMin, xMax, canopyTop, zMax,
                          xMax, canopyBot, zMax, xMax, canopyBot, zMin,
                          1, 0, 0, leafSideLayer, 1f);
        w.axisAlignedQuad(xMin, canopyTop, zMax, xMin, canopyTop, zMin,
                          xMin, canopyBot, zMin, xMin, canopyBot, zMax,
                          -1, 0, 0, leafSideLayer, 1f);
        w.axisAlignedQuad(xMax, canopyTop, zMax, xMin, canopyTop, zMax,
                          xMin, canopyBot, zMax, xMax, canopyBot, zMax,
                          0, 0, 1, leafSideLayer, 1f);
        w.axisAlignedQuad(xMin, canopyTop, zMin, xMax, canopyTop, zMin,
                          xMax, canopyBot, zMin, xMin, canopyBot, zMin,
                          0, 0, -1, leafSideLayer, 1f);
    }

    /** Packs interleaved vertex/index writes into target arrays. */
    private static final class QuadWriter {
        final float[] pos, tex, nrm, water, alpha, translucent, layers;
        final int[] idx;
        int vertCount = 0;
        int idxCount  = 0;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        QuadWriter(float[] pos, float[] tex, float[] nrm,
                   float[] water, float[] alpha, float[] translucent, float[] layers, int[] idx) {
            this.pos = pos;
            this.tex = tex;
            this.nrm = nrm;
            this.water = water;
            this.alpha = alpha;
            this.translucent = translucent;
            this.layers = layers;
            this.idx = idx;
        }

        /** Flat-up top quad (sea sheets — the water branch shades procedurally). */
        void topQuadFlat(float wx, float y, float wz, int cellSize, int layer, float waterFlag) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            int v0 = pushVert(wx, y, wz, 0f, 0f, 0, 1, 0, waterFlag, 0f, layer);
                    pushVert(x1, y, wz, 1f, 0f, 0, 1, 0, waterFlag, 0f, layer);
                    pushVert(x1, y, z1, 1f, 1f, 0, 1, 0, waterFlag, 0f, layer);
                    pushVert(wx, y, z1, 0f, 1f, 0, 1, 0, waterFlag, 0f, layer);
            pushQuadIndices(v0);
        }

        /**
         * Land top quad with per-vertex gradient normals looked up from the
         * shared corner grid. Vertex order matches {@code topQuadFlat}:
         * (wx,wz) → (x1,wz) → (x1,z1) → (wx,z1), i.e. corners
         * (ix,iz), (ix+1,iz), (ix+1,iz+1), (ix,iz+1).
         */
        void topQuadSmooth(float wx, float y, float wz, int cellSize, int layer,
                           float[] cornerNormals, int ix, int iz, int cellsPerAxis) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            int cpa = cellsPerAxis + 1;
            int n00 = (ix       * cpa + iz)     * 3;
            int n10 = ((ix + 1) * cpa + iz)     * 3;
            int n11 = ((ix + 1) * cpa + iz + 1) * 3;
            int n01 = (ix       * cpa + iz + 1) * 3;
            int v0 = pushVert(wx, y, wz, 0f, 0f,
                    cornerNormals[n00], cornerNormals[n00 + 1], cornerNormals[n00 + 2], 0f, 0f, layer);
                    pushVert(x1, y, wz, 1f, 0f,
                    cornerNormals[n10], cornerNormals[n10 + 1], cornerNormals[n10 + 2], 0f, 0f, layer);
                    pushVert(x1, y, z1, 1f, 1f,
                    cornerNormals[n11], cornerNormals[n11 + 1], cornerNormals[n11 + 2], 0f, 0f, layer);
                    pushVert(wx, y, z1, 0f, 1f,
                    cornerNormals[n01], cornerNormals[n01 + 1], cornerNormals[n01 + 2], 0f, 0f, layer);
            pushQuadIndices(v0);
        }

        /** Skirt face on one cell edge descending from fTop to fBot. */
        void skirtQuad(float wx, float wz, int cellSize,
                       int dx, int dz, float fTop, float fBot, int layer, float waterFlag) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            if (dx > 0) {
                int v0 = pushVert(x1, fTop, wz, 0f, 0f, 1, 0, 0, waterFlag, 0f, layer);
                        pushVert(x1, fTop, z1, 1f, 0f, 1, 0, 0, waterFlag, 0f, layer);
                        pushVert(x1, fBot, z1, 1f, 1f, 1, 0, 0, waterFlag, 0f, layer);
                        pushVert(x1, fBot, wz, 0f, 1f, 1, 0, 0, waterFlag, 0f, layer);
                pushQuadIndices(v0);
            } else if (dx < 0) {
                int v0 = pushVert(wx, fTop, z1, 0f, 0f, -1, 0, 0, waterFlag, 0f, layer);
                        pushVert(wx, fTop, wz, 1f, 0f, -1, 0, 0, waterFlag, 0f, layer);
                        pushVert(wx, fBot, wz, 1f, 1f, -1, 0, 0, waterFlag, 0f, layer);
                        pushVert(wx, fBot, z1, 0f, 1f, -1, 0, 0, waterFlag, 0f, layer);
                pushQuadIndices(v0);
            } else if (dz > 0) {
                int v0 = pushVert(x1, fTop, z1, 0f, 0f, 0, 0, 1, waterFlag, 0f, layer);
                        pushVert(wx, fTop, z1, 1f, 0f, 0, 0, 1, waterFlag, 0f, layer);
                        pushVert(wx, fBot, z1, 1f, 1f, 0, 0, 1, waterFlag, 0f, layer);
                        pushVert(x1, fBot, z1, 0f, 1f, 0, 0, 1, waterFlag, 0f, layer);
                pushQuadIndices(v0);
            } else {
                int v0 = pushVert(wx, fTop, wz, 0f, 0f, 0, 0, -1, waterFlag, 0f, layer);
                        pushVert(x1, fTop, wz, 1f, 0f, 0, 0, -1, waterFlag, 0f, layer);
                        pushVert(x1, fBot, wz, 1f, 1f, 0, 0, -1, waterFlag, 0f, layer);
                        pushVert(wx, fBot, wz, 0f, 1f, 0, 0, -1, waterFlag, 0f, layer);
                pushQuadIndices(v0);
            }
        }

        void axisAlignedQuad(float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz, int layer, float alphaFlag) {
            int v0 = pushVert(x0, y0, z0, 0f, 0f, nx, ny, nz, 0f, alphaFlag, layer);
                    pushVert(x1, y1, z1, 1f, 0f, nx, ny, nz, 0f, alphaFlag, layer);
                    pushVert(x2, y2, z2, 1f, 1f, nx, ny, nz, 0f, alphaFlag, layer);
                    pushVert(x3, y3, z3, 0f, 1f, nx, ny, nz, 0f, alphaFlag, layer);
            pushQuadIndices(v0);
        }

        private int pushVert(float x, float y, float z,
                             float u, float v,
                             float nx, float ny, float nz,
                             float waterFlag, float alphaFlag, int layer) {
            int vi = vertCount;
            int p3 = vi * 3, p2 = vi * 2;
            pos[p3] = x; pos[p3 + 1] = y; pos[p3 + 2] = z;
            tex[p2] = u; tex[p2 + 1] = v;
            nrm[p3] = nx; nrm[p3 + 1] = ny; nrm[p3 + 2] = nz;
            water[vi] = waterFlag;
            alpha[vi] = alphaFlag;
            translucent[vi] = 0f;
            layers[vi] = layer;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
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
