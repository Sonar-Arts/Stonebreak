package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadMeshBuilder;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsCore.MmsWaterQuadCodec;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;

/**
 * Builds the coarse meshes for one {@link FastLodChunkData}: an opaque
 * terrain mesh and, when any cell is submerged, a separate translucent water
 * mesh drawn by the dedicated {@code WaterRenderer} (same shader as native
 * water, so the near/far water handover has no seam by construction).
 *
 * <p><b>Terrain mesh.</b> Every cell — land or seabed — emits one top quad at
 * its REAL terrain height plus up to four skirts facing lower neighbour
 * cells; submerged cells use their sampled seabed block (serializer v2), so
 * the ocean floor renders under the water sheet exactly like native chunks.
 * At the finest level ({@link FastLodLevel#L0}) dry cells additionally emit a
 * low-poly tree silhouette when they carry a tree sample. Textures come from
 * the {@link BlockTextureArray}: each quad spans one layer (unit-square UVs)
 * with a per-vertex layer index.
 *
 * <p>Land top quads carry per-vertex normals derived from the height-field
 * gradient (central difference over the margin-extended heights), so distant
 * hills receive real sun diffuse instead of reading as flat-lit plateaus. The
 * corner normals are shared between adjacent cells, giving smooth shading
 * across cells and — because the margin samples the neighbouring chunk's
 * terrain — approximately across chunk borders too.
 *
 * <p>Cells on the node's border additionally emit dark "foundation" walls
 * descending to y=0 on the border-facing edge, below the lit skirt (or flush
 * with the cell top when no skirt is emitted). Neighbouring nodes at other
 * LOD levels sample the terrain at a different stride, so their border
 * geometry never matches exactly — T-junction pinholes and small height
 * mismatches otherwise open sky-colored cracks along every level boundary.
 * The walls also seal the node from the side below ground: sightlines that
 * leave the native chunk disk through a cave or tunnel hit a dark wall
 * instead of showing sky through terrain that only exists as a surface sheet.
 *
 * <p><b>Water mesh.</b> One flat sheet quad per submerged cell at
 * {@code SEA_LEVEL - 0.125} — the same plane native water surfaces occupy.
 * Its vertex flags follow the water-mesh semantics documented in
 * {@code shaders/water/water.vert}: flags.x = surface-height fraction
 * (0.875), flags.y = falling (0), flags.w = light (1). Because the sheet is
 * rendered by the same water shader over a real LOD seabed, fresnel
 * transparency, waves, specular and fog are all continuous with native water.
 */
public final class FastLodMesher {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    /**
     * Native water tops sit at blockBase + 0.875 (see shaders/water/water.vert);
     * worldgen fills water below {@code SEA_LEVEL}, so the visible surface is
     * SEA_LEVEL - 1 + 0.875. The LOD sea sheet uses the same height.
     */
    private static final float SEA_SURFACE_Y = SEA_LEVEL - 0.125f;
    /** Surface-height fraction baked into water sheet flags (water.vert). */
    private static final float WATER_SURFACE_FRACTION = 0.875f;

    private static final int TOP_QUAD            = 1;
    private static final int MAX_SKIRTS_PER_CELL = 4;
    private static final int TREE_QUADS_PER_CELL = 9;   // 4 trunk + 5 canopy

    /**
     * Per-vertex light for foundation walls (flags.w). They are only ever
     * visible through border cracks and underground sightlines, so they shade
     * to the world shader's light floor — dark crevice/cave rock, never a
     * bright sunlit face where sky used to leak through.
     */
    private static final float FOUNDATION_LIGHT = 0.0f;

    private static final float CANOPY_RADIUS      = 1.5f;
    private static final float CANOPY_HALF_HEIGHT = 1.5f;

    private final BlockTextureArray textureArray;

    public FastLodMesher(BlockTextureArray textureArray) {
        this.textureArray = textureArray;
    }

    /**
     * Terrain mesh, optional water-sheet mesh ({@code null} when the node has
     * no submerged cells), and the exact combined vertex Y bounds — tracked
     * while quads are written (skirts, foundations, sheets and tree canopies
     * included) so per-node frustum culling can never drift out of sync with
     * the geometry.
     */
    public record Result(MmsMeshData mesh, MmsMeshData waterMesh, float minY, float maxY) {
        public static Result empty() {
            return new Result(MmsMeshData.empty(), null, 0f, 0f);
        }
    }

