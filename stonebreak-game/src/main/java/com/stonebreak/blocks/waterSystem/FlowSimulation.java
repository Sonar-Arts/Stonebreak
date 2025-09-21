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
 * Handles water flow physics using cellular automaton approach.
 * Manages water pressure, flow direction, and level changes between blocks.
 */
public class FlowSimulation {

    private static final float FLOW_UPDATE_INTERVAL = 0.05f;
    private static final float FLOW_RATE = 2.0f;
    private static final float MIN_FLOW_AMOUNT = 0.1f;
    private static final float GRAVITY_FLOW_RATE = 8.0f;
    private static final int MAX_FLOW_DISTANCE = 7; // Water stops flowing after 7 blocks
    private static final float LEVEL_DECAY_PER_BLOCK = 1.0f; // Level decreases by 1 per block

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> waterSources;
    private final Queue<Vector3i> waterUpdateQueue;
    private final Set<Vector3i> activeWaterBlocks;
    private float timeSinceWaterUpdate;

    public FlowSimulation() {
        this.waterBlocks = new ConcurrentHashMap<>();
        this.waterSources = Collections.synchronizedSet(new HashSet<>());
        this.waterUpdateQueue = new ConcurrentLinkedQueue<>();
        this.activeWaterBlocks = Collections.synchronizedSet(new HashSet<>());
        this.timeSinceWaterUpdate = 0.0f;
    }

    /**
     * Updates water flow physics.
     *
     * @param deltaTime Time elapsed since last update
     */
    public void update(float deltaTime) {
        timeSinceWaterUpdate += deltaTime;

        if (timeSinceWaterUpdate >= FLOW_UPDATE_INTERVAL) {
            processWaterFlow();
            timeSinceWaterUpdate = 0.0f;
        }
    }

    /**
     * Adds a water source at the specified position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void addWaterSource(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) return;

        Vector3i pos = new Vector3i(x, y, z);
        world.setBlockAt(x, y, z, BlockType.WATER);

        WaterBlock water = new WaterBlock();
        water.setSource(true);
        water.setDistanceFromSource(0); // Sources are at distance 0

        waterBlocks.put(pos, water);
        waterSources.add(pos);
        activeWaterBlocks.add(pos);
        waterUpdateQueue.offer(pos);

        // Queue neighbors for immediate update
        queueNeighbors(pos);
    }

    /**
     * Removes a water source at the specified position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void removeWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        waterSources.remove(pos);

        // If not a source anymore, queue for flow update
        if (waterBlocks.containsKey(pos)) {
            WaterBlock water = waterBlocks.get(pos);
            if (water != null) {
                water.setSource(false);
            }
            activeWaterBlocks.add(pos);
            waterUpdateQueue.offer(pos);
            queueNeighbors(pos);
        }
    }

    /**
     * Gets the water level at a specific position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Water level from 0.0 to 1.0
     */
    public float getWaterLevel(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock water = waterBlocks.get(pos);

        if (water != null) {
            return water.getNormalizedLevel();
        }

        // Check if water block exists in world
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 1.0f; // Full water block
        }

