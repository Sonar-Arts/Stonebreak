package com.stonebreak;

import org.joml.Vector3f;
import org.joml.Vector3i;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.LinkedList;

/**
 * Manages water particles and effects, including water flow simulation.
 */
public class WaterEffects {
    private static final int MAX_PARTICLES = 50;
    private static final float PARTICLE_LIFETIME = 1.5f; // seconds
    private static final float PARTICLE_SPAWN_INTERVAL = 0.2f; // seconds
    private static final float PARTICLE_SIZE = 0.05f;
    
    // Water flow simulation constants
    private static final int MAX_FLOW_DISTANCE = 7; // Maximum distance water can flow from source
    private static final float BASE_WATER_UPDATE_INTERVAL = 0.2f; // Base update interval (slower for stability)
    private static final float MIN_WATER_UPDATE_INTERVAL = 0.15f; // Minimum interval for large water systems
    private static final float MAX_WATER_UPDATE_INTERVAL = 0.3f; // Maximum interval for small water systems
    private static final int MAX_WATER_LEVEL = 7; // Water levels 0-7 (0 = no water, 7 = full block)
    private static final int BASE_BATCH_SIZE = 30; // Base number of water blocks to process per batch (reduced)
    private static final int MAX_BATCH_SIZE = 100; // Maximum batch size for large systems (reduced)
    
    private List<WaterParticle> particles;
    private Random random;
    private float timeSinceLastSpawn;
    
    // Water flow simulation data
    private Map<Vector3i, WaterBlock> waterBlocks; // Tracks all water blocks and their flow levels
    private Set<Vector3i> waterSources; // Tracks water source blocks
    private Queue<Vector3i> waterUpdateQueue; // Queue for water blocks that need updating
    private Queue<PendingFlow> pendingFlows; // Queue for gradual water spreading
    private float timeSinceWaterUpdate;
    
    // Batch processing data
    private List<Vector3i> currentBatch; // Current batch of blocks being processed
    private List<PendingFlow> batchFlows; // Flows generated in current batch
    private Set<Vector3i> processedInBatch; // Blocks processed in current batch to avoid duplicates
    private float currentUpdateInterval; // Dynamic update interval based on water system size
    
    // Neighbor calculation cache for bulk operations
    private Map<Vector3i, Integer> neighborLevelCache; // Cache for neighbor level calculations
    private Set<Vector3i> cacheValidPositions; // Positions where cache is valid for current frame
    
    public WaterEffects() {
        this.particles = new ArrayList<>();
        this.random = new Random();
        this.timeSinceLastSpawn = 0.0f;
        
        // Initialize water flow simulation
        this.waterBlocks = new HashMap<>();
        this.waterSources = new HashSet<>();
        this.waterUpdateQueue = new LinkedList<>();
        this.pendingFlows = new LinkedList<>();
        this.timeSinceWaterUpdate = 0.0f;
        
        // Initialize batch processing
        this.currentBatch = new ArrayList<>();
        this.batchFlows = new ArrayList<>();
        this.processedInBatch = new HashSet<>();
        this.currentUpdateInterval = BASE_WATER_UPDATE_INTERVAL;
        
        // Initialize neighbor calculation cache
        this.neighborLevelCache = new HashMap<>();
        this.cacheValidPositions = new HashSet<>();
    }
    
    /**
     * Updates all water particles and water flow simulation.
     * @param player The player
     * @param deltaTime Time since last update
     */
    public void update(Player player, float deltaTime) {
        // Update water particles
        updateParticles(player, deltaTime);
        
        // Update water flow simulation
        updateWaterFlow(deltaTime);
    }
    