    public Result build(FastLodChunkData data) {
        FastLodLevel level = data.level();
        int cellSize     = level.cellSize();
        int cellsPerAxis = level.cellsPerAxis();

        int maxQuadsPerCell = TOP_QUAD + MAX_SKIRTS_PER_CELL
                + (level.emitsTrees() ? TREE_QUADS_PER_CELL : 0);
        // Foundations are bounded by the node's border-edge count, NOT per
        // cell: at L4 the single cell owns all four border edges (a per-cell
        // constant would under-allocate there and overflow the arrays).
        int maxFoundations = 4 * cellsPerAxis;
        int maxQuads   = cellsPerAxis * cellsPerAxis * maxQuadsPerCell + maxFoundations;
        int maxVerts   = maxQuads * 4;
        int maxIndices = maxQuads * 6;

        float[] positions       = new float[maxVerts * 3];
        float[] texCoords       = new float[maxVerts * 2];
        float[] normals         = new float[maxVerts * 3];
        float[] waterFlags      = new float[maxVerts];
        float[] alphaFlags      = new float[maxVerts];
        float[] translucentFlags = new float[maxVerts];
        float[] lightValues     = new float[maxVerts];
        float[] layerIndices    = new float[maxVerts];
        int[]   indices         = new int[maxIndices];

        QuadWriter w = new QuadWriter(positions, texCoords, normals,
                waterFlags, alphaFlags, translucentFlags, lightValues, layerIndices, indices);

        // Water sheet: at most one quad per cell.
        int maxWaterQuads = cellsPerAxis * cellsPerAxis;
        float[] wPositions       = new float[maxWaterQuads * 4 * 3];
        float[] wTexCoords       = new float[maxWaterQuads * 4 * 2];
        float[] wNormals         = new float[maxWaterQuads * 4 * 3];
        float[] wSurfaceFlags    = new float[maxWaterQuads * 4];
        float[] wFallingFlags    = new float[maxWaterQuads * 4];
        float[] wSourceFlags     = new float[maxWaterQuads * 4];
        float[] wLight           = new float[maxWaterQuads * 4];
        float[] wLayers          = new float[maxWaterQuads * 4];
        int[]   wIndices         = new int[maxWaterQuads * 6];
        QuadWriter ww = new QuadWriter(wPositions, wTexCoords, wNormals,
                wSurfaceFlags, wFallingFlags, wSourceFlags, wLight, wLayers, wIndices);

        // Pulled LOD quads: 16 bytes per quad relative to the LOD region origin
        // (MmsLodQuadCodec) instead of 4 × 20-byte vertices + indices. The
        // per-vertex writer stays for legacy/compact formats and for water.
        LodQuadWriter pulled = MmsVertexFormat.active().pulled()
            ? new LodQuadWriter(maxQuads,
                com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.regionOrigin(data.chunkX()),
                com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.regionOrigin(data.chunkZ()))
            : null;
        LodWaterWriter pulledWater = null;
        if (pulled != null) {
            w = pulled;
            pulledWater = new LodWaterWriter(maxWaterQuads,
                com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.regionOrigin(data.chunkX()),
                com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.regionOrigin(data.chunkZ()));
            ww = pulledWater;
        }

        // Height-gradient normals shared at cell corners ((cellsPerAxis+1)² grid).
        float[] cornerNormals = computeCornerNormals(data, cellsPerAxis, cellSize);

        int baseX = data.chunkX() * WorldConfiguration.CHUNK_SIZE;
        int baseZ = data.chunkZ() * WorldConfiguration.CHUNK_SIZE;

        for (int ix = 0; ix < cellsPerAxis; ix++) {
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                BlockType surface = data.surfaceAt(ix, iz);
                int terrainH = data.heightAt(ix, iz);
                boolean submerged = terrainH < SEA_LEVEL;

                float wx = baseX + ix * cellSize;
                float wz = baseZ + iz * cellSize;

                // Terrain top at the REAL height — the seabed under water.
                int topLayer = textureArray.getBlockFaceLayer(surface, BlockType.Face.TOP.getIndex());
                w.topQuadSmooth(wx, terrainH, wz, cellSize, topLayer,
                        cornerNormals, ix, iz, cellsPerAxis);

                int last = cellsPerAxis - 1;
                emitSideQuads(w, data, ix, iz, +1, 0, surface, terrainH, wx, wz, cellSize, ix == last);
                emitSideQuads(w, data, ix, iz, -1, 0, surface, terrainH, wx, wz, cellSize, ix == 0);
                emitSideQuads(w, data, ix, iz, 0, +1, surface, terrainH, wx, wz, cellSize, iz == last);
                emitSideQuads(w, data, ix, iz, 0, -1, surface, terrainH, wx, wz, cellSize, iz == 0);

                if (submerged) {
                    // Water sheet quad. Flag semantics per water.vert:
                    // x = surface-height fraction, y = falling, w = light.
                    ww.topQuadFlat(wx, SEA_SURFACE_Y, wz, cellSize, 0, WATER_SURFACE_FRACTION);
                }

                if (level.emitsTrees() && !submerged) {
                    TreeSample tree = data.treeAt(ix, iz);
                    if (tree != null) {
                        emitTree(w, wx, wz, terrainH, tree);
                    }
                }
            }
        }

