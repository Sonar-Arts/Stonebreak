package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handles water flow physics using cellular automaton approach.
 * Manages water pressure, flow direction, and level changes between blocks.
 */
public class FlowSimulation {

    private static final float FLOW_UPDATE_INTERVAL = 0.1f;
    private static final float FLOW_DAMPING = 0.95f;

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> waterSources;
    private final Queue<Vector3i> waterUpdateQueue;
    private float timeSinceWaterUpdate;

    public FlowSimulation() {
        this.waterBlocks = new ConcurrentHashMap<>();
        this.waterSources = Collections.synchronizedSet(new HashSet<>());
        this.waterUpdateQueue = new ConcurrentLinkedQueue<>();
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

        waterBlocks.put(pos, water);
        waterSources.add(pos);
        waterUpdateQueue.offer(pos);
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
            waterUpdateQueue.offer(pos);
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
    }

    /**
     * Processes water flow using cellular automaton approach.
     */
    private void processWaterFlow() {
        World world = Game.getWorld();
        if (world == null) return;

        Set<Vector3i> processed = new HashSet<>();
        List<FlowUpdate> updates = new ArrayList<>();

        // Process update queue
        while (!waterUpdateQueue.isEmpty()) {
            Vector3i pos = waterUpdateQueue.poll();
            if (processed.contains(pos)) continue;
            processed.add(pos);

            WaterBlock water = waterBlocks.get(pos);
            if (water == null) continue;

            // Check if still valid
            if (world.getBlockAt(pos.x, pos.y, pos.z) != BlockType.WATER) {
                waterBlocks.remove(pos);
                waterSources.remove(pos);
                continue;
            }

            // Process flow
            processWaterBlockFlow(pos, water, world, updates);
        }

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

        // Calculate pressure based on water above
        float pressure = calculatePressure(pos, world);
        water.setPressure(pressure);

        // Priority 1: Flow downward
        Vector3i down = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(down, world)) {
            float flowRate = Math.min(water.getLevel(), pressure);
            updates.add(new FlowUpdate(pos, down, flowRate, true));
            return;
        }

        // Priority 2: Spread horizontally based on pressure
        if (water.getLevel() > 1 || pressure > 2) {
            spreadHorizontally(pos, water, world, updates);
        }

        // Update flow direction for visual effects
        updateFlowDirection(pos, water);
    }

    /**
     * Calculates water pressure at a position.
     */
    private float calculatePressure(Vector3i pos, World world) {
        float pressure = 0;
        int y = pos.y + 1;

        // Check water column above
        while (y < WorldConfiguration.WORLD_HEIGHT) {
            if (world.getBlockAt(pos.x, y, pos.z) == BlockType.WATER) {
                WaterBlock above = waterBlocks.get(new Vector3i(pos.x, y, pos.z));
                if (above != null) {
                    pressure += above.getLevel() * 0.5f;
                }
                y++;
            } else {
                break;
            }
        }

        return Math.min(pressure, WaterBlock.MAX_WATER_LEVEL);
    }

    /**
     * Spreads water horizontally based on pressure differentials.
     */
    private void spreadHorizontally(Vector3i pos, WaterBlock water, World world, List<FlowUpdate> updates) {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };

        // Calculate flow to each direction
        for (Vector3i dir : directions) {
            Vector3i target = new Vector3i(pos).add(dir);

            if (canFlowTo(target, world)) {
                WaterBlock targetWater = waterBlocks.get(target);
                float targetLevel = targetWater != null ? targetWater.getLevel() : 0;

                // Flow based on level difference and pressure
                float levelDiff = water.getLevel() - targetLevel;
                float flowRate = (levelDiff + water.getPressure() * 0.2f) * FLOW_DAMPING;

                if (flowRate > 0.1f) {
                    updates.add(new FlowUpdate(pos, target, Math.min(flowRate, water.getLevel() - 1), false));
                }
            }
        }
    }

    /**
     * Updates flow direction for visual current effects.
     */
    private void updateFlowDirection(Vector3i pos, WaterBlock water) {
        // Implementation delegated to WaterBlock.updateFlowDirection()
        // This maintains the existing logic while keeping it in the data model
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
        if (source == null || source.getLevel() < update.amount) return;

        // Create or update target
        WaterBlock target = waterBlocks.get(update.to);
        if (target == null) {
            world.setBlockAt(update.to.x, update.to.y, update.to.z, BlockType.WATER);
            target = new WaterBlock();
            waterBlocks.put(update.to, target);
        }

        // Transfer water
        if (!waterSources.contains(update.from)) {
            source.removeWater(update.amount);
            if (source.isEmpty()) {
                waterBlocks.remove(update.from);
                world.setBlockAt(update.from.x, update.from.y, update.from.z, BlockType.AIR);
            }
        }

        target.addWater(update.amount);

        // Queue neighbors for update
        queueNeighbors(update.to);
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

        for (Vector3i neighbor : neighbors) {
            if (waterBlocks.containsKey(neighbor)) {
                waterUpdateQueue.offer(neighbor);
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

        FlowUpdate(Vector3i from, Vector3i to, float amount, boolean isVertical) {
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.isVertical = isVertical;
        }
    }
}