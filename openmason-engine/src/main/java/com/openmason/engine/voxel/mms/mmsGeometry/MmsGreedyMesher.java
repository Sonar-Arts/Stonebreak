package com.openmason.engine.voxel.mms.mmsGeometry;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;

/**
 * Mighty Mesh System - Greedy meshing algorithm implementation.
 *
 * Reduces triangle count by merging adjacent faces with the same texture
 * and visual properties. This dramatically improves rendering performance.
 *
 * Design Philosophy:
 * - Performance: Minimize draw calls and vertex count
 * - Correctness: Never merge faces that would produce visual artifacts
 * - KISS: Simple algorithm, easy to understand and maintain
 *
 * Algorithm:
 * 1. For each axis direction (X, Y, Z):
 *    - Scan through chunk in slices perpendicular to axis
 *    - Build 2D mask of visible faces in current slice
 *    - Greedily merge adjacent faces with same properties
 *    - Generate merged quad geometry
 *
 * Performance:
 * - Typical reduction: 50-70% fewer triangles vs naive meshing
 * - Time complexity: O(n³) where n = chunk size (16)
 * - Memory: O(n²) for face mask arrays
 *
 * @since MMS 1.1
 */
public final class MmsGreedyMesher {

    // Face direction constants
    private static final int DIR_POS_X = 0;
    private static final int DIR_NEG_X = 1;
    private static final int DIR_POS_Y = 2;
    private static final int DIR_NEG_Y = 3;
    private static final int DIR_POS_Z = 4;
    private static final int DIR_NEG_Z = 5;

    /**
     * Face mask entry representing a visible face.
     * Contains block type and texture coordinates for merging decisions.
     */
    private static class FaceMask {
        IBlockType blockType;
        float texU, texV;
        boolean alphaTest;

        FaceMask() {
            blockType = null;
        }

        boolean isEmpty() {
            return blockType == null;
        }

        boolean canMergeWith(FaceMask other) {
            if (isEmpty() || other.isEmpty()) {
                return false;
            }
            return blockType == other.blockType &&
                   texU == other.texU &&
                   texV == other.texV &&
                   alphaTest == other.alphaTest;
        }

        void set(IBlockType type, float u, float v, boolean alpha) {
            this.blockType = type;
            this.texU = u;
            this.texV = v;
            this.alphaTest = alpha;
        }

        void clear() {
            this.blockType = null;
        }
    }

    /**
     * Generates an optimized mesh using greedy meshing algorithm.
     *
     * @param chunkData Chunk data to mesh
     * @param builder Mesh builder to append geometry to
     * @return Number of faces generated
     */
    public static int generateGreedyMesh(CcoChunkData chunkData, MmsMeshBuilder builder) {
        if (chunkData == null || builder == null) {
            throw new IllegalArgumentException("ChunkData and builder cannot be null");
        }

        int totalFaces = 0;

        // Process each axis direction
        totalFaces += generateAxisMesh(chunkData, builder, DIR_POS_X);
        totalFaces += generateAxisMesh(chunkData, builder, DIR_NEG_X);
        totalFaces += generateAxisMesh(chunkData, builder, DIR_POS_Y);
        totalFaces += generateAxisMesh(chunkData, builder, DIR_NEG_Y);
        totalFaces += generateAxisMesh(chunkData, builder, DIR_POS_Z);
        totalFaces += generateAxisMesh(chunkData, builder, DIR_NEG_Z);

        return totalFaces;
    }

    private static int generateAxisMesh(CcoChunkData chunkData, MmsMeshBuilder builder, int direction) {
        int faceCount = 0;

        int[] dims = getAxisDimensions(direction);
        int width = dims[0];
        int height = dims[1];
        int depth = dims[2];

        FaceMask[][] mask = new FaceMask[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                mask[x][y] = new FaceMask();
            }
        }