        if (w.idxCount == 0 && ww.idxCount == 0) {
            return Result.empty();
        }
        if (pulled != null) {
            MmsMeshData pulledMesh = pulled.build();
            MmsMeshData waterOut = null;
            float pMinY = pulled.minY, pMaxY = pulled.maxY;
            if (pulledWater.idxCount > 0) {
                waterOut = pulledWater.build();
                pMinY = Math.min(pMinY, pulledWater.minY);
                pMaxY = Math.max(pMaxY, pulledWater.maxY);
            }
            return new Result(pulledMesh, waterOut, pMinY, pMaxY);
        }

        MmsMeshData mesh = new MmsMeshData(
                Arrays.copyOf(positions, w.vertCount * 3),
                Arrays.copyOf(texCoords, w.vertCount * 2),
                Arrays.copyOf(normals, w.vertCount * 3),
                Arrays.copyOf(waterFlags, w.vertCount),
                Arrays.copyOf(alphaFlags, w.vertCount),
                Arrays.copyOf(translucentFlags, w.vertCount),
                Arrays.copyOf(lightValues, w.vertCount),
                Arrays.copyOf(layerIndices, w.vertCount),
                Arrays.copyOf(indices, w.idxCount),
                w.idxCount
        );
        MmsMeshData waterMesh = null;
        float minY = w.minY, maxY = w.maxY;
        if (ww.idxCount > 0) {
            waterMesh = new MmsMeshData(
                    Arrays.copyOf(wPositions, ww.vertCount * 3),
                    Arrays.copyOf(wTexCoords, ww.vertCount * 2),
                    Arrays.copyOf(wNormals, ww.vertCount * 3),
                    Arrays.copyOf(wSurfaceFlags, ww.vertCount),
                    Arrays.copyOf(wFallingFlags, ww.vertCount),
                    Arrays.copyOf(wSourceFlags, ww.vertCount),
                    Arrays.copyOf(wLight, ww.vertCount),
                    Arrays.copyOf(wLayers, ww.vertCount),
                    Arrays.copyOf(wIndices, ww.idxCount),
                    ww.idxCount
            );
            minY = Math.min(minY, ww.minY);
            maxY = Math.max(maxY, ww.maxY);
        }
        return new Result(mesh, waterMesh, minY, maxY);
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

