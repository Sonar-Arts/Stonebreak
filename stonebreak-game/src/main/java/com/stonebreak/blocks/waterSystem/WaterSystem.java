package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.PriorityQueue;

/**
 * Self-contained water simulation that mirrors Minecraft's water rules while
 * keeping the implementation intentionally small and readable. The system only
 * tracks a minimal per-block state (level + falling) and recalculates flow
 * lazily through an update queue.
 */
public final class WaterSystem {

    private static final int MAX_UPDATES_PER_TICK = 64;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final int WATER_TICK_DELAY = 10; // Slowed down for better cleanup performance
    private static final int NEIGHBOR_TICK_DELAY = 10; // Match water delay
    private static final int IMMEDIATE_UPDATE_DELAY = 2; // Small delay for "immediate" updates to prevent instant spreading
    private static final float MC_TICK_INTERVAL = 1.0f / 20.0f;
    private static final int[][] HORIZONTAL_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final float LEVEL_NORMALIZER = WaterBlock.MAX_LEVEL + 1.0f;

    private final World world;
    private final Map<BlockPos, WaterBlock> cells = new HashMap<>();
    private final PriorityQueue<ScheduledUpdate> pendingUpdates = new PriorityQueue<>((a, b) -> Long.compare(a.scheduledTick(), b.scheduledTick()));
    private final Map<BlockPos, Long> scheduledTicks = new HashMap<>();
    private final Set<Long> scannedChunks = new HashSet<>();
    private final Set<Long> dirtyChunks = new HashSet<>(); // Batched mesh updates

    private int suppressedCallbacks;
    private float tickAccumulator;
    private long logicalTick;

