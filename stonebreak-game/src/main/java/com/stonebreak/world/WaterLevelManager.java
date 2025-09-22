package com.stonebreak.world;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.core.Game;

/**
 * Manages water level visual heights for rendering with distance-based decrease.
 * Integrates with the existing water system to provide smooth height transitions
 * similar to Minecraft's water mechanics where water visually decreases over distance.
 *
 * This system works with the existing WaterBlock depth system (0-7) to create
 * smooth visual slopes that connect water blocks of different depths.
 */
public class WaterLevelManager {

    /**
     * Gets the visual height for water at a specific position considering neighboring depths.
     * This creates smooth transitions between water blocks of different depths.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return Visual height for rendering (0.0 to 0.875)
     */
    public static float getWaterVisualHeight(int x, int y, int z) {
        WaterBlock waterBlock = Water.getWaterBlock(x, y, z);
        if (waterBlock == null || waterBlock.isEmpty()) {
            return 0.0f;
        }

        // Base height from the water block's depth
        float baseHeight = waterBlock.getVisualHeight();

        // For source blocks, check if they should remain flat
        if (waterBlock.isSource()) {
            // If this is an isolated source block, keep it flat
            if (isIsolatedSourceBlock(x, y, z)) {
                return baseHeight;
            }
            // Source blocks with flowing neighbors can have slight slopes
            return Math.max(baseHeight - 0.0625f, calculateSmoothHeight(x, y, z, waterBlock, baseHeight));
        }

        // For flowing water, consider neighbor depths for smooth transitions
        return calculateSmoothHeight(x, y, z, waterBlock, baseHeight);
    }