    /**
     * Side geometry for one cell edge: the lit skirt toward a lower neighbour
     * (comparing REAL terrain heights — the seabed is regular terrain now),
     * plus — on node-border edges — a dark foundation wall descending to y=0.
     *
     * <p>Foundation walls back any remaining border crack (neighbouring nodes
     * at other LOD levels sample at a different stride, so exact matches are
     * impossible) and seal underground sightlines through the node.
     */
    private void emitSideQuads(QuadWriter w, FastLodChunkData data,
                               int ix, int iz, int dx, int dz,
                               BlockType surface, int terrainH,
                               float wx, float wz, int cellSize, boolean nodeBorder) {
        int nH = data.heightAt(ix + dx, iz + dz);
        boolean hasSkirt = nH < terrainH;
        if (!hasSkirt && !nodeBorder) {
            return;
        }
        int sideLayer = textureArray.getBlockFaceLayer(surface, faceForDirection(dx, dz).getIndex());
        if (hasSkirt) {
            w.skirtQuad(wx, wz, cellSize, dx, dz, terrainH, nH, sideLayer, 1f);
        }
        if (nodeBorder) {
            // Continue below the skirt bottom (or flush with the cell top when
            // no skirt exists) down to y=0.
            float foundationTopY = hasSkirt ? nH : terrainH;
            if (foundationTopY > 0f) {
                w.skirtQuad(wx, wz, cellSize, dx, dz, foundationTopY, 0f, sideLayer, FOUNDATION_LIGHT);
            }
        }
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

    /**
     * Packs interleaved vertex/index writes into target arrays. The four
     * scalar slots map to aFlags.xyzw at upload; the terrain writer uses them
     * as (water, alphaTest, translucent, light) and the water-sheet writer as
     * (surfaceHeight, falling, source, light) per the water.vert contract.
     */
    /**
     * Pulled-quad twin of {@link QuadWriter}: the same three terrain emitters,
     * but each rectangle becomes one {@link MmsLodQuadCodec} record. Counts
     * {@code idxCount} so the caller's emptiness check keeps working.
     */
    private static final class LodQuadWriter extends QuadWriter {
        private final MmsQuadMeshBuilder quads;
        private final float originX, originZ;

        LodQuadWriter(int estimatedQuads, float originX, float originZ) {
            super(null, null, null, null, null, null, null, null, null);
            this.quads = new MmsQuadMeshBuilder(estimatedQuads, MmsVertexFormat.LODQUAD16)
                .setOrigin(originX, 0f, originZ);
            this.originX = originX;
            this.originZ = originZ;
        }

        MmsMeshData build() {
            return quads.build();
        }

        private void record(int face, float x, float yMin, float z, float w, float h,
                            int layer, boolean smooth, boolean lit, boolean alpha,
                            int nPair01, int nPair23) {
            int rx = Math.round(x - originX);
            int rz = Math.round(z - originZ);
            int yHalf = Math.round(yMin * 2f);
            int wHalf = Math.max(1, Math.round(w * 2f));
            int hHalf = Math.max(1, Math.round(h * 2f));
            if (yHalf < 0 || yHalf > 511 || hHalf > 1023 || wHalf > 63) {
                return; // outside the representable band (never for WORLD_HEIGHT 256)
            }
            if (!quads.addWords(
                    MmsLodQuadCodec.word0(rx, rz, yHalf, face, smooth, lit),
                    MmsLodQuadCodec.word1(wHalf, hHalf, layer, alpha),
                    nPair01, nPair23)) {
                return; // per-draw quad cap (never reached by a single LOD node)
            }
            idxCount += 6;
            vertCount += 4;
            if (yMin < minY) minY = yMin;
            float yMax = yMin + (face <= 1 ? 0f : h);
            if (yMax > maxY) maxY = yMax;
        }

        @Override
        void topQuadSmooth(float wx, float y, float wz, int cellSize, int layer,
                           float[] cornerNormals, int ix, int iz, int cellsPerAxis) {
            int cpa = cellsPerAxis + 1;
            int n00 = (ix       * cpa + iz)     * 3;
            int n10 = ((ix + 1) * cpa + iz)     * 3;
            int n11 = ((ix + 1) * cpa + iz + 1) * 3;
            int n01 = (ix       * cpa + iz + 1) * 3;
            // Top-face corner order (FACE_VERTEX_OFFSETS): (0,·,1),(1,·,1),(1,·,0),(0,·,0)
            int pair01 = MmsLodQuadCodec.normalPair(
                cornerNormals[n01], cornerNormals[n01 + 1], cornerNormals[n01 + 2],
                cornerNormals[n11], cornerNormals[n11 + 1], cornerNormals[n11 + 2]);
            int pair23 = MmsLodQuadCodec.normalPair(
                cornerNormals[n10], cornerNormals[n10 + 1], cornerNormals[n10 + 2],
                cornerNormals[n00], cornerNormals[n00 + 1], cornerNormals[n00 + 2]);
            record(0, wx, y, wz, cellSize, cellSize, layer, true, true, false, pair01, pair23);
        }

        @Override
        void skirtQuad(float wx, float wz, int cellSize,
                       int dx, int dz, float fTop, float fBot, int layer, float lightVal) {
            int face = dx > 0 ? 4 : dx < 0 ? 5 : dz > 0 ? 3 : 2;
            float x = dx > 0 ? wx + cellSize : wx;
            float z = dz > 0 ? wz + cellSize : wz;
            record(face, x, fBot, z, cellSize, fTop - fBot, layer, false, lightVal > 0.5f, false, 0, 0);
        }

        @Override
        void axisAlignedQuad(float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz, int layer, float alphaFlag) {
            float minX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
            float maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
            float lo = Math.min(Math.min(y0, y1), Math.min(y2, y3));
            float hi = Math.max(Math.max(y0, y1), Math.max(y2, y3));
            float minZ = Math.min(Math.min(z0, z1), Math.min(z2, z3));
            float maxZ = Math.max(Math.max(z0, z1), Math.max(z2, z3));
            int face = ny > 0.5f ? 0 : ny < -0.5f ? 1 : nz < -0.5f ? 2 : nz > 0.5f ? 3 : nx > 0.5f ? 4 : 5;
            // u axis: x for ±Y/±Z faces, z for ±X; v axis: z for ±Y, else y.
            float w = face >= 4 ? maxZ - minZ : maxX - minX;
            float h = face <= 1 ? maxZ - minZ : hi - lo;
            record(face, minX, lo, minZ, w, h, layer, false, true, alphaFlag > 0.5f, 0, 0);
        }
    }

    /** Pulled twin of the water {@link QuadWriter}: one WATERQUAD16 record per flat sea-sheet cell. */
    private static final class LodWaterWriter extends QuadWriter {
        private final MmsQuadMeshBuilder quads;
        private final float originX, originZ;

        LodWaterWriter(int estimatedQuads, float originX, float originZ) {
            super(null, null, null, null, null, null, null, null, null);
            this.quads = new MmsQuadMeshBuilder(estimatedQuads, MmsVertexFormat.WATERQUAD16)
                .setOrigin(originX, 0f, originZ);
            this.originX = originX;
            this.originZ = originZ;
        }

        MmsMeshData build() {
            return quads.build();
        }

        @Override
        void topQuadFlat(float wx, float y, float wz, int cellSize, int layer, float xFlag) {
            int cellY = (int) Math.floor(y) + 1; // sheet sits 0.125 below the cell's top: cell = SEA_LEVEL
            int qx = Math.round(wx - originX);
            int qz = Math.round(wz - originZ);
            if (qx < 0 || qx > 255 || qz < 0 || qz > 255 || cellY < 0 || cellY > 511 || cellSize > 16) {
                return;
            }
            if (!quads.addWords(
                    MmsWaterQuadCodec.word0(qx, cellY, qz, 0, false, false),
                    MmsWaterQuadCodec.word1(cellY, y, y, y, y),
                    MmsWaterQuadCodec.word2(xFlag, xFlag, xFlag, xFlag),
                    MmsWaterQuadCodec.word3(cellSize, cellSize))) {
                return;
            }
            idxCount += 6;
            vertCount += 4;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
    }

    private static class QuadWriter {
        final float[] pos, tex, nrm, flagX, flagY, flagZ, flagW, layers;
        final int[] idx;
        int vertCount = 0;
        int idxCount  = 0;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        QuadWriter(float[] pos, float[] tex, float[] nrm,
                   float[] flagX, float[] flagY, float[] flagZ, float[] flagW,
                   float[] layers, int[] idx) {
            this.pos = pos;
            this.tex = tex;
            this.nrm = nrm;
            this.flagX = flagX;
            this.flagY = flagY;
            this.flagZ = flagZ;
            this.flagW = flagW;
            this.layers = layers;
            this.idx = idx;
        }

        /**
         * Flat-up top quad. Terrain never uses this anymore; the water writer
         * emits sheets with it ({@code xFlag} = surface-height fraction).
         */
        void topQuadFlat(float wx, float y, float wz, int cellSize, int layer, float xFlag) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            int v0 = pushVert(wx, y, wz, 0f, 0f, 0, 1, 0, xFlag, 0f, 1f, layer);
                    pushVert(x1, y, wz, 1f, 0f, 0, 1, 0, xFlag, 0f, 1f, layer);
                    pushVert(x1, y, z1, 1f, 1f, 0, 1, 0, xFlag, 0f, 1f, layer);
                    pushVert(wx, y, z1, 0f, 1f, 0, 1, 0, xFlag, 0f, 1f, layer);
            pushQuadIndices(v0);
        }

        /**
         * Terrain top quad with per-vertex gradient normals looked up from the
         * shared corner grid. Vertex order: (wx,wz) → (x1,wz) → (x1,z1) →
         * (wx,z1), i.e. corners (ix,iz), (ix+1,iz), (ix+1,iz+1), (ix,iz+1).
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
                    cornerNormals[n00], cornerNormals[n00 + 1], cornerNormals[n00 + 2], 0f, 0f, 1f, layer);
                    pushVert(x1, y, wz, 1f, 0f,
                    cornerNormals[n10], cornerNormals[n10 + 1], cornerNormals[n10 + 2], 0f, 0f, 1f, layer);
                    pushVert(x1, y, z1, 1f, 1f,
                    cornerNormals[n11], cornerNormals[n11 + 1], cornerNormals[n11 + 2], 0f, 0f, 1f, layer);
                    pushVert(wx, y, z1, 0f, 1f,
                    cornerNormals[n01], cornerNormals[n01 + 1], cornerNormals[n01 + 2], 0f, 0f, 1f, layer);
            pushQuadIndices(v0);
        }

        /** Skirt face on one cell edge descending from fTop to fBot. */
        void skirtQuad(float wx, float wz, int cellSize,
                       int dx, int dz, float fTop, float fBot, int layer, float lightVal) {
            float x1 = wx + cellSize;
            float z1 = wz + cellSize;
            if (dx > 0) {
                int v0 = pushVert(x1, fTop, wz, 0f, 0f, 1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(x1, fTop, z1, 1f, 0f, 1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(x1, fBot, z1, 1f, 1f, 1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(x1, fBot, wz, 0f, 1f, 1, 0, 0, 0f, 0f, lightVal, layer);
                pushQuadIndices(v0);
            } else if (dx < 0) {
                int v0 = pushVert(wx, fTop, z1, 0f, 0f, -1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(wx, fTop, wz, 1f, 0f, -1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(wx, fBot, wz, 1f, 1f, -1, 0, 0, 0f, 0f, lightVal, layer);
                        pushVert(wx, fBot, z1, 0f, 1f, -1, 0, 0, 0f, 0f, lightVal, layer);
                pushQuadIndices(v0);
            } else if (dz > 0) {
                int v0 = pushVert(x1, fTop, z1, 0f, 0f, 0, 0, 1, 0f, 0f, lightVal, layer);
                        pushVert(wx, fTop, z1, 1f, 0f, 0, 0, 1, 0f, 0f, lightVal, layer);
                        pushVert(wx, fBot, z1, 1f, 1f, 0, 0, 1, 0f, 0f, lightVal, layer);
                        pushVert(x1, fBot, z1, 0f, 1f, 0, 0, 1, 0f, 0f, lightVal, layer);
                pushQuadIndices(v0);
            } else {
                int v0 = pushVert(wx, fTop, wz, 0f, 0f, 0, 0, -1, 0f, 0f, lightVal, layer);
                        pushVert(x1, fTop, wz, 1f, 0f, 0, 0, -1, 0f, 0f, lightVal, layer);
                        pushVert(x1, fBot, wz, 1f, 1f, 0, 0, -1, 0f, 0f, lightVal, layer);
                        pushVert(wx, fBot, wz, 0f, 1f, 0, 0, -1, 0f, 0f, lightVal, layer);
                pushQuadIndices(v0);
            }
        }

        void axisAlignedQuad(float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz, int layer, float alphaFlag) {
            int v0 = pushVert(x0, y0, z0, 0f, 0f, nx, ny, nz, 0f, alphaFlag, 1f, layer);
                    pushVert(x1, y1, z1, 1f, 0f, nx, ny, nz, 0f, alphaFlag, 1f, layer);
                    pushVert(x2, y2, z2, 1f, 1f, nx, ny, nz, 0f, alphaFlag, 1f, layer);
                    pushVert(x3, y3, z3, 0f, 1f, nx, ny, nz, 0f, alphaFlag, 1f, layer);
            pushQuadIndices(v0);
        }

        private int pushVert(float x, float y, float z,
                             float u, float v,
                             float nx, float ny, float nz,
                             float fx, float fy, float fw, int layer) {
            int vi = vertCount;
            int p3 = vi * 3, p2 = vi * 2;
            pos[p3] = x; pos[p3 + 1] = y; pos[p3 + 2] = z;
            tex[p2] = u; tex[p2 + 1] = v;
            nrm[p3] = nx; nrm[p3 + 1] = ny; nrm[p3 + 2] = nz;
            flagX[vi] = fx;
            flagY[vi] = fy;
            flagZ[vi] = 0f;
            flagW[vi] = fw;
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
