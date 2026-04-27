package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelWorld;
import com.openmason.engine.voxel.VoxelWorldConfig;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOCullingPolicy;

import java.util.function.Predicate;

/**
 * Shared face culling logic for block geometry providers.
 *
 * <p>Determines which faces of a block should be rendered based on
 * adjacent block transparency. Extracted from the legacy MmsCcoAdapter
 * to be reused by all geometry providers.
 */
public class MmsFaceCullingService implements SBOCullingPolicy {

    private IVoxelWorld world;
    private Predicate<IBlockType> translucencyPolicy = block -> false;
    private Predicate<IBlockType> crossBlockPolicy = block -> false;

    public MmsFaceCullingService() {
    }

    /**
     * Configure the predicate that identifies TRANSLUCENT (alpha-blended,
     * cube-shaped) blocks such as ice. Translucent cube faces are culled
     * against opaque neighbors to avoid coplanar z-fighting in the
     * transparent pass (depth writes disabled).
     *
     * <p>CUTOUT blocks (flowers, leaves) do NOT use this rule — their
     * "faces" aren't cube faces and culling them drops geometry.
     *
     * @param policy predicate returning true for translucent blocks
     */
    public void setTranslucencyPolicy(Predicate<IBlockType> policy) {
        this.translucencyPolicy = policy != null ? policy : block -> false;
    }

    /**
     * Configure the predicate that identifies cross-plane blocks (flowers
     * and similar non-cube geometry). These blocks skip neighbor-based
     * face culling entirely: their geometry is two intersecting planes
     * packed into the stamp's face slots, so culling a "face" because a
     * neighbor exists can drop an entire visible plane (notably when two
     * flowers of the same type are adjacent).
     *
     * @param policy predicate returning true for cross-plane blocks
     */
    public void setCrossBlockPolicy(Predicate<IBlockType> policy) {
        this.crossBlockPolicy = policy != null ? policy : block -> false;
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
     *   <li>Transparent blocks render against different transparent block
     *       types (so e.g. water next to ice is visible both ways), but are
     *       culled against opaque neighbors — the opaque block fully hides
     *       the adjacent face, and drawing it only causes coplanar
     *       z-fighting in the transparent pass (depth writes disabled).</li>
     *   <li>Opaque blocks cull against other opaque blocks</li>
     *   <li>Opaque blocks render against transparent blocks</li>
     * </ol>
     */
    public boolean shouldRenderAgainst(IBlockType blockType, IBlockType adjacentBlock) {
        if (adjacentBlock == null || adjacentBlock.isAir()) {
            return true;
        }

        // Cross-plane blocks (flowers) bypass neighbor culling entirely.
        // Their geometry isn't oriented to cube faces, so any face-slot
        // culling can silently drop a visible plane.
        if (crossBlockPolicy.test(blockType)) {
            return true;
        }

        // Compare by stable identity (block ID), not reference. Cross-chunk
        // neighbor lookups go through WorldAdapter.getBlockAt(), which wraps
        // each BlockType in a fresh BlockTypeAdapter record — so reference
        // equality silently fails at chunk borders and lets same-type
        // translucent faces leak through.
        boolean sameType = blockType.getId() == adjacentBlock.getId();

        if (blockType.isTransparent()) {
            // TRANSLUCENT cube-shaped blocks (e.g. ice) need stricter culling:
            // the face is hidden behind any opaque neighbor, and rendering it
            // causes coplanar z-fighting in the transparent pass since depth
            // writes are disabled there.
            //
            // Other transparent blocks (CUTOUT flowers, leaves) keep the
            // permissive "different type" rule — their "faces" may actually
            // be cross-plane geometry packed into face slots, so culling
            // them against opaque neighbors drops visible planes.
            if (translucencyPolicy.test(blockType)) {
                return adjacentBlock.isTransparent() && !sameType;
            }
            return !sameType;
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
