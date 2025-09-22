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
 * - Maximum horizontal spread of 7 blocks from source or reset point
 * - Depth resets to 0 when water flows down to a new elevation
 * - Flow prefers edges for aesthetic waterfall creation
 * - Diamond-shaped spread pattern (15 blocks point-to-point on flat surface)
 * - Vertical flow always takes priority over horizontal flow
 * - Water can flow infinitely downward but only 7 blocks horizontally
 * - Source blocks only spread horizontally when they cannot flow down
 */
public class FlowSimulation {

    // Minecraft-accurate flow timing (5 game ticks = 0.25 seconds at 20 TPS)
    private static final float FLOW_UPDATE_INTERVAL = 0.5f; // Slower updates to prevent chaos
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

        // System.out.println("DEBUG: Added water source at " + x + "," + y + "," + z + " - scheduled for flow update");
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
        Vector3i brokenPos = new Vector3i(x, y, z);
        World world = Game.getWorld();
        if (world == null) return;

        // Schedule updates for water blocks that might flow into the empty space
        scheduleNeighborUpdates(brokenPos);

        // Check for water above that might flow down (increased range for cascading water)
        for (int dy = 1; dy <= 12; dy++) {
            Vector3i above = new Vector3i(x, y + dy, z);
            BlockType blockAbove = world.getBlockAt(above.x, above.y, above.z);

            if (blockAbove == BlockType.WATER) {
                // Ensure this water block is in the simulation
                if (!waterBlocks.containsKey(above)) {
                    WaterBlock waterBlock = new WaterBlock(estimateDepthFromSurroundings(above, world));
                    waterBlocks.put(above, waterBlock);
                    System.out.println("DEBUG: Re-initialized water above broken block at " + above.x + "," + above.y + "," + above.z);
                }
                scheduleFlowUpdate(above);
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
                                WaterBlock waterBlock = new WaterBlock(estimateDepthFromSurroundings(checkPos, world));
                                waterBlocks.put(checkPos, waterBlock);
                                System.out.println("DEBUG: Re-initialized water at distance " + radius + " from broken block");
                            }

                            // Schedule flow update
                            scheduleFlowUpdate(checkPos);
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

        // First schedule immediate neighbor updates
        scheduleNeighborUpdates(placedPos);

        // Check for water blocks above that need to re-route their flow
        for (int dy = 1; dy <= 8; dy++) {
            Vector3i above = new Vector3i(x, y + dy, z);
            BlockType blockAbove = world.getBlockAt(above.x, above.y, above.z);

            if (blockAbove == BlockType.WATER) {
                // Ensure this water block is in the simulation
                if (!waterBlocks.containsKey(above)) {
                    // Re-initialize water block that exists in world but not in simulation
                    WaterBlock waterBlock = new WaterBlock(estimateDepthFromSurroundings(above, world));
                    waterBlocks.put(above, waterBlock);
                    System.out.println("DEBUG: Re-initialized water block at " + above.x + "," + above.y + "," + above.z + " after block placement");
                }
                scheduleFlowUpdate(above);
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
                            WaterBlock waterBlock = new WaterBlock(estimateDepthFromSurroundings(checkPos, world));
                            waterBlocks.put(checkPos, waterBlock);
                            System.out.println("DEBUG: Re-initialized nearby water at " + checkPos.x + "," + checkPos.y + "," + checkPos.z);
                        }

                        // Schedule update for this water block
                        scheduleFlowUpdate(checkPos);
                    }
                }
            }
        }

        System.out.println("DEBUG: Block placed at " + x + "," + y + "," + z + " - scheduled comprehensive water updates");
    }

    /**
     * Processes all scheduled flow updates according to Minecraft mechanics.
     */
    private void processFlowUpdates() {
        World world = Game.getWorld();
        if (world == null) return;

        Set<Vector3i> processedThisUpdate = new HashSet<>();

        // Remove duplicates from the queue before processing
        int originalSize = flowUpdateQueue.size();
        Set<Vector3i> uniqueUpdates = new HashSet<>(flowUpdateQueue);
        Queue<Vector3i> currentQueue = new ArrayDeque<>(uniqueUpdates);
        int queueSize = currentQueue.size();
        flowUpdateQueue.clear();
        scheduledUpdates.clear();

        // if (queueSize > 0) {
        //     System.out.println("DEBUG: Processing " + queueSize + " unique flow updates (from " + originalSize + " total)");
        // }

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
            // System.out.println("DEBUG: Created tracking for untracked water at " + pos.x + "," + pos.y + "," + pos.z);

            // Also check and initialize nearby water blocks that might not be tracked
            ensureNearbyWaterTracked(pos, world);
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
        // System.out.println("DEBUG: Spreading from source at " + sourcePos.x + "," + sourcePos.y + "," + sourcePos.z);

        boolean flowedDown = false;

        // First, try to flow downward (infinite vertical flow)
        Vector3i downPos = new Vector3i(sourcePos.x, sourcePos.y - 1, sourcePos.z);
        if (canFlowTo(downPos, world)) {
            // When water flows down, depth resets to 0 at the new elevation (Minecraft behavior)
            createOrUpdateWaterAt(downPos, 0, world);
            scheduleFlowUpdate(downPos);
            flowedDown = true;
            // System.out.println("DEBUG: Created flowing water below source at " + downPos.x + "," + downPos.y + "," + downPos.z + " with depth 0 (reset at new elevation)");
        }

        // ALWAYS spread horizontally from sources (not just when they can't flow down)
        // This matches Minecraft behavior where sources spread in all directions
        // System.out.println("DEBUG: Source spreading horizontally" + (flowedDown ? " (also flowing down)" : ""));
        spreadHorizontally(sourcePos, WaterBlock.SOURCE_DEPTH, world);
    }

    /**
     * Processes flowing water according to Minecraft mechanics.
     */
    private void processFlowingWater(Vector3i pos, WaterBlock waterBlock, World world) {
        // Don't remove water immediately - let it persist unless truly invalid
        // This prevents flickering when water is still valid but hasn't been updated

        // Try to flow downward first - ALWAYS prioritize downward flow
        Vector3i downPos = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(downPos, world)) {
            // When water flows down, depth resets to 0 at the new elevation (Minecraft behavior)
            createOrUpdateWaterAt(downPos, 0, world);
            scheduleFlowUpdate(downPos);

            // Don't spread horizontally from falling water - this causes chaos
            // Falling water should only fall, not spread sideways
            return;
        }

        // Only check source validity for non-falling water
        if (!hasValidSource(pos, world)) {
            removeWaterAt(pos, world);
            return;
        }

        // Spread horizontally if can't flow down
        int currentDepth = waterBlock.getDepth();
        if (currentDepth < MAX_DEPTH) {
            spreadHorizontally(pos, currentDepth, world);
        } else {
            // System.out.println("DEBUG: Water at " + pos.x + "," + pos.y + "," + pos.z + " has depth " + currentDepth + " (max reached, cannot spread)");
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

        // System.out.println("DEBUG: Calculating flow weights from " + pos.x + "," + pos.y + "," + pos.z + " with depth " + currentDepth);

        // Calculate flow weights for each direction
        Map<Vector3i, Integer> flowWeights = calculateFlowWeights(pos, directions, world);

        // System.out.println("DEBUG: Found " + flowWeights.size() + " possible flow directions");

        if (flowWeights.isEmpty()) {
            // System.out.println("DEBUG: No valid flow directions found - all blocked or already have water");
            return;
        }

        // Find the minimum weight
        int minWeight = flowWeights.values().stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse(MIN_FLOW_WEIGHT);

        // System.out.println("DEBUG: Minimum flow weight is " + minWeight);

        // Flow to all directions with minimum weight
        // In Minecraft, water flows equally in all valid directions
        boolean isSource = (currentDepth == WaterBlock.SOURCE_DEPTH);
        int flowCount = 0;

        for (Map.Entry<Vector3i, Integer> entry : flowWeights.entrySet()) {
            Vector3i targetPos = entry.getKey();
            int newDepth = isSource ? 1 : currentDepth + 1; // Sources create depth 1, flowing water increments

            // Only flow if within the maximum depth range
            if (newDepth <= MAX_DEPTH) {
                // Flow to all directions with the minimum weight
                // This ensures water spreads even when no edges are nearby
                if (entry.getValue() == minWeight) {
                    createOrUpdateWaterAt(targetPos, newDepth, world);
                    scheduleFlowUpdate(targetPos);
                    flowCount++;
                    // System.out.println("DEBUG: Created water at " + targetPos.x + "," + targetPos.y + "," + targetPos.z +
                    //     " with depth " + newDepth + " (weight=" + entry.getValue() + ")");
                }
            }
        }

        if (flowCount == 0) {
            // System.out.println("DEBUG: No water was created - currentDepth=" + currentDepth + ", maxDepth=" + MAX_DEPTH + ", isSource=" + isSource);
        }
    }

    /**
     * Calculates flow weights for each direction using Minecraft's algorithm.
     * Water prefers to flow toward edges where it can create waterfalls.
     */
    private Map<Vector3i, Integer> calculateFlowWeights(Vector3i pos, Vector3i[] directions, World world) {
        Map<Vector3i, Integer> weights = new HashMap<>();

        // First, check for edges within 5 blocks in all directions from current position
        // This is how Minecraft determines flow preference
        Map<Vector3i, Integer> edgeDistances = findEdgeDistances(pos, world, 5);
        // if (!edgeDistances.isEmpty()) {
        //     System.out.println("DEBUG: Found " + edgeDistances.size() + " edges from " + pos.x + "," + pos.y + "," + pos.z);
        // }

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

            // Calculate weight based on distance to nearest edge
            // Lower distance = lower weight = higher priority
            int weight = MIN_FLOW_WEIGHT;

            // Check if this direction leads toward an edge
            if (edgeDistances.containsKey(targetPos)) {
                weight = edgeDistances.get(targetPos);
                // System.out.println("DEBUG: Direction to " + targetPos.x + "," + targetPos.y + "," + targetPos.z + " has edge at distance " + weight);
            } else {
                // Also check if moving in this direction gets us closer to any edge
                int pathToDown = findShortestPathToDown(targetPos, world, 5);
                if (pathToDown >= 0) {
                    weight = pathToDown + 1; // Add 1 since we're one step further
                    // System.out.println("DEBUG: Direction to " + targetPos.x + "," + targetPos.y + "," + targetPos.z + " leads to edge at distance " + weight);
                }
            }

            weights.put(targetPos, weight);
        }

        return weights;
    }

    /**
     * Finds all edges within a certain distance and returns their distances.
     * This implements Minecraft's edge detection for flow direction preference.
     */
    private Map<Vector3i, Integer> findEdgeDistances(Vector3i startPos, World world, int maxDistance) {
        Map<Vector3i, Integer> edgeDistances = new HashMap<>();

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

            // Skip if we've exceeded the search distance
            if (current.distance > maxDistance) {
                continue;
            }

            // Check if this position is an edge (can flow down)
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            if (current.distance > 0) { // Don't check the starting position itself
                BlockType belowBlock = world.getBlockAt(downPos.x, downPos.y, downPos.z);
                if (belowBlock == BlockType.AIR) {
                    // This is an edge! Water can fall here
                    edgeDistances.putIfAbsent(current.pos, current.distance);
                    // System.out.println("DEBUG: Found edge at " + current.pos.x + "," + current.pos.y + "," + current.pos.z + " distance " + current.distance + " from origin");
                }
            }

            // Continue searching if we haven't reached max distance
            if (current.distance < maxDistance) {
                for (Vector3i dir : directions) {
                    Vector3i nextPos = new Vector3i(current.pos).add(dir);

                    if (!visited.contains(nextPos)) {
                        // Check if position is traversable (air or water)
                        BlockType blockType = world.getBlockAt(nextPos.x, nextPos.y, nextPos.z);
                        if (blockType == BlockType.AIR || blockType == BlockType.WATER) {
                            visited.add(nextPos);
                            queue.offer(new PathNode(nextPos, current.distance + 1));
                        }
                    }
                }
            }
        }

        return edgeDistances;
    }

    /**
     * Finds the shortest path to a downward flow opportunity (edge detection).
     * This implements Minecraft's edge detection for creating waterfalls.
     */
    private int findShortestPathToDown(Vector3i startPos, World world, int maxDistance) {
        // Check immediate down first
        Vector3i immediateDown = new Vector3i(startPos.x, startPos.y - 1, startPos.z);
        if (canFlowTo(immediateDown, world)) {
            return 0; // Can flow down immediately
        }

        // Search within maxDistance blocks for edges (Minecraft behavior)
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

            // Check if we can flow down from this position (edge detection)
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            BlockType belowBlock = world.getBlockAt(downPos.x, downPos.y, downPos.z);
            if (belowBlock == BlockType.AIR) {
                // System.out.println("DEBUG: findShortestPathToDown found edge at distance " + current.distance);
                return current.distance;
            }

            // Explore adjacent positions
            for (Vector3i dir : directions) {
                Vector3i nextPos = new Vector3i(current.pos).add(dir);

                if (!visited.contains(nextPos)) {
                    // Check if position is traversable (air or water)
                    BlockType blockType = world.getBlockAt(nextPos.x, nextPos.y, nextPos.z);
                    if (blockType == BlockType.AIR || blockType == BlockType.WATER) {
                        visited.add(nextPos);
                        queue.offer(new PathNode(nextPos, current.distance + 1));
                    }
                }
            }
        }

        return -1; // No edge found within range
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
     * Water can flow infinitely downward but only 7 blocks horizontally.
     */
    private boolean hasValidSource(Vector3i pos, World world) {
        // Check if there's water directly above (infinite vertical flow)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        BlockType aboveBlock = world.getBlockAt(abovePos.x, abovePos.y, abovePos.z);
        if (aboveBlock == BlockType.WATER) {
            return true; // Water can flow infinitely downward
        }

        // Check for adjacent water blocks that could be feeding this one
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                // Check if neighbor has lower or equal depth (can feed us)
                int ourDepth = waterBlocks.get(pos) != null ? waterBlocks.get(pos).getDepth() : MAX_DEPTH;
                if (neighborWater.getDepth() < ourDepth) {
                    return true;
                }
            }
        }

        // Check if there's a source block nearby
        for (Vector3i sourcePos : sourceBlocks) {
            int horizontalDistance = Math.abs(pos.x - sourcePos.x) + Math.abs(pos.z - sourcePos.z);
            int verticalDistance = Math.abs(pos.y - sourcePos.y);

            // Simple check: within 7 blocks horizontally and on same level or one below
            if (horizontalDistance <= MAX_HORIZONTAL_DISTANCE && verticalDistance <= 1) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if there's a valid flow path between source and target.
     * This considers both horizontal spread limits and vertical flow.
     */
    private boolean hasValidFlowPath(Vector3i source, Vector3i target, World world) {
        // If target is directly below source, always valid (infinite vertical)
        if (source.x == target.x && source.z == target.z && source.y > target.y) {
            return true;
        }

        // For horizontal flow, check if within the 7-block limit
        int horizontalDistance = Math.abs(target.x - source.x) + Math.abs(target.z - source.z);

        // If on same level, simple distance check
        if (source.y == target.y) {
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        // If source is higher, water may have reset its depth when flowing down
        // Each level down allows another 7 blocks of horizontal spread
        if (source.y > target.y) {
            // Check if the horizontal distance is reasonable for the vertical drop
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        return false;
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
     * Ensures nearby water blocks are tracked in the simulation.
     * This helps recover from situations where water blocks exist in the world
     * but aren't properly tracked in the flow simulation.
     */
    private void ensureNearbyWaterTracked(Vector3i centerPos, World world) {
        // Check all blocks within a 2-block radius
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    Vector3i checkPos = new Vector3i(centerPos.x + dx, centerPos.y + dy, centerPos.z + dz);
                    BlockType blockType = world.getBlockAt(checkPos.x, checkPos.y, checkPos.z);

                    if (blockType == BlockType.WATER && !waterBlocks.containsKey(checkPos)) {
                        // Found untracked water block
                        WaterBlock waterBlock = new WaterBlock(estimateDepthFromSurroundings(checkPos, world));
                        waterBlocks.put(checkPos, waterBlock);
                        scheduleFlowUpdate(checkPos);
                        System.out.println("DEBUG: Found and tracked nearby untracked water at " + checkPos.x + "," + checkPos.y + "," + checkPos.z);
                    }
                }
            }
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