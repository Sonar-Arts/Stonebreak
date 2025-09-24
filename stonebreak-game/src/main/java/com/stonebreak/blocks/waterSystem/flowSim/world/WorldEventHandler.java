package com.stonebreak.blocks.waterSystem.flowSim.world;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowValidator;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.Map;

/**
 * Handles world events that affect water flow.
 * Manages block placement/breaking events and their impact on water.
 *
 * Following Single Responsibility Principle - only handles world events.
 */
public class WorldEventHandler {

    private static final int MAX_HORIZONTAL_DISTANCE = 7;

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final FlowUpdateScheduler scheduler;
    private final FlowValidator validator;
    private final WorldWaterDetector detector;

    public WorldEventHandler(Map<Vector3i, WaterBlock> waterBlocks, FlowUpdateScheduler scheduler,
                            FlowValidator validator, WorldWaterDetector detector) {
        this.waterBlocks = waterBlocks;
        this.scheduler = scheduler;
        this.validator = validator;
        this.detector = detector;
    }

    /**
     * Handles block broken events.
     */
    public void onBlockBroken(int x, int y, int z) {
        Vector3i brokenPos = new Vector3i(x, y, z);
        World world = Game.getWorld();
        if (world == null) return;

        // Schedule updates for water blocks that might flow into the empty space
        scheduler.scheduleNeighborUpdates(brokenPos);

        // Check for water above that might flow down (increased range for cascading water)
        for (int dy = 1; dy <= 12; dy++) {
            Vector3i above = new Vector3i(x, y + dy, z);
            BlockType blockAbove = world.getBlockAt(above.x, above.y, above.z);

            if (blockAbove == BlockType.WATER) {
                // Ensure this water block is in the simulation
                if (!waterBlocks.containsKey(above)) {
                    detector.initializeWaterBlock(above, world);
                    System.out.println("DEBUG: Re-initialized water above broken block at " + above.x + "," + above.y + "," + above.z);
                }
                scheduler.scheduleFlowUpdate(above);
            } else if (blockAbove != BlockType.AIR) {
                // Stop checking if we hit a solid block
                break;
            }
        }

        // Check in a wider radius for water that can now flow into the space
        // Water can flow from up to 7 blocks away horizontally
        for (int radius = 1; radius <= MAX_HORIZONTAL_DISTANCE; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Skip if not on the perimeter of current radius
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    // Check multiple heights
                    for (int dy = -1; dy <= 2; dy++) {
                        Vector3i checkPos = new Vector3i(x + dx, y + dy, z + dz);
                        BlockType blockType = world.getBlockAt(checkPos.x, checkPos.y, checkPos.z);

                        if (blockType == BlockType.WATER) {
                            // Ensure this water block is tracked
                            if (!waterBlocks.containsKey(checkPos)) {
                                detector.initializeWaterBlock(checkPos, world);
                                System.out.println("DEBUG: Re-initialized water at distance " + radius + " from broken block");
                            }

                            // Schedule flow update
                            scheduler.scheduleFlowUpdate(checkPos);
                        }
                    }
                }
            }
        }

        System.out.println("DEBUG: Block broken at " + x + "," + y + "," + z + " - scheduled comprehensive water flow checks");
    }

    /**
     * Handles block placed events.
     */
    public void onBlockPlaced(int x, int y, int z) {
        Vector3i placedPos = new Vector3i(x, y, z);
        World world = Game.getWorld();
        if (world == null) return;

        // ARCHITECTURAL FIX: Immediately clean up any stale water block data at this position
        WaterBlock staleWaterBlock = waterBlocks.remove(placedPos);
        if (staleWaterBlock != null) {
            System.out.println("DEBUG: Removed stale water block data at placed position " + x + "," + y + "," + z);
        }

        // Schedule immediate neighbor updates
        scheduler.scheduleNeighborUpdates(placedPos);

        // Check for water blocks above that need to re-route their flow
        for (int dy = 1; dy <= 8; dy++) {
            Vector3i above = new Vector3i(x, y + dy, z);
            BlockType blockAbove = world.getBlockAt(above.x, above.y, above.z);

            if (blockAbove == BlockType.WATER) {
                // Ensure this water block is in the simulation
                if (!waterBlocks.containsKey(above)) {
                    // Re-initialize water block that exists in world but not in simulation
                    detector.initializeWaterBlock(above, world);
                    System.out.println("DEBUG: Re-initialized water block at " + above.x + "," + above.y + "," + above.z + " after block placement");
                }
                scheduler.scheduleFlowUpdate(above);
            }
        }

        // Check in a 3-block radius horizontally for water that might be affected
        // This ensures water flows properly around newly placed obstacles
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;

                for (int dy = -1; dy <= 2; dy++) {
                    Vector3i checkPos = new Vector3i(x + dx, y + dy, z + dz);
                    BlockType blockType = world.getBlockAt(checkPos.x, checkPos.y, checkPos.z);

                    if (blockType == BlockType.WATER) {
                        // Ensure this water block is tracked
                        if (!waterBlocks.containsKey(checkPos)) {
                            detector.initializeWaterBlock(checkPos, world);
                            System.out.println("DEBUG: Re-initialized nearby water at " + checkPos.x + "," + checkPos.y + "," + checkPos.z);
                        }

                        // Schedule update for this water block
                        scheduler.scheduleFlowUpdate(checkPos);
                    }
                }
            }
        }

        System.out.println("DEBUG: Block placed at " + x + "," + y + "," + z + " - scheduled comprehensive water updates");
    }

    /**
     * Handles terrain modifications that affect large areas.
     */
    public void onTerrainModified(Vector3i center, int radius) {
        World world = Game.getWorld();
        if (world == null) return;

        // Scan the modified area for water that needs attention
        detector.scanAreaForWater(center, radius, world);

        // Schedule updates for all water in the affected area
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Vector3i pos = new Vector3i(center.x + dx, center.y + dy, center.z + dz);
                    if (waterBlocks.containsKey(pos)) {
                        scheduler.scheduleFlowUpdate(pos);
                    }
                }
            }
        }
    }

    /**
     * Handles chunk loading events.
     */
    public void onChunkLoaded(int chunkX, int chunkZ) {
        // When a new chunk loads, scan it for water that needs to be integrated
        World world = Game.getWorld();
        if (world == null) return;

        Vector3i chunkCenter = new Vector3i(
            chunkX * 16 + 8,
            128, // Middle height
            chunkZ * 16 + 8
        );

        detector.scanAreaForWater(chunkCenter, 16, world);
    }

    /**
     * Handles chunk unloading events.
     */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        // Remove water blocks from the simulation when chunks unload
        // to prevent memory leaks
        int minX = chunkX * 16;
        int maxX = minX + 15;
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;

        waterBlocks.entrySet().removeIf(entry -> {
            Vector3i pos = entry.getKey();
            return pos.x >= minX && pos.x <= maxX && pos.z >= minZ && pos.z <= maxZ;
        });
    }
}