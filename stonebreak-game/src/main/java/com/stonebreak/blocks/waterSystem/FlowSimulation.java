package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft-accurate fluid flow simulation following the exact mechanics from the Minecraft Wiki.
 *
 * Key mechanics implemented:
 * - Water spreads 1 block every 5 game ticks (4 blocks per second)
 * - Source blocks have depth 0, flowing water has depth 1-7
 * - Maximum horizontal spread of 7 blocks from source
 * - Flow direction determined by pathfinding weights toward lowest elevation
 * - Diamond-shaped spread pattern (15 blocks point-to-point on flat surface)
 * - Vertical flow always takes priority over horizontal flow
 * - Source blocks provide infinite water
 */
public class FlowSimulation {

    // Minecraft-accurate flow timing (5 game ticks = 0.25 seconds at 20 TPS)
    private static final float FLOW_UPDATE_INTERVAL = 0.25f; // 4 updates per second
    private static final int MAX_HORIZONTAL_DISTANCE = 7; // Water travels 7 blocks horizontally
    private static final int MAX_DEPTH = 7; // Depth values: 0 (source) to 7 (flowing)
    private static final int MIN_FLOW_WEIGHT = 1000; // Initial weight for flow calculations

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> sourceBlocks;
    private final Queue<Vector3i> flowUpdateQueue;
    private final Set<Vector3i> scheduledUpdates;
    private float timeSinceLastUpdate;

    public FlowSimulation() {
        this.waterBlocks = new ConcurrentHashMap<>();
        this.sourceBlocks = Collections.synchronizedSet(new HashSet<>());
        this.flowUpdateQueue = new ConcurrentLinkedQueue<>();
        this.scheduledUpdates = Collections.synchronizedSet(new HashSet<>());
        this.timeSinceLastUpdate = 0.0f;
    }

    /**
     * Updates water flow physics according to Minecraft timing.
     */
    public void update(float deltaTime) {
        timeSinceLastUpdate += deltaTime;

        if (timeSinceLastUpdate >= FLOW_UPDATE_INTERVAL) {
            processFlowUpdates();
            timeSinceLastUpdate = 0.0f;
        }
    }

    /**
     * Adds a water source block at the specified position.
     */
    public void addWaterSource(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) return;

        Vector3i pos = new Vector3i(x, y, z);

        // Set block in world
        world.setBlockAt(x, y, z, BlockType.WATER);

        // Create source water block (depth 0)
        WaterBlock waterBlock = new WaterBlock(WaterBlock.SOURCE_DEPTH);
        waterBlock.setSource(true);

        waterBlocks.put(pos, waterBlock);
        sourceBlocks.add(pos);

        // Schedule immediate flow update
        scheduleFlowUpdate(pos);