    /**
     * Checks if a source block is isolated (no flowing water neighbors).
     * Isolated source blocks should render flat to avoid the triangle effect.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if this source block has no flowing water neighbors
     */
    private static boolean isIsolatedSourceBlock(int x, int y, int z) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] dir : directions) {
            WaterBlock neighbor = Water.getWaterBlock(x + dir[0], y, z + dir[1]);
            if (neighbor != null && !neighbor.isEmpty() && !neighbor.isSource()) {
                return false; // Has flowing water neighbor
            }
        }
        return true; // No flowing neighbors found
    }

    /**
     * Calculates smooth height transitions by considering neighboring water depths.
     * This creates the sloped water effect similar to Minecraft.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param currentBlock Current water block
     * @param baseHeight Base height from water block depth
     * @return Adjusted height for smooth transitions
     */
    private static float calculateSmoothHeight(int x, int y, int z, WaterBlock currentBlock, float baseHeight) {
        int currentDepth = currentBlock.getDepth();

        // Check the 4 horizontal neighbors to find the average depth influence
        float heightSum = baseHeight * 4; // Start with current height for each direction
        int validNeighbors = 4;

        // Check each cardinal direction
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        World world = Game.getWorld();

        for (int[] dir : directions) {
            int nx = x + dir[0];
            int nz = z + dir[1];

            WaterBlock neighbor = Water.getWaterBlock(nx, y, nz);

            if (neighbor != null && !neighbor.isEmpty()) {
                // Neighbor has water - use its height
                float neighborHeight = neighbor.getVisualHeight();
                heightSum = heightSum - baseHeight + neighborHeight;
                continue;
            }

            BlockType neighborType = BlockType.AIR;
            if (world != null) {
                neighborType = world.getBlockAt(nx, y, nz);
            }

            if (neighborType == BlockType.WATER) {
                // Neighbor water exists in world but not simulation - fall back to visual height
                float fallbackHeight = Water.getWaterVisualHeight(nx, y, nz);
                if (fallbackHeight <= 0.0f) {
                    fallbackHeight = baseHeight;
                }
                heightSum = heightSum - baseHeight + fallbackHeight;
                continue;
            }

            if (!neighborType.isTransparent()) {
                // Solid neighbor blocks the flow, keep current height to avoid visual gaps
                continue;
            }

            // No water or solid neighbor - this edge should taper down for slope effect
            float edgeHeight = Math.max(0.125f, baseHeight - 0.125f);
            heightSum = heightSum - baseHeight + edgeHeight;
        }

        // Average the height influences for smooth transitions
        float smoothHeight = heightSum / validNeighbors;

        // Ensure the height stays within valid bounds
        return Math.max(0.125f, Math.min(0.875f, smoothHeight));
    }

    /**
     * Gets the water height considering corner interpolation for even smoother transitions.
     * This method can be used for more detailed rendering where corner vertices need
     * specific height values.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param cornerX Corner offset (0.0 to 1.0 within block)
     * @param cornerZ Corner offset (0.0 to 1.0 within block)
     * @return Interpolated height for the specific corner
     */
    public static float getWaterCornerHeight(int x, int y, int z, float cornerX, float cornerZ) {
        WaterBlock currentBlock = Water.getWaterBlock(x, y, z);

        // For isolated source blocks, return uniform height to prevent triangles
        if (currentBlock != null && currentBlock.isSource() && isIsolatedSourceBlock(x, y, z)) {
            return currentBlock.getVisualHeight();
        }

        // Get heights of the 4 surrounding block positions for interpolation
        float h00 = getWaterVisualHeight(x, y, z);
        float h10 = getWaterVisualHeight(x + 1, y, z);
        float h01 = getWaterVisualHeight(x, y, z + 1);
        float h11 = getWaterVisualHeight(x + 1, y, z + 1);

        // If this is the current block and some neighbors don't exist,
        // use the current block's height to avoid sudden drops
        if (cornerX >= 0.0f && cornerX <= 1.0f && cornerZ >= 0.0f && cornerZ <= 1.0f) {
            if (h10 == 0.0f) h10 = h00; // No water to the right, use current
            if (h01 == 0.0f) h01 = h00; // No water behind, use current
            if (h11 == 0.0f) h11 = Math.max(h10, h01); // No water diagonally, use best available
        }

        // Bilinear interpolation for smooth corner heights
        float h0 = h00 * (1 - cornerX) + h10 * cornerX;
        float h1 = h01 * (1 - cornerX) + h11 * cornerX;

        return h0 * (1 - cornerZ) + h1 * cornerZ;
    }

    /**
     * Checks if water should render a sloped surface at this position.
     * Returns true if neighboring water blocks have different depths.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if water should render with slopes
     */
    public static boolean shouldRenderSloped(int x, int y, int z) {
        WaterBlock current = Water.getWaterBlock(x, y, z);
        if (current == null || current.isEmpty()) {
            return false;
        }

        int currentDepth = current.getDepth();

        // Check if any neighbor has a different depth
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] dir : directions) {
            WaterBlock neighbor = Water.getWaterBlock(x + dir[0], y, z + dir[1]);

            if (neighbor == null || neighbor.isEmpty()) {
                return true; // Edge of water should be sloped
            }

            if (neighbor.getDepth() != currentDepth) {
                return true; // Different depths should be sloped
            }
        }

        return false; // All neighbors same depth - flat surface
    }

    /**
     * Gets the flow direction influence on height for creating directional slopes.
     * Water should appear to flow downhill based on the flow direction.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param offsetX Offset within block (-0.5 to 0.5)
     * @param offsetZ Offset within block (-0.5 to 0.5)
     * @return Height adjustment based on flow direction
     */
    public static float getFlowHeightAdjustment(int x, int y, int z, float offsetX, float offsetZ) {
        WaterBlock waterBlock = Water.getWaterBlock(x, y, z);
        if (waterBlock == null || waterBlock.isSource()) {
            return 0.0f; // No flow adjustment for source blocks
        }

        // Get flow direction (this indicates downhill direction)
        var flowDir = waterBlock.getFlowDirection();

        // Calculate height adjustment based on position relative to flow
        float flowInfluence = (offsetX * flowDir.x + offsetZ * flowDir.z);

        // Scale the influence (max 0.0625f adjustment = 1/16 of a block)
        return flowInfluence * 0.0625f;
    }
}