    /**
     * Updates water particles and creates new ones if the player is moving in water.
     * @param player The player
     * @param deltaTime Time since last update
     */
    private void updateParticles(Player player, float deltaTime) {
        // Only spawn particles if the player is in water and moving
        if (player.isInWater() && (
            Math.abs(player.getVelocity().x) > 1.0f || 
            Math.abs(player.getVelocity().z) > 1.0f || 
            Math.abs(player.getVelocity().y) > 1.0f)) {
            
            timeSinceLastSpawn += deltaTime;
            
            // Spawn new particles at an interval
            if (timeSinceLastSpawn >= PARTICLE_SPAWN_INTERVAL && particles.size() < MAX_PARTICLES) {
                spawnParticle(player);
                timeSinceLastSpawn = 0.0f;
            }
        }
        
        // Update existing particles
        Iterator<WaterParticle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            WaterParticle particle = iterator.next();
            particle.update(deltaTime);
            
            // Remove expired particles
            if (particle.getLifetime() <= 0) {
                iterator.remove();
            }
        }
    }
    
    /**
     * Spawns a water particle at the player's position.
     */
    private void spawnParticle(Player player) {
        Vector3f position = new Vector3f(player.getPosition());
        
        // Randomize position slightly around the player
        position.x += (random.nextFloat() - 0.5f) * 0.5f;
        position.y += random.nextFloat() * 0.5f; // Mostly above the player's feet
        position.z += (random.nextFloat() - 0.5f) * 0.5f;
        
        // Create a velocity that moves slightly upward and in a random direction
        Vector3f velocity = new Vector3f(
            (random.nextFloat() - 0.5f) * 0.5f,
            random.nextFloat() * 0.5f + 0.2f, // Mostly upward
            (random.nextFloat() - 0.5f) * 0.5f
        );
        
        WaterParticle particle = new WaterParticle(position, velocity, PARTICLE_LIFETIME);
        particles.add(particle);
    }
    
    /**
     * Gets all active water particles.
     */
    public List<WaterParticle> getParticles() {
        return particles;
    }
    
    /**
     * Updates the water flow simulation with dynamic batching.
     * @param deltaTime Time since last update
     */
    private void updateWaterFlow(float deltaTime) {
        timeSinceWaterUpdate += deltaTime;
        
        // Calculate dynamic update interval based on water system size
        updateDynamicInterval();
        
        if (timeSinceWaterUpdate >= currentUpdateInterval) {
            processBatchedWaterFlow();
            timeSinceWaterUpdate = 0.0f;
        }
    }
    
    /**
     * Calculates dynamic update interval based on water system size.
     */
    private void updateDynamicInterval() {
        int totalWaterBlocks = waterBlocks.size();
        
        if (totalWaterBlocks <= 10) {
            // Small systems: slower updates for realism
            currentUpdateInterval = MAX_WATER_UPDATE_INTERVAL;
        } else if (totalWaterBlocks <= 50) {
            // Medium systems: base interval
            currentUpdateInterval = BASE_WATER_UPDATE_INTERVAL;
        } else {
            // Large systems: faster updates for responsiveness
            currentUpdateInterval = MIN_WATER_UPDATE_INTERVAL;
        }
    }
    
    /**
     * Processes water flow using batch processing for simultaneous updates.
     */
    private void processBatchedWaterFlow() {
        World world = Game.getWorld();
        if (world == null) return;
        
        // Clear batch data and cache
        currentBatch.clear();
        batchFlows.clear();
        processedInBatch.clear();
        neighborLevelCache.clear();
        cacheValidPositions.clear();
        
        // Pre-populate neighbor level cache for efficient bulk calculations
        populateNeighborLevelCache();
        
        // Calculate dynamic batch size
        int batchSize = Math.min(MAX_BATCH_SIZE, Math.max(BASE_BATCH_SIZE, waterBlocks.size()));
        
        // Process all pending flows first (apply previously queued flows)
        processPendingFlowsBatch(world);
        
        // Build batch of water blocks to update
        buildUpdateBatch(batchSize);
        
        // Process the entire batch simultaneously
        processWaterBatch(world);
        
        // Generate new flows from all water sources and high-level flowing water simultaneously
        generateNewFlows(world);
        
        // Apply batch flows
        applyBatchFlows(world);
    }
    
    /**
     * Processes all pending flows in batches.
     */
    private void processPendingFlowsBatch(World world) {
        List<PendingFlow> flowsToProcess = new ArrayList<>();
        
        // Collect all pending flows
        while (!pendingFlows.isEmpty()) {
            flowsToProcess.add(pendingFlows.poll());
        }
        
        // Process flows in batches to avoid overwhelming the system
        int batchSize = Math.max(10, flowsToProcess.size() / 3); // Process in thirds
        
        for (int i = 0; i < Math.min(batchSize, flowsToProcess.size()); i++) {
            PendingFlow flow = flowsToProcess.get(i);
            if (canWaterFlowTo(flow.toPos, flow.level, world)) {
                addOrUpdateWaterBlock(flow.toPos, flow.level, world);
            }
        }
        
        // Re-queue remaining flows for next update
        for (int i = batchSize; i < flowsToProcess.size(); i++) {
            pendingFlows.offer(flowsToProcess.get(i));
        }
    }
    
    /**
     * Builds a batch of water blocks to update.
     */
    private void buildUpdateBatch(int batchSize) {
        // Add queued updates first
        while (!waterUpdateQueue.isEmpty() && currentBatch.size() < batchSize) {
            Vector3i pos = waterUpdateQueue.poll();
            if (!processedInBatch.contains(pos)) {
                currentBatch.add(pos);
                processedInBatch.add(pos);
            }
        }
        
        // Fill remaining slots with water blocks that need regular updates
        List<Vector3i> allWaterPositions = new ArrayList<>(waterBlocks.keySet());
        for (Vector3i pos : allWaterPositions) {
            if (currentBatch.size() >= batchSize) break;
            if (!processedInBatch.contains(pos)) {
                currentBatch.add(pos);
                processedInBatch.add(pos);
            }
        }
    }
    
    /**
     * Processes all water blocks in the current batch simultaneously.
     */
    private void processWaterBatch(World world) {
        for (Vector3i pos : currentBatch) {
            updateWaterBlock(pos, world);
        }
    }
    
    /**
     * Generates new flows from all water sources and flowing water simultaneously.
     */
    private void generateNewFlows(World world) {
        // Process all water sources simultaneously
        for (Vector3i sourcePos : waterSources) {
            WaterBlock sourceBlock = waterBlocks.get(sourcePos);
            if (sourceBlock != null) {
                // Ensure source maintains max level
                sourceBlock.level = MAX_WATER_LEVEL;
                generateFlowsFrom(sourcePos, world);
            }
        }
        
        // Process high-level flowing water simultaneously
        for (Map.Entry<Vector3i, WaterBlock> entry : waterBlocks.entrySet()) {
            Vector3i pos = entry.getKey();
            WaterBlock waterBlock = entry.getValue();
            
            // Allow flowing water with level >= 3 to continue spreading
            if (!waterSources.contains(pos) && waterBlock.level >= 3) {
                generateFlowsFrom(pos, world);
            }
        }
    }
    
    /**
     * Applies all flows generated in the current batch.
     */
    private void applyBatchFlows(World world) {
        // Sort flows by priority (downward flows first, then by level)
        batchFlows.sort((a, b) -> {
            // Prioritize downward flows
            boolean aIsDown = a.toPos.y < a.fromPos.y;
            boolean bIsDown = b.toPos.y < b.fromPos.y;
            
            if (aIsDown && !bIsDown) return -1;
            if (!aIsDown && bIsDown) return 1;
            
            // Then prioritize by level
            return Integer.compare(b.level, a.level);
        });
        
        // Apply flows
        for (PendingFlow flow : batchFlows) {
            if (canWaterFlowTo(flow.toPos, flow.level, world)) {
                addOrUpdateWaterBlock(flow.toPos, flow.level, world, flow.isWaterfall);
            }
        }
    }
    
    /**
     * Updates a single water block and propagates flow to neighbors.
     */
    private void updateWaterBlock(Vector3i pos, World world) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) return;
        
        // Check if this position still has water in the world
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        if (blockType != BlockType.WATER) {
            // Water block was removed, clean up
            waterBlocks.remove(pos);
            waterSources.remove(pos);
            return;
        }
        
        // Water sources maintain full level and spread
        if (waterSources.contains(pos)) {
            waterBlock.level = MAX_WATER_LEVEL;
            spreadWaterFrom(pos, world);
            return;
        }
        
        // For flowing water, check if it should still exist
        // Flowing water should persist if it has a source nearby
        boolean shouldExist = hasValidWaterSource(pos, world);
        
        if (!shouldExist) {
            // This water block should disappear - no valid source
            removeWaterBlock(pos, world);
        } else {
            // Calculate proper level based on distance from nearest source
            int newLevel = calculateWaterLevel(pos, world);
            if (newLevel != waterBlock.level) {
                waterBlock.level = newLevel;
            }
            // Always try to spread if level is high enough
            if (waterBlock.level > 1) {
                spreadWaterFrom(pos, world);
            }
        }
    }
    
    /**
     * Generates flows from a position and adds them to the batch.
     */
    private void generateFlowsFrom(Vector3i pos, World world) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) return;
        
        int currentLevel = waterBlock.level;
        
        // Priority 1: Check for infinite downward flow (waterfall behavior)
        Vector3i fallTarget = findWaterFallTarget(pos, world);
        if (fallTarget != null) {
            // Water falling maintains full level and resets flow distance
            batchFlows.add(new PendingFlow(pos, fallTarget, currentLevel, true));
            return; // Don't spread horizontally if we can fall
        }
        
        // Priority 2: Check immediate downward flow
        Vector3i downPos = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canWaterFlowTo(downPos, currentLevel, world)) {
            // Handle flowing into existing water vs flowing to air
            BlockType belowBlock = world.getBlockAt(downPos.x, downPos.y, downPos.z);
            if (belowBlock == BlockType.WATER) {
                // Flowing into existing water - merge and stop horizontal spread
                WaterBlock existingWater = waterBlocks.get(downPos);
                if (existingWater != null && currentLevel > existingWater.level) {
                    batchFlows.add(new PendingFlow(pos, downPos, Math.max(currentLevel, existingWater.level)));
                }
                return; // Don't spread horizontally when flowing into water
            } else {
                // Flowing to air - normal downward flow
                batchFlows.add(new PendingFlow(pos, downPos, currentLevel));
                return; // Don't spread horizontally if we can flow down
            }
        }
        
        // Priority 3: Check for nearest step down within 3-block radius before horizontal spreading
        Vector3i stepDownTarget = findNearestStepDown(pos, world);
        if (stepDownTarget != null && (currentLevel >= 4 || waterSources.contains(pos))) {
            // Water should flow towards the step down
            Vector3i pathToStepDown = findPathToStepDown(pos, stepDownTarget, world);
            if (pathToStepDown != null) {
                int newLevel;
                if (waterSources.contains(pos)) {
                    newLevel = Math.max(5, currentLevel - 1); // Sources maintain high levels for reaching step downs
                } else {
                    newLevel = Math.max(3, currentLevel - 1); // Regular flow towards step down
                }
                
                if (canWaterFlowToHorizontally(pathToStepDown, newLevel, world)) {
                    batchFlows.add(new PendingFlow(pos, pathToStepDown, newLevel));
                    return; // Prioritize step down over normal horizontal spread
                }
            }
        }
        
        // Priority 4: Spread horizontally if we cannot flow down or reach step down
        // Water sources and high-level water should always try to spread
        if (currentLevel >= 3 || waterSources.contains(pos)) {
            // Check all horizontal neighbors and add them to batch flows
            Vector3i[] directions = {
                new Vector3i(1, 0, 0),   // East
                new Vector3i(-1, 0, 0),  // West
                new Vector3i(0, 0, 1),   // South
                new Vector3i(0, 0, -1)   // North
            };
            
            for (Vector3i dir : directions) {
                Vector3i neighborPos = new Vector3i(pos.x + dir.x, pos.y + dir.y, pos.z + dir.z);
                
                // Water sources spread with high levels, others decrease
                int newLevel;
                if (waterSources.contains(pos)) {
                    newLevel = Math.max(4, currentLevel - 1); // Sources maintain high levels
                } else {
                    newLevel = Math.max(2, currentLevel - 1); // Regular flow
                }
                
                if (canWaterFlowToHorizontally(neighborPos, newLevel, world)) {
                    batchFlows.add(new PendingFlow(pos, neighborPos, newLevel));
                }
            }
        }
    }
    
    /**
     * Legacy method for compatibility - now redirects to generateFlowsFrom.
     */
    private void queueWaterFlowsFrom(Vector3i pos, World world) {
        generateFlowsFrom(pos, world);
    }
    
    /**
     * Legacy method for immediate spreading (used in specific cases).
     */
    private void spreadWaterFrom(Vector3i pos, World world) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) return;
        
        int currentLevel = waterBlock.level;
        
        // Water sources always spread, others need sufficient level
        if (waterSources.contains(pos) || currentLevel >= 3) {
            queueWaterFlowsFrom(pos, world);
        }
    }
    
    /**
     * Finds the nearest step down within a 3-block radius.
     * Returns the position where water can step down, or null if none found.
     */
    private Vector3i findNearestStepDown(Vector3i pos, World world) {
        Vector3i bestStepDown = null;
        int shortestDistance = Integer.MAX_VALUE;
        
        // Search in a 3-block radius around the position
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue; // Skip the current position
                
                Vector3i checkPos = new Vector3i(pos.x + dx, pos.y, pos.z + dz);
                
                // Check if this position can receive water (is air or lower-level water)
                BlockType currentBlock = world.getBlockAt(checkPos.x, checkPos.y, checkPos.z);
                if (currentBlock != BlockType.AIR && currentBlock != BlockType.WATER) {
                    continue; // Can't flow to solid block
                }
                
                // Check if there's a step down here (solid block below)
                Vector3i belowPos = new Vector3i(checkPos.x, checkPos.y - 1, checkPos.z);
                BlockType belowBlock = world.getBlockAt(belowPos.x, belowPos.y, belowPos.z);
                
                if (belowBlock != BlockType.AIR && belowBlock != BlockType.WATER) {
                    // Found a step down - calculate distance
                    int distance = Math.abs(dx) + Math.abs(dz);
                    if (distance < shortestDistance) {
                        shortestDistance = distance;
                        bestStepDown = checkPos;
                    }
                } else {
                    // Check if there's a deeper step down (water can flow further down)
                    Vector3i deeperPos = new Vector3i(checkPos.x, checkPos.y - 1, checkPos.z);
                    while (deeperPos.y >= 0) {
                        BlockType deepBlock = world.getBlockAt(deeperPos.x, deeperPos.y, deeperPos.z);
                        if (deepBlock == BlockType.AIR) {
                            deeperPos.y--; // Continue checking down
                        } else if (deepBlock == BlockType.WATER) {
                            // Found water below - this could be a good step down
                            int distance = Math.abs(dx) + Math.abs(dz);
                            if (distance < shortestDistance) {
                                shortestDistance = distance;
                                bestStepDown = new Vector3i(deeperPos.x, deeperPos.y + 1, deeperPos.z);
                            }
                            break;
                        } else {
                            // Found solid ground - this is a step down
                            Vector3i landingPos = new Vector3i(deeperPos.x, deeperPos.y + 1, deeperPos.z);
                            BlockType landingBlock = world.getBlockAt(landingPos.x, landingPos.y, landingPos.z);
                            if (landingBlock == BlockType.AIR) {
                                int distance = Math.abs(dx) + Math.abs(dz);
                                if (distance < shortestDistance) {
                                    shortestDistance = distance;
                                    bestStepDown = landingPos;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }
        
        return bestStepDown;
    }
    
    /**
     * Finds the next position on the path towards a step down target.
     * Returns the immediate next position to move towards the step down.
     */
    private Vector3i findPathToStepDown(Vector3i from, Vector3i stepDownTarget, World world) {
        if (stepDownTarget == null) return null;
        
        // Calculate direction towards step down
        int dx = stepDownTarget.x - from.x;
        int dz = stepDownTarget.z - from.z;
        
        // Normalize to get the immediate next step
        int stepX = 0;
        int stepZ = 0;
        
        if (dx != 0) {
            stepX = dx > 0 ? 1 : -1;
        }
        if (dz != 0) {
            stepZ = dz > 0 ? 1 : -1;
        }
        
        // Try direct diagonal path first
        if (stepX != 0 && stepZ != 0) {
            Vector3i diagonalStep = new Vector3i(from.x + stepX, from.y, from.z + stepZ);
            BlockType blockType = world.getBlockAt(diagonalStep.x, diagonalStep.y, diagonalStep.z);
            if (blockType == BlockType.AIR || 
                (blockType == BlockType.WATER && canReceiveHigherLevelWater(diagonalStep))) {
                return diagonalStep;
            }
        }
        
        // Try X direction first
        if (stepX != 0) {
            Vector3i xStep = new Vector3i(from.x + stepX, from.y, from.z);
            BlockType blockType = world.getBlockAt(xStep.x, xStep.y, xStep.z);
            if (blockType == BlockType.AIR || 
                (blockType == BlockType.WATER && canReceiveHigherLevelWater(xStep))) {
                return xStep;
            }
        }
        
        // Try Z direction
        if (stepZ != 0) {
            Vector3i zStep = new Vector3i(from.x, from.y, from.z + stepZ);
            BlockType blockType = world.getBlockAt(zStep.x, zStep.y, zStep.z);
            if (blockType == BlockType.AIR || 
                (blockType == BlockType.WATER && canReceiveHigherLevelWater(zStep))) {
                return zStep;
            }
        }
        
        return null; // No valid path found
    }
    
    /**
     * Checks if a water position can receive higher level water.
     */
    private boolean canReceiveHigherLevelWater(Vector3i pos) {
        WaterBlock existingWater = waterBlocks.get(pos);
        return existingWater == null || existingWater.level < 6; // Can receive water if level is not maxed
    }
    
    /**
     * Finds the target position for a waterfall (infinite downward flow).
     * Returns null if no valid fall target exists.
     */
    private Vector3i findWaterFallTarget(Vector3i pos, World world) {
        // Check if there's open air below for falling
        Vector3i currentPos = new Vector3i(pos.x, pos.y - 1, pos.z);
        
        // Scan downward until we hit a non-air block or reach bottom
        while (currentPos.y >= 0) {
            BlockType blockType = world.getBlockAt(currentPos.x, currentPos.y, currentPos.z);
            
            if (blockType == BlockType.AIR) {
                // Continue falling through air
                currentPos.y--;
            } else if (blockType == BlockType.WATER) {
                // Hit existing water - merge into it if we're higher level
                WaterBlock existingWater = waterBlocks.get(currentPos);
                if (existingWater != null) {
                    return currentPos; // Fall into existing water
                }
                return null; // Can't determine water level, don't fall
            } else {
                // Hit solid block - fall to the air space above it
                Vector3i landingPos = new Vector3i(currentPos.x, currentPos.y + 1, currentPos.z);
                BlockType landingBlock = world.getBlockAt(landingPos.x, landingPos.y, landingPos.z);
                if (landingBlock == BlockType.AIR) {
                    return landingPos;
                }
                return null; // No valid landing spot
            }
        }
        
        return null; // Reached bottom of world
    }
    
    /**
     * Checks if water can flow horizontally to a position.
     */
    private boolean canWaterFlowToHorizontally(Vector3i pos, int flowLevel, World world) {
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        
        // Can flow to air
        if (blockType == BlockType.AIR) {
            return true;
        }
        
        // Can flow to existing water if the flow level would be higher or equal (for leveling)
        if (blockType == BlockType.WATER) {
            WaterBlock existingWater = waterBlocks.get(pos);
            if (existingWater != null) {
                // Allow flow if new level is higher, or if levels are close for equalization
                return flowLevel > existingWater.level || 
                       (Math.abs(flowLevel - existingWater.level) <= 1 && flowLevel >= existingWater.level);
            }
            // If no water block data exists, allow flow (shouldn't happen but safe fallback)
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if water at a given level can flow to a position.
     */
    private boolean canWaterFlowTo(Vector3i pos, int flowLevel, World world) {
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        
        // Can flow to air
        if (blockType == BlockType.AIR) {
            return true;
        } else if (blockType == BlockType.WATER) {
            // Can flow to existing water if flow level is higher (for downward flow merging)
            WaterBlock existingWater = waterBlocks.get(pos);
            return existingWater != null && flowLevel > existingWater.level;
        }
        
        return false;
    }
    
    /**
     * Adds or updates a water block at the given position.
     */
    private void addOrUpdateWaterBlock(Vector3i pos, int level, World world) {
        addOrUpdateWaterBlock(pos, level, world, false);
    }
    
    /**
     * Adds or updates a water block at the given position with waterfall flag.
     */
    private void addOrUpdateWaterBlock(Vector3i pos, int level, World world, boolean isWaterfallLanding) {
        WaterBlock existingWater = waterBlocks.get(pos);
        
        if (existingWater == null) {
            // Create new water block
            world.setBlockAt(pos.x, pos.y, pos.z, BlockType.WATER);
            WaterBlock newWater = new WaterBlock(level, false);
            newWater.isWaterfallLanding = isWaterfallLanding;
            
            if (isWaterfallLanding) {
                // Reset flow distance for waterfall landings
                newWater.distanceFromSource = 0;
            } else {
                // Calculate distance with proper reset logic
                newWater.distanceFromSource = calculateFlowDistanceFromContext(pos, world);
            }
            
            waterBlocks.put(new Vector3i(pos), newWater);
            waterUpdateQueue.offer(new Vector3i(pos));
        } else if (level > existingWater.level) {
            // Update existing water block with higher level
            existingWater.level = level;
            
            if (isWaterfallLanding) {
                existingWater.isWaterfallLanding = true;
                existingWater.distanceFromSource = 0; // Reset distance for waterfall
            } else {
                // Recalculate distance with proper reset logic
                existingWater.distanceFromSource = calculateFlowDistanceFromContext(pos, world);
            }
            
            waterUpdateQueue.offer(new Vector3i(pos));
        }
    }
    
    /**
     * Calculates flow distance based on flow context (checks if flowing from above to reset distance).
     */
    private int calculateFlowDistanceFromContext(Vector3i pos, World world) {
        // Check if water is flowing from above (which should reset distance)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        WaterBlock aboveWater = waterBlocks.get(abovePos);
        
        if (aboveWater != null && aboveWater.level > 2) {
            // Water flowing down resets horizontal distance but adds 1 for vertical flow
            return 1; // Fresh start for horizontal flow
        }
        
        // Check horizontal neighbors for distance context
        Vector3i[] horizontalDirections = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };
        
        int minNeighborDistance = Integer.MAX_VALUE;
        for (Vector3i dir : horizontalDirections) {
            Vector3i neighborPos = new Vector3i(pos.x + dir.x, pos.y + dir.y, pos.z + dir.z);
            WaterBlock neighborWater = waterBlocks.get(neighborPos);
            if (neighborWater != null) {
                minNeighborDistance = Math.min(minNeighborDistance, neighborWater.distanceFromSource);
            }
        }
        
        if (minNeighborDistance != Integer.MAX_VALUE) {
            // Distance is neighbor distance + 1 (for horizontal flow)
            return Math.min(MAX_FLOW_DISTANCE, minNeighborDistance + 1);
        }
        
        // Fallback to full distance calculation
        return calculateDistanceFromNearestSource(pos);
    }
    
    /**
     * Removes a water block from the simulation and world.
     */
    private void removeWaterBlock(Vector3i pos, World world) {
        waterBlocks.remove(pos);
        waterSources.remove(pos);
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR);
        
        // Queue neighbor updates
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1),
            new Vector3i(0, 1, 0), new Vector3i(0, -1, 0)
        };
        
        for (Vector3i dir : directions) {
            Vector3i neighborPos = new Vector3i(pos.x + dir.x, pos.y + dir.y, pos.z + dir.z);
            if (waterBlocks.containsKey(neighborPos)) {
                waterUpdateQueue.offer(neighborPos);
            }
        }
    }
    
    /**
     * Checks if this water block has a valid water source within flow distance (with distance reset on vertical drops).
     */
    private boolean hasValidWaterSource(Vector3i pos, World world) {
        // Use BFS to find nearest water source with distance reset on vertical flow
        Queue<FlowPathNode> queue = new LinkedList<>();
        Set<Vector3i> visited = new HashSet<>();
        
        queue.offer(new FlowPathNode(pos, 0, 0));
        visited.add(pos);
        
        Vector3i[] horizontalDirections = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };
        
        while (!queue.isEmpty()) {
            FlowPathNode current = queue.poll();
            
            // Check if this is a water source
            if (waterSources.contains(current.pos)) {
                return true;
            }
            
            // If horizontal distance reached limit, only check upward (which can reset distance)
            if (current.horizontalDistance >= MAX_FLOW_DISTANCE) {
                Vector3i upPos = new Vector3i(current.pos.x, current.pos.y + 1, current.pos.z);
                if (!visited.contains(upPos) && waterBlocks.containsKey(upPos)) {
                    visited.add(upPos);
                    // Reset distance when going up (reverse of downward flow reset)
                    queue.offer(new FlowPathNode(upPos, 0, current.level + 1));
                }
                continue;
            }
            
            // Check horizontal neighbors
            for (Vector3i dir : horizontalDirections) {
                Vector3i neighbor = new Vector3i(current.pos.x + dir.x, current.pos.y + dir.y, current.pos.z + dir.z);
                if (!visited.contains(neighbor) && waterBlocks.containsKey(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(new FlowPathNode(neighbor, current.horizontalDistance + 1, current.level));
                }
            }
            
            // Check upward neighbor (can reset distance)
            Vector3i upPos = new Vector3i(current.pos.x, current.pos.y + 1, current.pos.z);
            if (!visited.contains(upPos) && waterBlocks.containsKey(upPos)) {
                visited.add(upPos);
                // Reset distance when going up (water can flow down from here)
                queue.offer(new FlowPathNode(upPos, 0, current.level + 1));
            }
        }
        
        return false;
    }
    
    /**
     * Calculates the distance from the nearest water source with flow distance reset on vertical drops.
     */
    private int calculateDistanceFromNearestSource(Vector3i pos) {
        int minDistance = Integer.MAX_VALUE;
        
        for (Vector3i sourcePos : waterSources) {
            int flowDistance = calculateFlowDistanceWithReset(sourcePos, pos);
            minDistance = Math.min(minDistance, flowDistance);
        }
        
        return minDistance == Integer.MAX_VALUE ? MAX_FLOW_DISTANCE : minDistance;
    }
    
    /**
     * Calculates flow distance with resets when water flows down.
     */
    private int calculateFlowDistanceWithReset(Vector3i sourcePos, Vector3i targetPos) {
        // Use BFS to find shortest path with distance resets on downward flow
        Queue<FlowPathNode> queue = new LinkedList<>();
        Set<Vector3i> visited = new HashSet<>();
        
        queue.offer(new FlowPathNode(sourcePos, 0, 0)); // position, horizontal distance, current level
        visited.add(sourcePos);
        
        Vector3i[] horizontalDirections = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };
        
        while (!queue.isEmpty()) {
            FlowPathNode current = queue.poll();
            
            if (current.pos.equals(targetPos)) {
                return current.horizontalDistance;
            }
            
            // If horizontal distance reached limit, stop expanding horizontally
            if (current.horizontalDistance >= MAX_FLOW_DISTANCE) {
                // But still allow downward flow which resets distance
                Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
                if (!visited.contains(downPos) && waterBlocks.containsKey(downPos)) {
                    visited.add(downPos);
                    // Reset horizontal distance when flowing down
                    queue.offer(new FlowPathNode(downPos, 0, current.level + 1));
                }
                continue;
            }
            
            // Check horizontal neighbors
            for (Vector3i dir : horizontalDirections) {
                Vector3i neighbor = new Vector3i(current.pos.x + dir.x, current.pos.y + dir.y, current.pos.z + dir.z);
                if (!visited.contains(neighbor) && waterBlocks.containsKey(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(new FlowPathNode(neighbor, current.horizontalDistance + 1, current.level));
                }
            }
            
            // Check downward neighbor (resets horizontal distance)
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            if (!visited.contains(downPos) && waterBlocks.containsKey(downPos)) {
                visited.add(downPos);
                // Reset horizontal distance when flowing down
                queue.offer(new FlowPathNode(downPos, 0, current.level + 1));
            }
        }
        
        return MAX_FLOW_DISTANCE; // No path found
    }
    
    /**
     * Helper class for flow path calculation with distance reset.
     */
    private static class FlowPathNode {
        Vector3i pos;
        int horizontalDistance;
        int level;
        
        FlowPathNode(Vector3i pos, int horizontalDistance, int level) {
            this.pos = new Vector3i(pos);
            this.horizontalDistance = horizontalDistance;
            this.level = level;
        }
    }
    
    /**
     * Calculates the appropriate water level for a position based on nearest water source.
     */
    private int calculateWaterLevel(Vector3i pos, World world) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        
        if (waterBlock != null && waterBlock.isWaterfallLanding) {
            // Waterfall landings maintain higher levels regardless of distance
            return Math.max(MAX_WATER_LEVEL - 1, waterBlock.level);
        }
        
        // Check for water above (flowing down maintains higher level)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        WaterBlock aboveWater = waterBlocks.get(abovePos);
        if (aboveWater != null && aboveWater.level > 2) {
            // Water flowing down maintains most of its level
            return Math.max(aboveWater.level - 1, 4);
        }
        
        // Check for adjacent water sources for better flow spreading
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1),
            new Vector3i(0, 1, 0)  // Include above
        };
        
        int maxAdjacentLevel = 0;
        for (Vector3i dir : directions) {
            Vector3i adjacentPos = new Vector3i(pos.x + dir.x, pos.y + dir.y, pos.z + dir.z);
            if (waterSources.contains(adjacentPos)) {
                return 6; // High level near sources
            }
            WaterBlock adjacentWater = waterBlocks.get(adjacentPos);
            if (adjacentWater != null) {
                maxAdjacentLevel = Math.max(maxAdjacentLevel, adjacentWater.level);
            }
        }
        
        // If near high-level water, maintain good level
        if (maxAdjacentLevel >= 5) {
            return Math.max(4, maxAdjacentLevel - 1);
        }
        
        // Use distance calculation as fallback
        int distance = waterBlock != null ? waterBlock.distanceFromSource : calculateDistanceFromNearestSource(pos);
        
        // Very generous level calculation for wide spreading
        int calculatedLevel = Math.max(3, MAX_WATER_LEVEL - distance);
        
        // Ensure good flowing level
        return Math.max(3, calculatedLevel);
    }
    
    /**
     * Populates the neighbor level cache for efficient bulk calculations.
     */
    private void populateNeighborLevelCache() {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1),
            new Vector3i(0, 1, 0)  // Include above for falling water
        };
        
        // Calculate neighbor levels for all water blocks
        for (Vector3i pos : waterBlocks.keySet()) {
            int maxLevel = 0;
            
            for (Vector3i dir : directions) {
                Vector3i neighborPos = new Vector3i(pos.x + dir.x, pos.y + dir.y, pos.z + dir.z);
                WaterBlock neighborWater = waterBlocks.get(neighborPos);
                
                if (neighborWater != null) {
                    maxLevel = Math.max(maxLevel, neighborWater.level);
                }
            }
            
            neighborLevelCache.put(new Vector3i(pos), maxLevel);
            cacheValidPositions.add(new Vector3i(pos));
        }
    }
    
    /**
     * Adds a water source block at the specified position.
     */
    public void addWaterSource(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) return;
        
        Vector3i pos = new Vector3i(x, y, z);
        world.setBlockAt(x, y, z, BlockType.WATER);
        
        waterBlocks.put(pos, new WaterBlock(MAX_WATER_LEVEL, true));
        waterSources.add(pos);
        waterUpdateQueue.offer(pos);
    }
    
    /**
     * Removes a water source and all flowing water connected to it.
     */
    public void removeWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        waterSources.remove(pos);
        
        // Queue this position and all water blocks for update
        waterUpdateQueue.offer(pos);
        waterUpdateQueue.addAll(waterBlocks.keySet());
    }
    
    /**
     * Detects existing water blocks in the world and registers them appropriately.
     * This is useful for initializing the water simulation with pre-existing water.
     */
    public void detectExistingWater() {
        World world = Game.getWorld();
        if (world == null) return;
        
        Player player = Game.getPlayer();
        if (player == null) return;
        
        // Check a reasonable area around the player for existing water
        int playerX = (int) player.getPosition().x;
        int playerY = (int) player.getPosition().y;
        int playerZ = (int) player.getPosition().z;
        
        int range = 50; // Check 50 blocks in each direction
        
        for (int x = playerX - range; x <= playerX + range; x++) {
            for (int y = Math.max(0, playerY - range); y <= Math.min(World.WORLD_HEIGHT - 1, playerY + range); y++) {
                for (int z = playerZ - range; z <= playerZ + range; z++) {
                    if (world.getBlockAt(x, y, z) == BlockType.WATER) {
                        Vector3i pos = new Vector3i(x, y, z);
                        if (!waterBlocks.containsKey(pos)) {
                            // Assume existing water blocks are sources for now
                            // In a more sophisticated system, we could analyze connectivity
                            addWaterSource(x, y, z);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Gets detailed information about water blocks for debugging.
     */
    public String getWaterInfo() {
        return String.format("Water simulation: %d sources, %d total blocks, %d queued updates, interval: %.3fs, batch size: %d", 
                           waterSources.size(), waterBlocks.size(), waterUpdateQueue.size(), currentUpdateInterval,
                           Math.min(MAX_BATCH_SIZE, Math.max(BASE_BATCH_SIZE, waterBlocks.size())));
    }
    
    /**
     * Gets performance statistics for the water system.
     */
    public String getWaterPerformanceInfo() {
        int currentBatchSize = Math.min(MAX_BATCH_SIZE, Math.max(BASE_BATCH_SIZE, waterBlocks.size()));
        String systemSize = waterBlocks.size() <= 10 ? "Small" : 
                           waterBlocks.size() <= 50 ? "Medium" : "Large";
        
        return String.format("%s water system: %d blocks, %.3fs intervals, %d batch size", 
                           systemSize, waterBlocks.size(), currentUpdateInterval, currentBatchSize);
    }
    
    /**
     * Gets the water level at a specific position.
     * @return Water level (0-7) or 0 if no water at position
     */
    public int getWaterLevel(int x, int y, int z) {
        WaterBlock waterBlock = waterBlocks.get(new Vector3i(x, y, z));
        return waterBlock != null ? waterBlock.level : 0;
    }
    
    /**
     * Gets the visual height for a water block at a specific position.
     * @return Height from 0.0 to 1.0, or 1.0 if no water block found
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        WaterBlock waterBlock = waterBlocks.get(new Vector3i(x, y, z));
        if (waterBlock != null) {
            return waterBlock.getVisualHeight();
        }
        return 1.0f; // Default to full height if not in simulation (shouldn't happen)
    }
    
    /**
     * Checks if a position has a water source.
     */
    public boolean isWaterSource(int x, int y, int z) {
        return waterSources.contains(new Vector3i(x, y, z));
    }
    
    /**
     * Represents a pending water flow to be processed gradually.
     */
    private static class PendingFlow {
        public Vector3i fromPos;
        public Vector3i toPos;
        public int level;
        public boolean isWaterfall; // True if this is a waterfall (infinite downward flow)
        
        public PendingFlow(Vector3i fromPos, Vector3i toPos, int level) {
            this.fromPos = new Vector3i(fromPos);
            this.toPos = new Vector3i(toPos);
            this.level = level;
            this.isWaterfall = false;
        }
        
        public PendingFlow(Vector3i fromPos, Vector3i toPos, int level, boolean isWaterfall) {
            this.fromPos = new Vector3i(fromPos);
            this.toPos = new Vector3i(toPos);
            this.level = level;
            this.isWaterfall = isWaterfall;
        }
    }
    
    /**
     * Represents a water block with flow level and source status.
     */
    public static class WaterBlock {
        public int level; // 0-7, where 7 is full block
        public boolean isSource; // True if this is a water source block
        public int distanceFromSource; // Distance from nearest source (for flow calculation)
        public boolean isWaterfallLanding; // True if this water landed from a waterfall
        
        public WaterBlock(int level, boolean isSource) {
            this.level = level;
            this.isSource = isSource;
            this.distanceFromSource = isSource ? 0 : Integer.MAX_VALUE;
            this.isWaterfallLanding = false;
        }
        
        public WaterBlock(int level, boolean isSource, int distanceFromSource) {
            this.level = level;
            this.isSource = isSource;
            this.distanceFromSource = distanceFromSource;
            this.isWaterfallLanding = false;
        }
        
        // Add method to get visual height for rendering
        public float getVisualHeight() {
            // Water level 7 = full block (1.0), level 1 = 1/8 block height (0.125)
            // But ensure minimum visual height for gameplay
            return Math.max(0.125f, level / 7.0f);
        }
    }
    
    /**
     * Represents a single water particle.
     */
    public static class WaterParticle {
        private Vector3f position;
        private Vector3f velocity;
        private float lifetime;
        private float initialLifetime;
        
        public WaterParticle(Vector3f position, Vector3f velocity, float lifetime) {
            this.position = position;
            this.velocity = velocity;
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
        }
        
        public void update(float deltaTime) {
            // Update position based on velocity
            position.x += velocity.x * deltaTime;
            position.y += velocity.y * deltaTime;
            position.z += velocity.z * deltaTime;
            
            // Slow down over time
            velocity.mul(0.95f);
            
            // Reduce lifetime
            lifetime -= deltaTime;
        }
        
        public Vector3f getPosition() {
            return position;
        }
        
        public float getLifetime() {
            return lifetime;
        }
        
        public float getSize() {
            return PARTICLE_SIZE;
        }
        
        /**
         * Gets the opacity of the particle based on its remaining lifetime.
         * Fades out as lifetime decreases.
         */
        public float getOpacity() {
            return lifetime / initialLifetime;
        }
    }
}