        return 0.0f;
    }

    /**
     * Checks if a position contains a water source.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the position contains a water source
     */
    public boolean isWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        return waterSources.contains(pos);
    }

    /**
     * Gets the visual height of water for rendering.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Visual water height for rendering
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock water = waterBlocks.get(pos);

        if (water != null) {
            return water.getVisualHeight();
        }

        // Default water height if block exists but not in system
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 0.875f;
        }

        return 0.0f;
    }

    /**
     * Gets foam intensity at a position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Foam intensity (0.0 to 1.0)
     */
    public float getFoamIntensity(float x, float y, float z) {
        Vector3i pos = new Vector3i((int)x, (int)y, (int)z);
        WaterBlock water = waterBlocks.get(pos);

        if (water == null) return 0;

        // Foam based on flow speed
        float foam = water.getFlowDirection().length() * 0.5f;

        // Add foam near edges
        World world = Game.getWorld();
        if (world != null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockType neighbor = world.getBlockAt((int)x + dx, (int)y, (int)z + dz);
                    if (neighbor != BlockType.WATER && neighbor != BlockType.AIR) {
                        foam += 0.3f;
                    }
                }
            }
        }

        return Math.min(foam, 1.0f);
    }

    /**
     * Gets a water block at the specified position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return WaterBlock instance or null if none exists
     */
    public WaterBlock getWaterBlock(int x, int y, int z) {
        return waterBlocks.get(new Vector3i(x, y, z));
    }

    /**
     * Detects existing water blocks in the world.
     */
    public void detectExistingWater() {
        waterBlocks.clear();
        waterSources.clear();
        waterUpdateQueue.clear();
        activeWaterBlocks.clear();

        // Scan existing world for water blocks
        World world = Game.getWorld();
        if (world == null) return;

        // Note: Actual world scanning would require world chunk iteration
        // For now, this clears the simulation state
    }

    /**
     * Processes water flow using cellular automaton approach.
     */
    private void processWaterFlow() {
        World world = Game.getWorld();
        if (world == null) return;

        // Add all active water blocks to update queue if queue is empty
        if (waterUpdateQueue.isEmpty()) {
            waterUpdateQueue.addAll(activeWaterBlocks);
        }

        Set<Vector3i> processed = new HashSet<>();
        List<FlowUpdate> updates = new ArrayList<>();
        Set<Vector3i> newActiveBlocks = new HashSet<>();

        // Process update queue
        while (!waterUpdateQueue.isEmpty()) {
            Vector3i pos = waterUpdateQueue.poll();
            if (processed.contains(pos)) continue;
            processed.add(pos);

            WaterBlock water = waterBlocks.get(pos);
            if (water == null) {
                // Check if there's a water block in the world we don't know about
                if (world.getBlockAt(pos.x, pos.y, pos.z) == BlockType.WATER) {
                    water = new WaterBlock();
                    waterBlocks.put(pos, water);
                } else {
                    activeWaterBlocks.remove(pos);
                    continue;
                }
            }

            // Check if still valid
            if (world.getBlockAt(pos.x, pos.y, pos.z) != BlockType.WATER) {
                waterBlocks.remove(pos);
                waterSources.remove(pos);
                activeWaterBlocks.remove(pos);
                continue;
            }

            // Mark as active
            newActiveBlocks.add(pos);

            // Process flow
            processWaterBlockFlow(pos, water, world, updates);
        }

        // Update active blocks set
        activeWaterBlocks.clear();
        activeWaterBlocks.addAll(newActiveBlocks);

        // Apply all updates
        for (FlowUpdate update : updates) {
            applyFlowUpdate(update, world);
        }
    }

    /**
     * Processes flow for a single water block.
     */
    private void processWaterBlockFlow(Vector3i pos, WaterBlock water, World world, List<FlowUpdate> updates) {
        // Sources maintain full level
        if (waterSources.contains(pos)) {
            water.setLevel(WaterBlock.MAX_WATER_LEVEL);
            water.setPressure(WaterBlock.MAX_WATER_LEVEL);
        }

        // Skip flow if water level is too low
        if (water.getLevel() < MIN_FLOW_AMOUNT && !waterSources.contains(pos)) {
            return;
        }

        // Priority 1: Flow downward (gravity)
        Vector3i down = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(down, world)) {
            WaterBlock targetWater = waterBlocks.get(down);
            float targetLevel = targetWater != null ? targetWater.getLevel() : 0;
            int targetDistance = water.getDistanceFromSource(); // Same distance for vertical flow

            float maxTargetLevel = Math.max(0, WaterBlock.MAX_WATER_LEVEL - (targetDistance * LEVEL_DECAY_PER_BLOCK));

            if (targetLevel < maxTargetLevel) {
                float flowAmount = Math.min(water.getLevel() * GRAVITY_FLOW_RATE / WaterBlock.MAX_WATER_LEVEL,
                                          maxTargetLevel - targetLevel);
                if (flowAmount >= MIN_FLOW_AMOUNT) {
                    updates.add(new FlowUpdate(pos, down, flowAmount, true, targetDistance));
                    return; // Prioritize vertical flow
                }
            }
        }

        // Priority 2: Spread horizontally (only if we have enough water)
        if (water.getLevel() > MIN_FLOW_AMOUNT) {
            spreadHorizontally(pos, water, world, updates);
        }

        // Update flow direction for visual effects
        updateFlowDirection(pos, water);
    }

    /**
     * Calculates water pressure at a position.
     */
    private float calculatePressure(Vector3i pos, World world) {
        float pressure = 1.0f; // Base pressure
        int y = pos.y + 1;
        int waterHeight = 0;

        // Check water column above
        while (y < WorldConfiguration.WORLD_HEIGHT && waterHeight < 10) {
            if (world.getBlockAt(pos.x, y, pos.z) == BlockType.WATER) {
                WaterBlock above = waterBlocks.get(new Vector3i(pos.x, y, pos.z));
                if (above != null) {
                    pressure += above.getLevel() * 0.3f;
                } else {
                    pressure += WaterBlock.MAX_WATER_LEVEL * 0.3f;
                }
                waterHeight++;
                y++;
            } else {
                break;
            }
        }

        return Math.min(pressure, WaterBlock.MAX_WATER_LEVEL * 2);
    }

    /**
     * Spreads water horizontally based on level differences and distance limits.
     */
    private void spreadHorizontally(Vector3i pos, WaterBlock water, World world, List<FlowUpdate> updates) {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };

        int currentDistance = water.getDistanceFromSource();
        if (currentDistance >= MAX_FLOW_DISTANCE) {
            return; // Water has reached maximum flow distance
        }

        List<FlowCandidate> candidates = new ArrayList<>();

        // Calculate level differences and check flow viability
        for (Vector3i dir : directions) {
            Vector3i target = new Vector3i(pos).add(dir);

            if (canFlowTo(target, world)) {
                WaterBlock targetWater = waterBlocks.get(target);
                float targetLevel = targetWater != null ? targetWater.getLevel() : 0;

                // Calculate what the target level should be based on distance from source
                int targetDistance = currentDistance + 1;
                float maxTargetLevel = Math.max(0, WaterBlock.MAX_WATER_LEVEL - (targetDistance * LEVEL_DECAY_PER_BLOCK));

                // Only flow if:
                // 1. Target level is below what it should be at this distance
                // 2. We have enough water to give
                // 3. Target distance is within flow limits
                if (targetDistance <= MAX_FLOW_DISTANCE &&
                    targetLevel < maxTargetLevel &&
                    water.getLevel() > targetLevel + MIN_FLOW_AMOUNT) {

                    float levelDiff = Math.min(water.getLevel() - targetLevel, maxTargetLevel - targetLevel);
                    if (levelDiff > MIN_FLOW_AMOUNT) {
                        candidates.add(new FlowCandidate(target, levelDiff, targetDistance));
                    }
                }
            }
        }

        // Distribute flow to viable candidates
        if (!candidates.isEmpty()) {
            for (FlowCandidate candidate : candidates) {
                float flowAmount;
                if (waterSources.contains(pos)) {
                    // Sources provide unlimited water but respect level hierarchy
                    flowAmount = candidate.levelDiff;
                } else {
                    // Regular blocks flow a portion of their water
                    flowAmount = Math.min(candidate.levelDiff * 0.8f,
                                        Math.max(0, water.getLevel() - MIN_FLOW_AMOUNT));
                }

                if (flowAmount >= MIN_FLOW_AMOUNT) {
                    updates.add(new FlowUpdate(pos, candidate.target, flowAmount, false, candidate.targetDistance));
                }
            }
        }
    }

    /**
     * Updates flow direction for visual current effects.
     */
    private void updateFlowDirection(Vector3i pos, WaterBlock water) {
        World world = Game.getWorld();
        if (world == null) return;

        Vector3f[] directions = {
            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
            new Vector3f(0, -1, 0) // Down
        };

        WaterBlock[] neighbors = new WaterBlock[directions.length];
        for (int i = 0; i < directions.length; i++) {
            Vector3f dir = directions[i];
            Vector3i neighborPos = new Vector3i(pos.x + (int)dir.x, pos.y + (int)dir.y, pos.z + (int)dir.z);
            neighbors[i] = waterBlocks.get(neighborPos);
        }

        water.updateFlowDirection(FLOW_UPDATE_INTERVAL, neighbors, directions);
    }

    /**
     * Checks if water can flow to a position.
     */
    private boolean canFlowTo(Vector3i pos, World world) {
        BlockType block = world.getBlockAt(pos.x, pos.y, pos.z);
        return block == BlockType.AIR || block == BlockType.WATER;
    }

    /**
     * Applies a flow update to the world.
     */
    private void applyFlowUpdate(FlowUpdate update, World world) {
        WaterBlock source = waterBlocks.get(update.from);
        if (source == null) return;

        // Validate flow amount
        float actualFlow = waterSources.contains(update.from) ?
            update.amount :
            Math.min(update.amount, Math.max(0, source.getLevel() - MIN_FLOW_AMOUNT));

        if (actualFlow < MIN_FLOW_AMOUNT) return;

        // Create or update target
        WaterBlock target = waterBlocks.get(update.to);
        if (target == null) {
            if (world.getBlockAt(update.to.x, update.to.y, update.to.z) == BlockType.AIR) {
                world.setBlockAt(update.to.x, update.to.y, update.to.z, BlockType.WATER);
            }
            target = new WaterBlock(0);
            waterBlocks.put(update.to, target);
        }

        // Calculate target level based on distance from source
        int targetDistance = update.targetDistance;
        float maxTargetLevel = Math.max(0, WaterBlock.MAX_WATER_LEVEL - (targetDistance * LEVEL_DECAY_PER_BLOCK));

        // Calculate how much water can actually be transferred
        float targetCapacity = Math.max(0, maxTargetLevel - target.getLevel());
        float transferAmount = Math.min(actualFlow, targetCapacity);

        if (transferAmount >= MIN_FLOW_AMOUNT) {
            // Transfer water
            if (!waterSources.contains(update.from)) {
                source.removeWater(transferAmount);
                if (source.isEmpty()) {
                    waterBlocks.remove(update.from);
                    activeWaterBlocks.remove(update.from);
                    world.setBlockAt(update.from.x, update.from.y, update.from.z, BlockType.AIR);
                }
            }

            target.addWater(transferAmount);
            target.setDistanceFromSource(targetDistance); // Set distance for level calculations
            activeWaterBlocks.add(update.to);

            // Queue neighbors for update
            queueNeighbors(update.to);
            queueNeighbors(update.from);
        }
    }

    /**
     * Queues neighboring blocks for update.
     */
    private void queueNeighbors(Vector3i pos) {
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y + 1, pos.z),
            new Vector3i(pos.x, pos.y - 1, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        World world = Game.getWorld();
        if (world == null) return;

        for (Vector3i neighbor : neighbors) {
            if (waterBlocks.containsKey(neighbor) ||
                world.getBlockAt(neighbor.x, neighbor.y, neighbor.z) == BlockType.WATER) {
                waterUpdateQueue.offer(neighbor);
                activeWaterBlocks.add(neighbor);
            }
        }
    }

    /**
     * Flow update information.
     */
    private static class FlowUpdate {
        final Vector3i from;
        final Vector3i to;
        final float amount;
        final boolean isVertical;
        final int targetDistance;

        FlowUpdate(Vector3i from, Vector3i to, float amount, boolean isVertical) {
            this(from, to, amount, isVertical, -1);
        }

        FlowUpdate(Vector3i from, Vector3i to, float amount, boolean isVertical, int targetDistance) {
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.isVertical = isVertical;
            this.targetDistance = targetDistance;
        }
    }

    /**
     * Flow candidate for horizontal spreading.
     */
    private static class FlowCandidate {
        final Vector3i target;
        final float levelDiff;
        final int targetDistance;

        FlowCandidate(Vector3i target, float levelDiff, int targetDistance) {
            this.target = target;
            this.levelDiff = levelDiff;
            this.targetDistance = targetDistance;
        }
    }
}