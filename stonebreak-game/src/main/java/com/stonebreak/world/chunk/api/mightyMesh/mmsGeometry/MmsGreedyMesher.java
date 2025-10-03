package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoChunkData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilder;

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

    // Chunk dimensions
    private static final int CHUNK_SIZE = 16;
    private static final int CHUNK_HEIGHT = 256;

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
        BlockType blockType;
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

        void set(BlockType type, float u, float v, boolean alpha) {
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

    /**
     * Generates mesh for one axis direction.
     *
     * @param chunkData Chunk data
     * @param builder Mesh builder
     * @param direction Face direction
     * @return Number of faces generated
     */
    private static int generateAxisMesh(CcoChunkData chunkData, MmsMeshBuilder builder, int direction) {
        int faceCount = 0;

        // Determine axis and dimensions based on direction
        int[] dims = getAxisDimensions(direction);
        int width = dims[0];
        int height = dims[1];
        int depth = dims[2];

        // Allocate face mask for current slice
        FaceMask[][] mask = new FaceMask[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                mask[x][y] = new FaceMask();
            }
        }

        // Process each slice along depth axis
        for (int d = 0; d < depth; d++) {
            // Clear mask
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    mask[x][y].clear();
                }
            }

            // Build mask for this slice
            buildFaceMask(chunkData, mask, direction, d, width, height);

            // Greedily merge and generate quads
            faceCount += generateMergedQuads(mask, builder, direction, d, width, height);
        }

        return faceCount;
    }

    /**
     * Gets axis dimensions based on face direction.
     *
     * @param direction Face direction
     * @return [width, height, depth] dimensions
     */
    private static int[] getAxisDimensions(int direction) {
        switch (direction) {
            case DIR_POS_X:
            case DIR_NEG_X:
                return new int[]{CHUNK_SIZE, CHUNK_HEIGHT, CHUNK_SIZE}; // Z, Y, X
            case DIR_POS_Y:
            case DIR_NEG_Y:
                return new int[]{CHUNK_SIZE, CHUNK_SIZE, CHUNK_HEIGHT}; // X, Z, Y
            case DIR_POS_Z:
            case DIR_NEG_Z:
                return new int[]{CHUNK_SIZE, CHUNK_HEIGHT, CHUNK_SIZE}; // X, Y, Z
            default:
                throw new IllegalArgumentException("Invalid direction: " + direction);
        }
    }

    /**
     * Builds a 2D mask of visible faces for current slice.
     *
     * @param chunkData Chunk data
     * @param mask Face mask to populate
     * @param direction Face direction
     * @param depth Current slice depth
     * @param width Mask width
     * @param height Mask height
     */
    private static void buildFaceMask(CcoChunkData chunkData, FaceMask[][] mask,
                                      int direction, int depth, int width, int height) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                // Convert mask coordinates to world coordinates
                int[] worldPos = maskToWorldCoords(direction, x, y, depth);
                int wx = worldPos[0];
                int wy = worldPos[1];
                int wz = worldPos[2];

                // Check if face is visible
                if (!isFaceVisible(chunkData, wx, wy, wz, direction)) {
                    continue;
                }

                BlockType block = chunkData.getBlock(wx, wy, wz);
                if (block == null || block == BlockType.AIR) {
                    continue;
                }

                // Add face to mask
                // TODO: Get actual texture coordinates from texture mapper
                float texU = 0.0f; // Placeholder
                float texV = 0.0f; // Placeholder
                boolean alphaTest = requiresAlphaTest(block);

                mask[x][y].set(block, texU, texV, alphaTest);
            }
        }
    }

    /**
     * Converts mask coordinates to world coordinates.
     *
     * @param direction Face direction
     * @param x Mask X coordinate
     * @param y Mask Y coordinate
     * @param depth Slice depth
     * @return [worldX, worldY, worldZ]
     */
    private static int[] maskToWorldCoords(int direction, int x, int y, int depth) {
        switch (direction) {
            case DIR_POS_X:
                return new int[]{depth, y, x};
            case DIR_NEG_X:
                return new int[]{depth, y, x};
            case DIR_POS_Y:
                return new int[]{x, depth, y};
            case DIR_NEG_Y:
                return new int[]{x, depth, y};
            case DIR_POS_Z:
                return new int[]{x, y, depth};
            case DIR_NEG_Z:
                return new int[]{x, y, depth};
            default:
                throw new IllegalArgumentException("Invalid direction");
        }
    }

    /**
     * Checks if a face is visible (not occluded by neighbor).
     *
     * @param chunkData Chunk data
     * @param x World X
     * @param y World Y
     * @param z World Z
     * @param direction Face direction
     * @return true if face is visible
     */
    private static boolean isFaceVisible(CcoChunkData chunkData, int x, int y, int z, int direction) {
        BlockType current = chunkData.getBlock(x, y, z);
        if (current == null || current == BlockType.AIR) {
            return false;
        }

        // Get neighbor position
        int[] offset = getDirectionOffset(direction);
        int nx = x + offset[0];
        int ny = y + offset[1];
        int nz = z + offset[2];

        // Check bounds
        if (!chunkData.isInBounds(nx, ny, nz)) {
            return true; // Faces at chunk boundaries are visible
        }

        BlockType neighbor = chunkData.getBlock(nx, ny, nz);
        if (neighbor == null || neighbor == BlockType.AIR) {
            return true;
        }

        // Face is visible if neighbor is transparent and current is not
        return isTransparent(neighbor) && !isTransparent(current);
    }

    /**
     * Gets direction offset vector.
     *
     * @param direction Face direction
     * @return [dx, dy, dz]
     */
    private static int[] getDirectionOffset(int direction) {
        switch (direction) {
            case DIR_POS_X: return new int[]{1, 0, 0};
            case DIR_NEG_X: return new int[]{-1, 0, 0};
            case DIR_POS_Y: return new int[]{0, 1, 0};
            case DIR_NEG_Y: return new int[]{0, -1, 0};
            case DIR_POS_Z: return new int[]{0, 0, 1};
            case DIR_NEG_Z: return new int[]{0, 0, -1};
            default: throw new IllegalArgumentException("Invalid direction");
        }
    }

    /**
     * Greedily merges faces in mask and generates quad geometry.
     *
     * @param mask Face mask
     * @param builder Mesh builder
     * @param direction Face direction
     * @param depth Slice depth
     * @param width Mask width
     * @param height Mask height
     * @return Number of quads generated
     */
    private static int generateMergedQuads(FaceMask[][] mask, MmsMeshBuilder builder,
                                           int direction, int depth, int width, int height) {
        int quadCount = 0;

        // Greedy meshing algorithm
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width;) {
                if (mask[x][y].isEmpty()) {
                    x++;
                    continue;
                }

                // Find width (expand along X)
                int w = 1;
                while (x + w < width && mask[x][y].canMergeWith(mask[x + w][y])) {
                    w++;
                }

                // Find height (expand along Y)
                int h = 1;
                boolean canExpandHeight = true;
                while (y + h < height && canExpandHeight) {
                    // Check if entire row can merge
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

                // Generate merged quad
                generateMergedQuad(builder, mask[x][y], direction, depth, x, y, w, h);
                quadCount++;

                // Clear merged region
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

    /**
     * Generates a merged quad spanning multiple blocks.
     *
     * @param builder Mesh builder
     * @param face Face properties
     * @param direction Face direction
     * @param depth Slice depth
     * @param x Mask X start
     * @param y Mask Y start
     * @param width Quad width
     * @param height Quad height
     */
    private static void generateMergedQuad(MmsMeshBuilder builder, FaceMask face,
                                           int direction, int depth,
                                           int x, int y, int width, int height) {
        // Convert mask coordinates to world space
        int[] worldStart = maskToWorldCoords(direction, x, y, depth);
        float wx = worldStart[0];
        float wy = worldStart[1];
        float wz = worldStart[2];

        // Generate quad vertices based on direction
        float[] vertices = generateQuadVertices(direction, wx, wy, wz, width, height);

        // Add texture coordinates and other attributes
        float[] normal = getDirectionNormal(direction);
        float alphaFlag = face.alphaTest ? 1.0f : 0.0f;
        float waterFlag = (face.blockType == BlockType.WATER) ? 1.0f : 0.0f;

        // Build quad with merged dimensions
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

    /**
     * Generates vertex positions for a merged quad.
     *
     * @param direction Face direction
     * @param x World X start
     * @param y World Y start
     * @param z World Z start
     * @param width Quad width
     * @param height Quad height
     * @return Array of 12 floats (4 vertices * 3 coordinates)
     */
    private static float[] generateQuadVertices(int direction, float x, float y, float z,
                                                int width, int height) {
        float[] vertices = new float[12];

        switch (direction) {
            case DIR_POS_Y: // Top face
                vertices[0] = x; vertices[1] = y + 1; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y + 1; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y + 1; vertices[8] = z + height;
                vertices[9] = x; vertices[10] = y + 1; vertices[11] = z + height;
                break;
            case DIR_NEG_Y: // Bottom face
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y; vertices[8] = z + height;
                vertices[9] = x; vertices[10] = y; vertices[11] = z + height;
                break;
            case DIR_POS_X: // Right face
                vertices[0] = x + 1; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + 1; vertices[4] = y; vertices[5] = z + width;
                vertices[6] = x + 1; vertices[7] = y + height; vertices[8] = z + width;
                vertices[9] = x + 1; vertices[10] = y + height; vertices[11] = z;
                break;
            case DIR_NEG_X: // Left face
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x; vertices[4] = y; vertices[5] = z + width;
                vertices[6] = x; vertices[7] = y + height; vertices[8] = z + width;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z;
                break;
            case DIR_POS_Z: // Front face
                vertices[0] = x; vertices[1] = y; vertices[2] = z + 1;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z + 1;
                vertices[6] = x + width; vertices[7] = y + height; vertices[8] = z + 1;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z + 1;
                break;
            case DIR_NEG_Z: // Back face
                vertices[0] = x; vertices[1] = y; vertices[2] = z;
                vertices[3] = x + width; vertices[4] = y; vertices[5] = z;
                vertices[6] = x + width; vertices[7] = y + height; vertices[8] = z;
                vertices[9] = x; vertices[10] = y + height; vertices[11] = z;
                break;
        }

        return vertices;
    }

    /**
     * Gets normal vector for face direction.
     *
     * @param direction Face direction
     * @return [nx, ny, nz]
     */
    private static float[] getDirectionNormal(int direction) {
        switch (direction) {
            case DIR_POS_X: return new float[]{1, 0, 0};
            case DIR_NEG_X: return new float[]{-1, 0, 0};
            case DIR_POS_Y: return new float[]{0, 1, 0};
            case DIR_NEG_Y: return new float[]{0, -1, 0};
            case DIR_POS_Z: return new float[]{0, 0, 1};
            case DIR_NEG_Z: return new float[]{0, 0, -1};
            default: return new float[]{0, 0, 0};
        }
    }

    /**
     * Checks if a block type is transparent.
     *
     * @param blockType Block type
     * @return true if transparent
     */
    private static boolean isTransparent(BlockType blockType) {
        return blockType == BlockType.AIR ||
               blockType == BlockType.WATER ||
               blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION;
    }

    /**
     * Checks if a block requires alpha testing.
     *
     * @param blockType Block type
     * @return true if alpha test needed
     */
    private static boolean requiresAlphaTest(BlockType blockType) {
        return blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION;
    }
}