        for (int d = 0; d < depth; d++) {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    mask[x][y].clear();
                }
            }

            buildFaceMask(chunkData, mask, direction, d, width, height);
            faceCount += generateMergedQuads(mask, builder, direction, d, width, height);
        }

        return faceCount;
    }

    private static int[] getAxisDimensions(int direction) {
        int cs = CcoBounds.getConfig().chunkSize();
        int ch = CcoBounds.getConfig().worldHeight();
        return switch (direction) {
            case DIR_POS_X, DIR_NEG_X -> new int[]{cs, ch, cs};
            case DIR_POS_Y, DIR_NEG_Y -> new int[]{cs, cs, ch};
            case DIR_POS_Z, DIR_NEG_Z -> new int[]{cs, ch, cs};
            default -> throw new IllegalArgumentException("Invalid direction: " + direction);
        };
    }

    private static void buildFaceMask(CcoChunkData chunkData, FaceMask[][] mask,
                                      int direction, int depth, int width, int height) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int[] worldPos = maskToWorldCoords(direction, x, y, depth);
                int wx = worldPos[0];
                int wy = worldPos[1];
                int wz = worldPos[2];

                if (!isFaceVisible(chunkData, wx, wy, wz, direction)) {
                    continue;
                }

                IBlockType block = chunkData.getBlock(wx, wy, wz);
                if (block == null || block.isAir()) {
                    continue;
                }

                float texU = 0.0f;
                float texV = 0.0f;
                boolean alphaTest = block.isTransparent() && !block.isAir();

                mask[x][y].set(block, texU, texV, alphaTest);
            }
        }
    }

    private static int[] maskToWorldCoords(int direction, int x, int y, int depth) {
        return switch (direction) {
            case DIR_POS_X, DIR_NEG_X -> new int[]{depth, y, x};
            case DIR_POS_Y, DIR_NEG_Y -> new int[]{x, depth, y};
            case DIR_POS_Z, DIR_NEG_Z -> new int[]{x, y, depth};
            default -> throw new IllegalArgumentException("Invalid direction");
        };
    }

    private static boolean isFaceVisible(CcoChunkData chunkData, int x, int y, int z, int direction) {
        IBlockType current = chunkData.getBlock(x, y, z);
        if (current == null || current.isAir()) {
            return false;
        }

        int[] offset = getDirectionOffset(direction);
        int nx = x + offset[0];
        int ny = y + offset[1];
        int nz = z + offset[2];

        if (!chunkData.isInBounds(nx, ny, nz)) {
            return true;
        }

        IBlockType neighbor = chunkData.getBlock(nx, ny, nz);
        if (neighbor == null || neighbor.isAir()) {
            return true;
        }

        // Transparent blocks render faces against any different block type
        if (current.isTransparent()) {
            return current != neighbor;
        }

        // Opaque blocks render faces against transparent blocks
        return neighbor.isTransparent();
    }

    private static int[] getDirectionOffset(int direction) {
        return switch (direction) {
            case DIR_POS_X -> new int[]{1, 0, 0};
            case DIR_NEG_X -> new int[]{-1, 0, 0};
            case DIR_POS_Y -> new int[]{0, 1, 0};
            case DIR_NEG_Y -> new int[]{0, -1, 0};
            case DIR_POS_Z -> new int[]{0, 0, 1};
            case DIR_NEG_Z -> new int[]{0, 0, -1};
            default -> throw new IllegalArgumentException("Invalid direction");
        };
    }

    private static int generateMergedQuads(FaceMask[][] mask, MmsMeshBuilder builder,
                                           int direction, int depth, int width, int height) {
        int quadCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width;) {
                if (mask[x][y].isEmpty()) {
                    x++;
                    continue;
                }

                int w = 1;
                while (x + w < width && mask[x][y].canMergeWith(mask[x + w][y])) {
                    w++;
                }

                int h = 1;
                boolean canExpandHeight = true;
                while (y + h < height && canExpandHeight) {
                    for (int dx = 0; dx < w; dx++) {
                        if (!mask[x][y].canMergeWith(mask[x + dx][y + h])) {
                            canExpandHeight = false;
                            break;
                        }
                    }
                    if (canExpandHeight) {
                        h++;
                    }
                }

                generateMergedQuad(builder, mask[x][y], direction, depth, x, y, w, h);
                quadCount++;

                for (int dy = 0; dy < h; dy++) {
                    for (int dx = 0; dx < w; dx++) {
                        mask[x + dx][y + dy].clear();
                    }
                }

                x += w;
            }
        }

        return quadCount;
    }

    private static void generateMergedQuad(MmsMeshBuilder builder, FaceMask face,
                                           int direction, int depth,
                                           int x, int y, int width, int height) {
        int[] worldStart = maskToWorldCoords(direction, x, y, depth);
        float wx = worldStart[0];
        float wy = worldStart[1];
        float wz = worldStart[2];

        float[] vertices = generateQuadVertices(direction, wx, wy, wz, width, height);
        float[] normal = getDirectionNormal(direction);
        float alphaFlag = face.alphaTest ? 1.0f : 0.0f;
        float waterFlag = 0.0f; // Water flag handled by specific water provider

        builder.beginFace()
            .addVertex(vertices[0], vertices[1], vertices[2],
                      face.texU, face.texV,
                      normal[0], normal[1], normal[2],
                      waterFlag, alphaFlag)
            .addVertex(vertices[3], vertices[4], vertices[5],
                      face.texU + width, face.texV,
                      normal[0], normal[1], normal[2],
                      waterFlag, alphaFlag)
            .addVertex(vertices[6], vertices[7], vertices[8],
                      face.texU + width, face.texV + height,
                      normal[0], normal[1], normal[2],
                      waterFlag, alphaFlag)
            .addVertex(vertices[9], vertices[10], vertices[11],
                      face.texU, face.texV + height,
                      normal[0], normal[1], normal[2],
                      waterFlag, alphaFlag)
            .endFace();
    }

    private static float[] generateQuadVertices(int direction, float x, float y, float z,
                                                int width, int height) {
        float[] vertices = new float[12];

        switch (direction) {
            case DIR_POS_Y:
                vertices[0] = x; vertices[1] = y + 1; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y + 1; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y + 1; vertices[8] = z + height;
                vertices[9] = x; vertices[10] = y + 1; vertices[11] = z + height;
                break;
            case DIR_NEG_Y:
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y; vertices[8] = z + height;
                vertices[9] = x; vertices[10] = y; vertices[11] = z + height;
                break;
            case DIR_POS_X:
                vertices[0] = x + 1; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + 1; vertices[4] = y; vertices[5] = z + width;
                vertices[6] = x + 1; vertices[7] = y + height; vertices[8] = z + width;
                vertices[9] = x + 1; vertices[10] = y + height; vertices[11] = z;
                break;
            case DIR_NEG_X:
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x; vertices[4] = y; vertices[5] = z + width;
                vertices[6] = x; vertices[7] = y + height; vertices[8] = z + width;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z;
                break;
            case DIR_POS_Z:
                vertices[0] = x; vertices[1] = y; vertices[2] = z + 1;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z + 1;
                vertices[6] = x + width; vertices[7] = y + height; vertices[8] = z + 1;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z + 1;
                break;
            case DIR_NEG_Z:
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y + height; vertices[8] = z;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z;
                break;
        }

        return vertices;
    }

    private static float[] getDirectionNormal(int direction) {
        return switch (direction) {
            case DIR_POS_X -> new float[]{1, 0, 0};
            case DIR_NEG_X -> new float[]{-1, 0, 0};
            case DIR_POS_Y -> new float[]{0, 1, 0};
            case DIR_NEG_Y -> new float[]{0, -1, 0};
            case DIR_POS_Z -> new float[]{0, 0, 1};
            case DIR_NEG_Z -> new float[]{0, 0, -1};
            default -> new float[]{0, 0, 0};
        };
    }
}
