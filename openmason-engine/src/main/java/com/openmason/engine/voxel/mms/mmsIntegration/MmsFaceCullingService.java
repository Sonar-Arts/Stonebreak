package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelWorld;
import com.openmason.engine.voxel.VoxelWorldConfig;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;

/**
 * Shared face culling logic for block geometry providers.
 *
 * <p>Determines which faces of a block should be rendered based on
 * adjacent block transparency. Extracted from the legacy MmsCcoAdapter
 * to be reused by all geometry providers.
 */
public class MmsFaceCullingService {

    private IVoxelWorld world;

    public MmsFaceCullingService() {
    }

    /**
     * Set the world reference for cross-chunk neighbor lookups.
     *
     * @param world the voxel world
     */
    public void setWorld(IVoxelWorld world) {
        this.world = world;
    }

    /**
     * Determines if a face should be rendered based on adjacent blocks.
     *
     * @param blockType the block being rendered
     * @param lx        local X
     * @param ly        local Y
     * @param lz        local Z
     * @param face      face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @param chunkData chunk data for neighbor lookups
     * @return true if the face should be rendered
     */
    public boolean shouldRenderFace(IBlockType blockType, int lx, int ly, int lz,
                                    int face, CcoChunkData chunkData) {
        int adjX = lx + getFaceOffsetX(face);
        int adjY = ly + getFaceOffsetY(face);
        int adjZ = lz + getFaceOffsetZ(face);

        IBlockType adjacentBlock = getAdjacentBlock(adjX, adjY, adjZ, chunkData);
        return shouldRenderAgainst(blockType, adjacentBlock);
    }

    /**
     * Determines if a face should render against an adjacent block.
     *
     * <p>Culling Rules:
     * <ol>
     *   <li>Always render against AIR</li>
     *   <li>Transparent blocks render against different block types</li>
     *   <li>Opaque blocks cull against other opaque blocks</li>
     *   <li>Opaque blocks render against transparent blocks</li>
     * </ol>
     */
    public boolean shouldRenderAgainst(IBlockType blockType, IBlockType adjacentBlock) {
        if (adjacentBlock == null || adjacentBlock.isAir()) {
            return true;
        }

        if (blockType.isTransparent()) {
            return blockType != adjacentBlock;
        }

        return adjacentBlock.isTransparent();
    }

    /**
     * Gets the adjacent block, handling chunk boundaries via world lookup.
     */
    public IBlockType getAdjacentBlock(int adjX, int adjY, int adjZ, CcoChunkData chunkData) {
        VoxelWorldConfig config = CcoBounds.getConfig();

        if (adjX >= 0 && adjX < config.chunkSize() &&
            adjY >= 0 && adjY < config.worldHeight() &&
            adjZ >= 0 && adjZ < config.chunkSize()) {
            return chunkData.getBlock(adjX, adjY, adjZ);
        }

        if (world != null && adjY >= 0 && adjY < config.worldHeight()) {
            int worldX = adjX + chunkData.getChunkX() * config.chunkSize();
            int worldZ = adjZ + chunkData.getChunkZ() * config.chunkSize();

            int neighborChunkX = Math.floorDiv(worldX, config.chunkSize());
            int neighborChunkZ = Math.floorDiv(worldZ, config.chunkSize());

            if (world.hasChunkAt(neighborChunkX, neighborChunkZ)) {
                IBlockType adjacentBlock = world.getBlockAt(worldX, adjY, worldZ);
                return adjacentBlock;
            }
        }

        return null; // Out of bounds = treat as air
    }

    // Face offset helpers
    public static int getFaceOffsetX(int face) {
        return switch (face) {
            case 4 -> 1;  // East
            case 5 -> -1; // West
            default -> 0;
        };
    }

    public static int getFaceOffsetY(int face) {
        return switch (face) {
            case 0 -> 1;  // Top
            case 1 -> -1; // Bottom
            default -> 0;
        };
    }

    public static int getFaceOffsetZ(int face) {
        return switch (face) {
            case 2 -> -1; // North
            case 3 -> 1;  // South
            default -> 0;
        };
    }
}
