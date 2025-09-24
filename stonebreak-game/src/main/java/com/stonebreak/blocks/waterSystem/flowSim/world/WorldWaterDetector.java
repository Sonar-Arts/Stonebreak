package com.stonebreak.blocks.waterSystem.flowSim.world;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowValidator;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.blocks.waterSystem.flowSim.management.WaterSourceManager;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3i;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Detects and manages existing water in the world.
 * Handles ocean preservation and water initialization.
 *
 * Following Single Responsibility Principle - only handles world water detection.
 */
public class WorldWaterDetector {

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final WaterSourceManager sourceManager;
    private final FlowUpdateScheduler scheduler;
    private final FlowValidator validator;

    public WorldWaterDetector(Map<Vector3i, WaterBlock> waterBlocks, WaterSourceManager sourceManager,
                             FlowUpdateScheduler scheduler, FlowValidator validator) {
        this.waterBlocks = waterBlocks;
        this.sourceManager = sourceManager;
        this.scheduler = scheduler;
        this.validator = validator;
    }

    /**
     * Detects existing water blocks in the world and initializes them in the simulation.
     * This preserves naturally generated oceans and other pre-existing water.
     */
    public void detectExistingWater() {
        World world = Game.getWorld();
        if (world == null) {
            // Clear simulation data if no world exists
            waterBlocks.clear();
            sourceManager.clear();
            scheduler.clear();
            return;
        }

        System.out.println("DEBUG: Detecting and preserving existing water blocks in the world...");

        // Clear only flow queues, but preserve existing water data for incremental updates
        scheduler.clear();

        // Get currently loaded chunks around player and scan for water
        // Use a reasonable search area - get chunks in a large radius
        int searchRadius = 16; // chunks in each direction
        Map<World.ChunkPosition, Chunk> loadedChunks = new HashMap<>();

        // Get player position to center the search
        com.stonebreak.player.Player player = Game.getPlayer();
        int centerChunkX = 0, centerChunkZ = 0;
        if (player != null) {
            centerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
            centerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        }

        // Get chunks in a wide area around the player (or origin if no player)
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (chunk != null) {
                    loadedChunks.put(new World.ChunkPosition(chunkX, chunkZ), chunk);
                }
            }
        }

        int detectedWater = 0;
        int preservedOceans = 0;

        for (Chunk chunk : loadedChunks.values()) {
            if (chunk == null) continue;

            int chunkX = chunk.getChunkX();
            int chunkZ = chunk.getChunkZ();

            // Scan entire chunk for water blocks
            for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                        if (chunk.getBlock(x, y, z) == BlockType.WATER) {
                            int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + x;
                            int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + z;
                            Vector3i pos = new Vector3i(worldX, y, worldZ);

                            // Only add if not already tracked
                            if (!waterBlocks.containsKey(pos)) {
                                // Determine if this is ocean water (at or below sea level)
                                boolean isOceanWater = (y <= WorldConfiguration.SEA_LEVEL);

                                if (isOceanWater) {
                                    // Treat ocean water as stable source blocks with proper OceanWaterType
                                    WaterBlock oceanWater = WaterBlock.createWithType(new com.stonebreak.blocks.waterSystem.types.OceanWaterType());
                                    oceanWater.setSource(true);
                                    oceanWater.setOceanWater(true); // Mark as ocean water
                                    waterBlocks.put(pos, oceanWater);
                                    sourceManager.addSourceBlock(pos);
                                    preservedOceans++;
                                } else {
                                    // Regular water above sea level - estimate depth
                                    int estimatedDepth = validator.estimateDepthFromSurroundings(pos, world);
                                    WaterBlock waterBlock = new WaterBlock(estimatedDepth);
                                    waterBlocks.put(pos, waterBlock);

                                    // Schedule for flow validation
                                    scheduler.scheduleFlowUpdate(pos);
                                }
                                detectedWater++;
                            }
                        }
                    }
                }
            }
        }

        System.out.println("DEBUG: Detected " + detectedWater + " existing water blocks, preserved " +
                          preservedOceans + " ocean blocks as stable sources");
    }

    /**
     * Ensures nearby water blocks are tracked in the simulation.
     * This helps recover from situations where water blocks exist in the world
     * but aren't properly tracked in the flow simulation.
     */
    public void ensureNearbyWaterTracked(Vector3i centerPos, World world) {
        // Check all blocks within a 2-block radius
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    Vector3i checkPos = new Vector3i(centerPos.x + dx, centerPos.y + dy, centerPos.z + dz);
                    BlockType blockType = world.getBlockAt(checkPos.x, checkPos.y, checkPos.z);

                    if (blockType == BlockType.WATER && !waterBlocks.containsKey(checkPos)) {
                        // Found untracked water block
                        WaterBlock waterBlock = new WaterBlock(validator.estimateDepthFromSurroundings(checkPos, world));
                        waterBlocks.put(checkPos, waterBlock);
                        scheduler.scheduleFlowUpdate(checkPos);
                    }
                }
            }
        }
    }

    /**
     * Initializes water tracking for water discovered at runtime.
     */
    public void initializeWaterBlock(Vector3i pos, World world) {
        if (!waterBlocks.containsKey(pos)) {
            WaterBlock waterBlock = new WaterBlock(validator.estimateDepthFromSurroundings(pos, world));
            waterBlocks.put(pos, waterBlock);
        }
    }

    /**
     * Scans a specific area for water blocks.
     */
    public void scanAreaForWater(Vector3i center, int radius, World world) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Vector3i pos = new Vector3i(center.x + dx, center.y + dy, center.z + dz);
                    BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);

                    if (blockType == BlockType.WATER && !waterBlocks.containsKey(pos)) {
                        initializeWaterBlock(pos, world);
                        scheduler.scheduleFlowUpdate(pos);
                    }
                }
            }
        }
    }
}