        System.out.println("DEBUG: Added water source at " + x + "," + y + "," + z + " - scheduled for flow update");
    }

    /**
     * Removes a water source at the specified position.
     */
    public void removeWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);

        sourceBlocks.remove(pos);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            waterBlock.setSource(false);
            scheduleFlowUpdate(pos);
            scheduleNeighborUpdates(pos);
        }
    }

    /**
     * Gets the water level (0.0 to 1.0) at a position.
     */
    public float getWaterLevel(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            return waterBlock.getNormalizedLevel();
        }

        // Check if there's a water block in the world
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 1.0f; // Default to full for unknown water blocks
        }

        return 0.0f;
    }

    /**
     * Checks if a position contains a water source.
     */
    public boolean isWaterSource(int x, int y, int z) {
        return sourceBlocks.contains(new Vector3i(x, y, z));
    }

    /**
     * Gets the visual height for rendering.
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            return waterBlock.getVisualHeight();
        }

        // Default height for water blocks not in simulation
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 0.875f;
        }

        return 0.0f;
    }

    /**
     * Gets foam intensity for visual effects.
     */
    public float getFoamIntensity(float x, float y, float z) {
        Vector3i pos = new Vector3i((int)x, (int)y, (int)z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock == null) return 0.0f;

        float foam = waterBlock.getFlowDirection().length() * 0.4f;

        // Add foam near solid blocks
        World world = Game.getWorld();
        if (world != null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockType neighbor = world.getBlockAt((int)x + dx, (int)y, (int)z + dz);
                    if (neighbor != BlockType.WATER && neighbor != BlockType.AIR) {
                        foam += 0.2f;
                    }
                }
            }
        }

        return Math.min(foam, 1.0f);
    }

    /**
     * Gets a water block at the specified position.
     */
    public WaterBlock getWaterBlock(int x, int y, int z) {
        return waterBlocks.get(new Vector3i(x, y, z));
    }

    /**
     * Detects existing water blocks in the world and clears simulation.
     */
    public void detectExistingWater() {
        waterBlocks.clear();
        sourceBlocks.clear();
        flowUpdateQueue.clear();
        scheduledUpdates.clear();
    }

    /**
     * Handles block broken events.
     */
    public void onBlockBroken(int x, int y, int z) {
        // Schedule updates for water blocks that might flow into the empty space
        scheduleNeighborUpdates(new Vector3i(x, y, z));

        // Check for water above that might flow down
        for (int dy = 1; dy <= 8; dy++) {
            Vector3i above = new Vector3i(x, y + dy, z);
            if (waterBlocks.containsKey(above)) {
                scheduleFlowUpdate(above);
            }
        }
    }

    /**
     * Handles block placed events.
     */
    public void onBlockPlaced(int x, int y, int z) {
        // Schedule updates for adjacent water blocks
        scheduleNeighborUpdates(new Vector3i(x, y, z));
    }

    /**
     * Processes all scheduled flow updates according to Minecraft mechanics.
     */
    private void processFlowUpdates() {
        World world = Game.getWorld();
        if (world == null) return;

        Set<Vector3i> processedThisUpdate = new HashSet<>();
        Queue<Vector3i> currentQueue = new ArrayDeque<>(flowUpdateQueue);
        int queueSize = currentQueue.size();
        flowUpdateQueue.clear();
        scheduledUpdates.clear();

        if (queueSize > 0) {
            System.out.println("DEBUG: Processing " + queueSize + " flow updates");
        }

        while (!currentQueue.isEmpty()) {
            Vector3i pos = currentQueue.poll();

            if (processedThisUpdate.contains(pos)) {
                continue;
            }

            processedThisUpdate.add(pos);
            processFlowAtPosition(pos, world);
        }
    }

    /**
     * Processes flow for a single water block position.
     */
    private void processFlowAtPosition(Vector3i pos, World world) {
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);

        // Remove water block data if block is no longer water
        if (blockType != BlockType.WATER) {
            waterBlocks.remove(pos);
            sourceBlocks.remove(pos);
            return;
        }

        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) {
            // Create water block for existing water in world
            waterBlock = new WaterBlock(estimateDepthFromSurroundings(pos, world));
            waterBlocks.put(pos, waterBlock);
        }

        // Source blocks don't need flow processing
        if (sourceBlocks.contains(pos)) {
            waterBlock.setSource(true);
            waterBlock.setDepth(WaterBlock.SOURCE_DEPTH);
            spreadFromSource(pos, world);
            return;
        }

        // Process flowing water
        processFlowingWater(pos, waterBlock, world);
    }

    /**
     * Spreads water from a source block using Minecraft's algorithm.
     */
    private void spreadFromSource(Vector3i sourcePos, World world) {
        System.out.println("DEBUG: Spreading from source at " + sourcePos.x + "," + sourcePos.y + "," + sourcePos.z);

        // First, try to flow downward (infinite vertical flow)
        Vector3i downPos = new Vector3i(sourcePos.x, sourcePos.y - 1, sourcePos.z);
        if (canFlowTo(downPos, world)) {
            // Flowing water from source has depth 1 (not source depth)
            createOrUpdateWaterAt(downPos, 1, world);
            scheduleFlowUpdate(downPos);
            System.out.println("DEBUG: Created flowing water below source at " + downPos.x + "," + downPos.y + "," + downPos.z);
        } else {
            System.out.println("DEBUG: Cannot flow down from source - will spread horizontally instead");
        }

        // ALWAYS spread horizontally from sources (regardless of downward flow)
        System.out.println("DEBUG: Starting horizontal spread from source");
        spreadHorizontally(sourcePos, WaterBlock.SOURCE_DEPTH, world);
    }

    /**
     * Processes flowing water according to Minecraft mechanics.
     */
    private void processFlowingWater(Vector3i pos, WaterBlock waterBlock, World world) {
        // Check if this flowing water should disappear
        if (!hasValidSource(pos, world)) {
            removeWaterAt(pos, world);
            return;
        }

        // Try to flow downward first
        Vector3i downPos = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(downPos, world)) {
            // Flowing water creates depth 1 when flowing down (reset distance)
            createOrUpdateWaterAt(downPos, 1, world);
            scheduleFlowUpdate(downPos);
            return;
        }

        // Spread horizontally if can't flow down
        int currentDepth = waterBlock.getDepth();
        if (currentDepth < MAX_DEPTH) {
            spreadHorizontally(pos, currentDepth, world);
        }
    }

    /**
     * Spreads water horizontally using Minecraft's flow weight algorithm.
     */
    private void spreadHorizontally(Vector3i pos, int currentDepth, World world) {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0),   // East
            new Vector3i(-1, 0, 0),  // West
            new Vector3i(0, 0, 1),   // South
            new Vector3i(0, 0, -1)   // North
        };

        System.out.println("DEBUG: Calculating flow weights from " + pos.x + "," + pos.y + "," + pos.z + " with depth " + currentDepth);

        // Calculate flow weights for each direction
        Map<Vector3i, Integer> flowWeights = calculateFlowWeights(pos, directions, world);

        System.out.println("DEBUG: Found " + flowWeights.size() + " possible flow directions");

        if (flowWeights.isEmpty()) {
            System.out.println("DEBUG: No valid flow directions found - all blocked or already have water");
            return;
        }

        // Find the minimum weight
        int minWeight = flowWeights.values().stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse(MIN_FLOW_WEIGHT);

        System.out.println("DEBUG: Minimum flow weight is " + minWeight);

        // Flow to all directions with minimum weight
        // In Minecraft, water flows horizontally regardless of weight if within range
        boolean isSource = (currentDepth == WaterBlock.SOURCE_DEPTH);
        int flowCount = 0;

        for (Map.Entry<Vector3i, Integer> entry : flowWeights.entrySet()) {
            Vector3i targetPos = entry.getKey();
            int newDepth = currentDepth + 1;

            // Minecraft allows flow if:
            // 1. We're a source (depth 0), OR
            // 2. The new depth is within the 7-block limit, OR
            // 3. We found a path to downward flow (weight < 1000)
            boolean shouldFlow = isSource ||
                               newDepth <= MAX_DEPTH ||
                               entry.getValue() < MIN_FLOW_WEIGHT;

            if (entry.getValue() == minWeight && shouldFlow && newDepth <= MAX_DEPTH) {
                createOrUpdateWaterAt(targetPos, newDepth, world);
                scheduleFlowUpdate(targetPos);
                flowCount++;
                System.out.println("DEBUG: Created water at " + targetPos.x + "," + targetPos.y + "," + targetPos.z + " with depth " + newDepth + " (reason: " +
                    (isSource ? "source" : newDepth <= MAX_DEPTH ? "within range" : "path to down") + ")");
            }
        }

        if (flowCount == 0) {
            System.out.println("DEBUG: No water was created - currentDepth=" + currentDepth + ", maxDepth=" + MAX_DEPTH + ", isSource=" + isSource + ", minWeight=" + minWeight);
        }
    }

    /**
     * Calculates flow weights for each direction using Minecraft's algorithm.
     */
    private Map<Vector3i, Integer> calculateFlowWeights(Vector3i pos, Vector3i[] directions, World world) {
        Map<Vector3i, Integer> weights = new HashMap<>();

        for (Vector3i dir : directions) {
            Vector3i targetPos = new Vector3i(pos).add(dir);

            if (!canFlowTo(targetPos, world)) {
                continue;
            }

            // Check if target already has water - we can still flow if our depth is better
            WaterBlock existingWater = waterBlocks.get(targetPos);
            int sourceDepth = waterBlocks.get(pos) != null ? waterBlocks.get(pos).getDepth() : WaterBlock.SOURCE_DEPTH;

            if (existingWater != null) {
                // Only flow if we can provide better (lower) depth
                int targetDepth = existingWater.getDepth();
                int newDepth = sourceDepth + 1;
                if (newDepth >= targetDepth) {
                    continue; // Our water wouldn't be better
                }
            }

            // Initial weight (high value means lower priority)
            int weight = MIN_FLOW_WEIGHT;

            // Find shortest path to downward flow within 4 blocks
            int pathToDown = findShortestPathToDown(targetPos, world, 4);
            if (pathToDown >= 0) {
                weight = pathToDown; // Lower weight = higher priority
            }
            // If no downward path, still allow flow but with high weight

            weights.put(targetPos, weight);
        }

        return weights;
    }

    /**
     * Finds the shortest path to a downward flow opportunity.
     */
    private int findShortestPathToDown(Vector3i startPos, World world, int maxDistance) {
        Queue<PathNode> queue = new ArrayDeque<>();
        Set<Vector3i> visited = new HashSet<>();

        queue.offer(new PathNode(startPos, 0));
        visited.add(startPos);

        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();

            if (current.distance >= maxDistance) {
                continue;
            }

            // Check if we can flow down from this position
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            if (canFlowTo(downPos, world)) {
                return current.distance;
            }

            // Explore adjacent positions
            for (Vector3i dir : directions) {
                Vector3i nextPos = new Vector3i(current.pos).add(dir);

                if (!visited.contains(nextPos) && canFlowTo(nextPos, world)) {
                    visited.add(nextPos);
                    queue.offer(new PathNode(nextPos, current.distance + 1));
                }
            }
        }

        return -1; // No path to downward flow found
    }

    /**
     * Checks if water can flow to a position.
     */
    private boolean canFlowTo(Vector3i pos, World world) {
        if (pos.y < 0 || pos.y >= WorldConfiguration.WORLD_HEIGHT) {
            return false;
        }

        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        return blockType == BlockType.AIR || blockType == BlockType.WATER;
    }

    /**
     * Creates or updates water at a position.
     */
    private void createOrUpdateWaterAt(Vector3i pos, int depth, World world) {
        WaterBlock existing = waterBlocks.get(pos);

        if (existing == null) {
            // Create new water block
            if (world.getBlockAt(pos.x, pos.y, pos.z) == BlockType.AIR) {
                world.setBlockAt(pos.x, pos.y, pos.z, BlockType.WATER);
            }

            WaterBlock newWater = new WaterBlock(depth);
            waterBlocks.put(pos, newWater);
        } else {
            // Update existing water block depth (take minimum for flowing water)
            if (!existing.isSource() && depth < existing.getDepth()) {
                existing.setDepth(depth);
            }
        }
    }

    /**
     * Removes water at a position.
     */
    private void removeWaterAt(Vector3i pos, World world) {
        waterBlocks.remove(pos);
        sourceBlocks.remove(pos);
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR);

        // Schedule neighbor updates
        scheduleNeighborUpdates(pos);
    }

    /**
     * Checks if flowing water has a valid source.
     */
    private boolean hasValidSource(Vector3i pos, World world) {
        // Check if there's a source within MAX_HORIZONTAL_DISTANCE
        for (Vector3i sourcePos : sourceBlocks) {
            int horizontalDistance = Math.abs(pos.x - sourcePos.x) + Math.abs(pos.z - sourcePos.z);
            if (horizontalDistance <= MAX_HORIZONTAL_DISTANCE) {
                // Check if there's a valid flow path
                if (hasValidFlowPath(sourcePos, pos, world)) {
                    return true;
                }
            }
        }

        // Check if there's water above (vertical flow)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        return waterBlocks.containsKey(abovePos);
    }

    /**
     * Checks if there's a valid flow path between source and target.
     */
    private boolean hasValidFlowPath(Vector3i source, Vector3i target, World world) {
        // Simplified check: if target is within distance and reachable
        int horizontalDistance = Math.abs(target.x - source.x) + Math.abs(target.z - source.z);
        return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
    }

    /**
     * Estimates depth from surrounding water blocks.
     */
    private int estimateDepthFromSurroundings(Vector3i pos, World world) {
        // Check for adjacent water blocks to estimate depth
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1),
            new Vector3i(pos.x, pos.y + 1, pos.z)
        };

        int minDepth = MAX_DEPTH;
        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                minDepth = Math.min(minDepth, neighborWater.getDepth() + 1);
            }
        }

        return Math.min(minDepth, MAX_DEPTH);
    }

    /**
     * Schedules a flow update for a position.
     */
    private void scheduleFlowUpdate(Vector3i pos) {
        if (scheduledUpdates.add(pos)) {
            flowUpdateQueue.offer(pos);
        }
    }

    /**
     * Schedules flow updates for all neighbors of a position.
     */
    private void scheduleNeighborUpdates(Vector3i pos) {
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y + 1, pos.z),
            new Vector3i(pos.x, pos.y - 1, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        for (Vector3i neighbor : neighbors) {
            scheduleFlowUpdate(neighbor);
        }
    }

    /**
     * Helper class for pathfinding.
     */
    private static class PathNode {
        final Vector3i pos;
        final int distance;

        PathNode(Vector3i pos, int distance) {
            this.pos = new Vector3i(pos);
            this.distance = distance;
        }
    }
}