    public WaterSystem(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Processes pending water updates with a default per-frame budget.
     */
    public void tick() {
        tick(MC_TICK_INTERVAL);
    }

    /**
     * Processes pending water updates using the supplied budget.
     */
    public void tick(int budget) {
        advanceTicks(1, Math.max(0, budget));

    }

    /**
     * Processes pending water updates using the elapsed time in seconds.
     * Accumulates deltaTime and processes logical ticks at 20 TPS (Minecraft rate).
     * Limited to MAX_TICKS_PER_FRAME (2) and MAX_UPDATES_PER_TICK (64) for smooth performance.
     */
    public void tick(float deltaTimeSeconds) {
        float delta = Float.isFinite(deltaTimeSeconds) ? Math.max(0.0f, deltaTimeSeconds) : 0.0f;
        tickAccumulator += delta;

        int ticksToRun = 0;
        while (tickAccumulator >= MC_TICK_INTERVAL) {
            tickAccumulator -= MC_TICK_INTERVAL;
            ticksToRun++;
            if (ticksToRun >= MAX_TICKS_PER_FRAME) {
                break; // Safety limit: max 2 logical ticks per frame (prevents burst spreading)
            }
        }

        if (ticksToRun == 0) {
            return;
        }

        for (int i = 0; i < ticksToRun; i++) {
            advanceTicks(1, MAX_UPDATES_PER_TICK);
        }
    }

    private void advanceTicks(int ticks, int budgetPerTick) {
        for (int i = 0; i < ticks; i++) {
            logicalTick++;
            processQueue(Math.max(1, budgetPerTick));
            flushDirtyChunks(); // Apply batched mesh updates after each logical tick
        }
    }

    /**
     * Registers existing water when a chunk finishes generation.
     * Only enqueues water blocks that need immediate evaluation (surface/flowing water).
     */
    public void onChunkLoaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long key = chunkKey(chunk.getChunkX(), chunk.getChunkZ());
        if (!scannedChunks.add(key)) {
            return; // Already scanned this chunk
        }

        var reader = chunk.getBlockReader();
        for (int localX = 0; localX < WorldConfiguration.CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < WorldConfiguration.CHUNK_SIZE; localZ++) {
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                    if (reader.get(localX, y, localZ) == BlockType.WATER) {
                        int worldX = chunk.getChunkX() * WorldConfiguration.CHUNK_SIZE + localX;
                        int worldZ = chunk.getChunkZ() * WorldConfiguration.CHUNK_SIZE + localZ;
                        BlockPos pos = new BlockPos(worldX, y, worldZ);

                        // Check if this is a water block that needs processing
                        boolean needsUpdate = false;

                        // Check if there's air below (flowing water)
                        if (y > 0 && reader.isAir(localX, y - 1, localZ)) {
                            needsUpdate = true;
                        }

                        // Check if there's air beside (surface water)
                        if (!needsUpdate && (
                            (localX > 0 && reader.isAir(localX - 1, y, localZ)) ||
                            (localX < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(localX + 1, y, localZ)) ||
                            (localZ > 0 && reader.isAir(localX, y, localZ - 1)) ||
                            (localZ < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(localX, y, localZ + 1))
                        )) {
                            needsUpdate = true;
                        }

                        cells.put(pos, WaterBlock.source());

                        // Only enqueue water that needs to flow
                        if (needsUpdate) {
                            enqueueImmediate(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes cached water data when a chunk is unloaded.
     */
    public void onChunkUnloaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long key = chunkKey(chunk.getChunkX(), chunk.getChunkZ());
        scannedChunks.remove(key);

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        Iterator<BlockPos> iter = cells.keySet().iterator();
        while (iter.hasNext()) {
            BlockPos pos = iter.next();
            if (Math.floorDiv(pos.x(), WorldConfiguration.CHUNK_SIZE) == chunkX &&
                Math.floorDiv(pos.z(), WorldConfiguration.CHUNK_SIZE) == chunkZ) {
                iter.remove();
            }
        }

        pendingUpdates.removeIf(update -> {
            BlockPos pos = update.pos();
            return Math.floorDiv(pos.x(), WorldConfiguration.CHUNK_SIZE) == chunkX &&
                   Math.floorDiv(pos.z(), WorldConfiguration.CHUNK_SIZE) == chunkZ;
        });
        scheduledTicks.entrySet().removeIf(entry ->
            Math.floorDiv(entry.getKey().x(), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(entry.getKey().z(), WorldConfiguration.CHUNK_SIZE) == chunkZ);
    }

    /**
     * Notified by the world whenever a block changes. We ignore changes happening
     * as part of the water system itself (wrapped by {@link #setBlockType}).
     */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (suppressedCallbacks > 0 || !isWithinWorld(y)) {
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        if (next == BlockType.WATER) {
            // Player-placed water always becomes a source block, even if replacing a flow
            cells.put(pos, WaterBlock.source());
            enqueueImmediate(pos);
            scheduleNeighbors(pos);
            return;
        }

        WaterBlock removed = cells.remove(pos);
        if (removed != null || previous == BlockType.WATER) {
            enqueue(pos, NEIGHBOR_TICK_DELAY);
            scheduleNeighbors(pos);
        }

        // If a non-water block was removed (changed to air/replaceable), check neighbors for water sources
        // This ensures water sources resume flowing when obstructions are removed
        if (previous != BlockType.AIR && previous != BlockType.WATER && FlowBlockInteraction.canDisplace(next)) {
            for (int[] dir : HORIZONTAL_DIRECTIONS) {
                BlockPos neighbor = pos.offset(dir[0], 0, dir[1]);
                if (cells.get(neighbor) != null) {
                    enqueueImmediate(neighbor);
                }
            }
            BlockPos above = pos.above();
            if (cells.get(above) != null) {
                enqueueImmediate(above);
            }
        }
    }

    /**
     * Schedules a specific position for re-evaluation.
     */
    public void queueUpdate(int x, int y, int z) {
        if (isWithinWorld(y)) {
            enqueue(new BlockPos(x, y, z), NEIGHBOR_TICK_DELAY);
        }
    }

    private void processQueue(int budget) {
        int processed = 0;
        while (processed < budget && !pendingUpdates.isEmpty()) {
            ScheduledUpdate next = pendingUpdates.peek();
            if (next.scheduledTick() > logicalTick) {
                break;
            }

            pendingUpdates.poll();
            Long trackedTick = scheduledTicks.get(next.pos());
            if (trackedTick == null || trackedTick != next.scheduledTick()) {
                continue; // Stale entry
            }

            scheduledTicks.remove(next.pos());
            updateCell(next.pos());
            processed++;
        }
    }

    private void updateCell(BlockPos pos) {
        if (!isWithinWorld(pos.y())) {
            cells.remove(pos);
            return;
        }

        BlockType blockType = world.getBlockAt(pos.x(), pos.y(), pos.z());
        WaterBlock current = cells.get(pos);

        if (blockType != BlockType.WATER) {
            if (current != null) {
                cells.remove(pos);
                scheduleNeighbors(pos);
            }
            return;
        }

        if (current == null) {
            current = deriveInitialState(pos);
            cells.put(pos, current);
        }

        // Source blocks never change state - they only produce falling water below them
        if (current.isSource()) {
            tryFlowDown(pos, current); // Generate falling water below if needed
            spreadHorizontally(pos, current); // Spread horizontally
            return; // Source blocks remain unchanged
        }

        boolean canFall = tryFlowDown(pos, current);
        if (!canFall && current.falling()) {
            // When falling water lands, reset depth to level 1 (fresh flow starting point)
            current = WaterBlock.flowing(1);
            cells.put(pos, current);
            markChunkDirty(pos); // Batched visual update when falling water lands
        }

        int targetLevel = computeTargetLevel(pos, current);

        if (!current.isSource() && targetLevel >= WaterBlock.EMPTY_LEVEL) {
            removeWater(pos);
            return;
        }

        int clampedLevel = Math.min(targetLevel, WaterBlock.MAX_LEVEL);
        // Water should be falling if there's space or falling water below
        boolean shouldFall = canFall;
        WaterBlock updated = new WaterBlock(clampedLevel, shouldFall);

        if (!updated.equals(current)) {
            cells.put(pos, updated);
            scheduleNeighbors(pos);
            markChunkDirty(pos); // Batched visual update when water level changes
        }

        spreadHorizontally(pos, updated);
    }

    private WaterBlock deriveInitialState(BlockPos pos) {
        BlockType above = (pos.y() + 1 < WorldConfiguration.WORLD_HEIGHT)
            ? world.getBlockAt(pos.x(), pos.y() + 1, pos.z())
            : BlockType.AIR;
        if (above == BlockType.WATER && canFlowInto(pos.below())) {
            return WaterBlock.falling(1);
        }
        return WaterBlock.source();
    }

    private boolean tryFlowDown(BlockPos pos, WaterBlock current) {
        BlockPos below = pos.below();

        // First check if there's already falling water below - if so, this block should also be falling
        WaterBlock existing = cells.get(below);
        if (existing != null && existing.falling()) {
            return true;
        }

        // Check if there's space below (treats source blocks as space for edge detection)
        if (!hasSpaceBelow(below)) {
            return false;
        }

        // If there's a source block below, don't create water column - just maintain falling state visually
        if (existing != null && existing.isSource()) {
            return true; // Falling state for visual merge, but don't create water blocks below
        }

        // Actually try to fill the space below (canFlowInto prevents entering sources)
        if (!canFlowInto(below)) {
            return true; // Space exists but can't enter (e.g., source block) - still counts as "can fall"
        }

        // Falling water always starts at level 1, regardless of source level
        boolean filled = tryFill(below, WaterBlock.falling(1));
        if (filled) {
            scheduleNeighbors(below);
            return true;
        }

        return false;
    }

    private int computeTargetLevel(BlockPos pos, WaterBlock current) {
        if (current.isSource()) {
            return WaterBlock.SOURCE_LEVEL;
        }

        int minNeighbor = WaterBlock.MAX_LEVEL;
        int sourceNeighbors = 0;

        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            BlockPos neighborPos = pos.offset(dir[0], 0, dir[1]);
            if (!isWithinWorld(neighborPos.y())) {
                continue;
            }
            WaterBlock neighbor = cells.get(neighborPos);
            if (neighbor == null) {
                if (world.getBlockAt(neighborPos.x(), neighborPos.y(), neighborPos.z()) != BlockType.WATER) {
                    continue;
                }
                neighbor = WaterBlock.source();
            }
            if (neighbor.isSource()) {
                sourceNeighbors++;
            }
            minNeighbor = Math.min(minNeighbor, neighbor.level());
        }

        if (sourceNeighbors >= 2 && FlowBlockInteraction.supportsSource(world, pos.x(), pos.y(), pos.z())) {
            return WaterBlock.SOURCE_LEVEL;
        }

        WaterBlock above = cells.get(pos.above());
        if (above != null) {
            return Math.min(above.level(), minNeighbor + 1);
        }

        // Edge case: All neighbors are level 7 or weaker (or no water neighbors found)
        // Level 7 blocks should only be maintained by stronger neighbors (level 6 or better)
        // If all neighbors are level 7, remove this block to allow proper cleanup
        if (minNeighbor == WaterBlock.MAX_LEVEL) {
            return WaterBlock.EMPTY_LEVEL; // No stronger neighbors, remove
        }
        return Math.min(minNeighbor + 1, WaterBlock.MAX_LEVEL);
    }

    private void spreadHorizontally(BlockPos pos, WaterBlock state) {
        if (state.falling()) {
            return; // Falling water does not spread sideways until it lands
        }

        int spreadLevel = state.isSource() ? 1 : state.level() + 1;
        if (spreadLevel > WaterBlock.MAX_LEVEL) {
            return; // Don't spread beyond max level
        }

        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            BlockPos neighbor = pos.offset(dir[0], 0, dir[1]);

            // Check if the target position has space below (treats sources as space) - if so, water should be falling
            boolean shouldBeFalling = hasSpaceBelow(neighbor.below());

            if (tryFill(neighbor, new WaterBlock(spreadLevel, shouldBeFalling))) {
                scheduleNeighbors(neighbor);
            }
        }
    }

    private boolean tryFill(BlockPos pos, WaterBlock candidate) {
        if (!isWithinWorld(pos.y())) {
            return false;
        }

        BlockType blockType = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!FlowBlockInteraction.canDisplace(blockType)) {
            return false;
        }

        WaterBlock existing = cells.get(pos);
        boolean levelChanged = false;

        if (existing != null) {
            // CRITICAL: Source blocks should not be replaced by flows or falling water
            // They can coexist - the source remains, and flows/falling water simply merge
            if (existing.isSource() && !candidate.isSource()) {
                return false; // Keep the source, don't replace it with flow/falling water
            }
            if (!candidate.isStrongerThan(existing)) {
                if (existing.falling() && !candidate.falling() && existing.level() == candidate.level()) {
                    // Landing water converting from falling to still at same level is allowed
                } else {
                    return false;
                }
            }
            // Check if the water level is actually changing
            levelChanged = (existing.level() != candidate.level() || existing.falling() != candidate.falling());
        }

        if (FlowBlockInteraction.isFragile(blockType)) {
            FlowBlockInteraction.dropFragile(world, pos.x(), pos.y(), pos.z(), blockType);
            setBlockType(pos, BlockType.AIR);
        }

        boolean blockTypeChanged = (blockType != BlockType.WATER);
        if (blockTypeChanged) {
            setBlockType(pos, BlockType.WATER);
        }

        cells.put(pos, candidate);
        enqueue(pos, WATER_TICK_DELAY);

        // Trigger batched visual update if water level changed (even if block type stayed WATER)
        if (levelChanged && !blockTypeChanged) {
            markChunkDirty(pos);
        }

        return true;
    }

    private void removeWater(BlockPos pos) {
        cells.remove(pos);
        if (world.getBlockAt(pos.x(), pos.y(), pos.z()) == BlockType.WATER) {
            setBlockType(pos, BlockType.AIR);
        }
        scheduleNeighbors(pos);
    }

    private void setBlockType(BlockPos pos, BlockType type) {
        if (!isWithinWorld(pos.y())) {
            return;
        }
        suppressedCallbacks++;
        try {
            world.setBlockAt(pos.x(), pos.y(), pos.z(), type);
        } finally {
            suppressedCallbacks--;
        }
    }

    /**
     * Marks a chunk as needing a mesh rebuild due to water changes.
     * Updates are batched and applied at the end of each logical tick.
     */
    private void markChunkDirty(BlockPos pos) {
        if (!isWithinWorld(pos.y())) {
            return;
        }
        int chunkX = Math.floorDiv(pos.x(), WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(pos.z(), WorldConfiguration.CHUNK_SIZE);
        dirtyChunks.add(chunkKey(chunkX, chunkZ));
    }

    /**
     * Applies all batched mesh and data updates to chunks.
     * Called once per logical tick after processing all water updates.
     * Marks chunks dirty for both mesh regeneration AND data saving.
     */
    private void flushDirtyChunks() {
        if (dirtyChunks.isEmpty()) {
            return;
        }

        for (Long chunkKey : dirtyChunks) {
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) (chunkKey & 0xFFFFFFFFL);

            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk != null) {
                // Mark chunk dirty for both mesh and data using CCO dirty tracker
                // This ensures water changes are both rendered AND saved to disk
                chunk.getCcoDirtyTracker().markBlockChanged();

                // Trigger mesh rebuild using world's scheduling system
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE;
                world.triggerChunkRebuild(worldX, 0, worldZ);
            }
        }

        dirtyChunks.clear();
    }

    private void scheduleNeighbors(BlockPos pos) {
        enqueue(pos, NEIGHBOR_TICK_DELAY);
        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            enqueue(pos.offset(dir[0], 0, dir[1]), NEIGHBOR_TICK_DELAY);
        }
        enqueue(pos.above(), WATER_TICK_DELAY);
        enqueue(pos.below(), WATER_TICK_DELAY);
    }

    private void enqueueImmediate(BlockPos pos) {
        enqueue(pos, IMMEDIATE_UPDATE_DELAY);
    }

    private void enqueue(BlockPos pos, int delayTicks) {
        if (!isWithinWorld(pos.y())) {
            return;
        }

        int clampedDelay = Math.max(0, delayTicks);
        long scheduledTick = logicalTick + clampedDelay;

        Long existing = scheduledTicks.get(pos);
        if (existing != null && existing <= scheduledTick) {
            return;
        }

        scheduledTicks.put(pos, scheduledTick);
        pendingUpdates.add(new ScheduledUpdate(pos, scheduledTick));
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private boolean canFlowInto(BlockPos pos) {
        if (!isWithinWorld(pos.y())) {
            return false;
        }
        // Water cannot flow into positions occupied by source blocks
        WaterBlock existing = cells.get(pos);
        if (existing != null && existing.isSource()) {
            return false;
        }
        return FlowBlockInteraction.canDisplace(world.getBlockAt(pos.x(), pos.y(), pos.z()));
    }

    /**
     * Checks if there's empty space below for water to fall into.
     * Treats source blocks as "space" for falling logic (water should fall over sources like edges).
     */
    private boolean hasSpaceBelow(BlockPos pos) {
        if (!isWithinWorld(pos.y())) {
            return false;
        }
        BlockType blockType = world.getBlockAt(pos.x(), pos.y(), pos.z());
        // Treat source blocks as space below (so water recognizes edge and falls)
        WaterBlock existing = cells.get(pos);
        if (existing != null && existing.isSource()) {
            return true; // Source = space for falling purposes
        }
        return FlowBlockInteraction.canDisplace(blockType);
    }

    private boolean isWithinWorld(int y) {
        return y >= 0 && y < WorldConfiguration.WORLD_HEIGHT;
    }

    public WaterBlock getWaterBlock(int x, int y, int z) {
        if (!isWithinWorld(y)) {
            return null;
        }
        return cells.get(new BlockPos(x, y, z));
    }

    public float getWaterLevel(int x, int y, int z) {
        WaterBlock state = getWaterBlock(x, y, z);
        if (state == null) {
            return 0.0f;
        }
        if (state.falling()) {
            return 1.0f;
        }
        return (WaterBlock.MAX_LEVEL - state.level() + 1) / LEVEL_NORMALIZER;
    }

    public boolean isWaterSource(int x, int y, int z) {
        WaterBlock state = getWaterBlock(x, y, z);
        return state != null && state.isSource();
    }

    public float getWaterVisualHeight(int x, int y, int z) {
        return getWaterLevel(x, y, z);
    }

    public float getFoamIntensity(float x, float y, float z) {
        return 0.0f; // Foam is purely a visual effect handled elsewhere
    }

    public int getTrackedWaterCount() {
        return cells.size();
    }

    private record BlockPos(int x, int y, int z) {
        BlockPos offset(int dx, int dy, int dz) {
            return new BlockPos(x + dx, y + dy, z + dz);
        }

        BlockPos above() {
            return offset(0, 1, 0);
        }

        BlockPos below() {
            return offset(0, -1, 0);
        }
    }

    private record ScheduledUpdate(BlockPos pos, long scheduledTick) { }
}
