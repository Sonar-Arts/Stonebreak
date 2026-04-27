package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

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
    // Cells and scheduled ticks are keyed by a packed (x, y, z) long instead of a
    // BlockPos record. The mesh thread hits getWaterBlock thousands of times per
    // chunk remesh; allocating a fresh BlockPos per lookup was the dominant
    // source of allocation churn in the water hot path.
    private final Map<Long, WaterBlock> cells = new ConcurrentHashMap<>();
    private final PriorityQueue<ScheduledUpdate> pendingUpdates = new PriorityQueue<>((a, b) -> Long.compare(a.scheduledTick(), b.scheduledTick()));
    private final Map<Long, Long> scheduledTicks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = new HashSet<>();
    private final Set<Long> dirtyChunks = new HashSet<>(); // Batched mesh updates

    // CCO API caching for performance
    private final Map<Long, CcoBlockReader> readerCache = new ConcurrentHashMap<>();

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
                        long posKey = packKey(worldX, y, worldZ);

                        boolean needsUpdate = false;

                        if (y > 0 && reader.isAir(localX, y - 1, localZ)) {
                            needsUpdate = true;
                        }

                        if (!needsUpdate && (
                            (localX > 0 && reader.isAir(localX - 1, y, localZ)) ||
                            (localX < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(localX + 1, y, localZ)) ||
                            (localZ > 0 && reader.isAir(localX, y, localZ - 1)) ||
                            (localZ < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(localX, y, localZ + 1))
                        )) {
                            needsUpdate = true;
                        }

                        // Only seed a source when nothing was loaded from save metadata —
                        // loadWaterMetadata() runs first and populates flow levels we must respect.
                        if (cells.get(posKey) == null) {
                            cells.put(posKey, WaterBlock.source());
                        }

                        if (needsUpdate) {
                            enqueueImmediate(posKey);
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes cached water data when a chunk is unloaded.
     * Thread-safe: ConcurrentHashMap handles concurrent access.
     */
    public void onChunkUnloaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long key = chunkKey(chunk.getChunkX(), chunk.getChunkZ());
        scannedChunks.remove(key);
        readerCache.remove(key); // Clear CCO reader cache

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        cells.keySet().removeIf(posKey ->
            Math.floorDiv(unpackX(posKey), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(unpackZ(posKey), WorldConfiguration.CHUNK_SIZE) == chunkZ
        );

        // PriorityQueue is not thread-safe; synchronize with processQueue/enqueue
        // to prevent heap shifts from yielding nulls or skipped elements during iteration.
        synchronized (pendingUpdates) {
            Iterator<ScheduledUpdate> iterator = pendingUpdates.iterator();
            while (iterator.hasNext()) {
                ScheduledUpdate update = iterator.next();
                if (update == null) {
                    continue;
                }
                long posKey = update.posKey();
                if (Math.floorDiv(unpackX(posKey), WorldConfiguration.CHUNK_SIZE) == chunkX &&
                    Math.floorDiv(unpackZ(posKey), WorldConfiguration.CHUNK_SIZE) == chunkZ) {
                    iterator.remove();
                }
            }
        }

        scheduledTicks.keySet().removeIf(posKey ->
            Math.floorDiv(unpackX(posKey), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(unpackZ(posKey), WorldConfiguration.CHUNK_SIZE) == chunkZ
        );
    }

    /**
     * Notified by the world whenever a block changes.
     * Note: CCO API handles callbacks automatically, so we process all changes.
     */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (!isWithinWorld(y)) {
            return;
        }

        long posKey = packKey(x, y, z);
        if (next == BlockType.WATER) {
            // Player-placed water always becomes a source block, even if replacing a flow
            cells.put(posKey, WaterBlock.source());
            enqueueImmediate(posKey);
            scheduleNeighbors(posKey);
            return;
        }

        WaterBlock removed = cells.remove(posKey);
        if (removed != null || previous == BlockType.WATER) {
            enqueue(posKey, NEIGHBOR_TICK_DELAY);
            scheduleNeighbors(posKey);
        }

        // If a non-water block was removed (changed to air/replaceable), check neighbors for water sources
        // so blocked sources resume flowing once the obstruction is gone.
        if (previous != BlockType.AIR && previous != BlockType.WATER && FlowBlockInteraction.canDisplace(next)) {
            for (int[] dir : HORIZONTAL_DIRECTIONS) {
                long neighbor = keyOffset(posKey, dir[0], 0, dir[1]);
                if (cells.get(neighbor) != null) {
                    enqueueImmediate(neighbor);
                }
            }
            long above = keyAbove(posKey);
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
            enqueue(packKey(x, y, z), NEIGHBOR_TICK_DELAY);
        }
    }

    private void processQueue(int budget) {
        int processed = 0;
        while (processed < budget) {
            ScheduledUpdate next;
            synchronized (pendingUpdates) {
                next = pendingUpdates.peek();
                if (next == null || next.scheduledTick() > logicalTick) {
                    break;
                }
                pendingUpdates.poll();
            }
            Long trackedTick = scheduledTicks.get(next.posKey());
            if (trackedTick == null || trackedTick != next.scheduledTick()) {
                continue; // Stale entry
            }

            scheduledTicks.remove(next.posKey());
            updateCell(next.posKey());
            processed++;
        }
    }

    private void updateCell(long posKey) {
        int posY = unpackY(posKey);
        if (!isWithinWorld(posY)) {
            cells.remove(posKey);
            return;
        }

        BlockType blockType = getBlockViaCco(posKey);
        WaterBlock current = cells.get(posKey);

        if (blockType != BlockType.WATER) {
            if (current != null) {
                cells.remove(posKey);
                scheduleNeighbors(posKey);
            }
            return;
        }

        if (current == null) {
            current = deriveInitialState(posKey);
            cells.put(posKey, current);
        }

        // Source blocks never change state - they only produce falling water below them
        if (current.isSource()) {
            tryFlowDown(posKey, current);
            spreadHorizontally(posKey, current);
            return;
        }

        boolean canFall = tryFlowDown(posKey, current);
        if (!canFall && current.falling()) {
            // Landing: drop the falling flag. Falling water is always level 1 (only
            // tryFlowDown produces it), so the level is unchanged and the mesh — which
            // consumes only level() — does not need rebuilding.
            current = current.withoutFalling();
            cells.put(posKey, current);
        }

        int targetLevel = computeTargetLevel(posKey, current);

        if (!current.isSource() && targetLevel >= WaterBlock.EMPTY_LEVEL) {
            removeWater(posKey);
            return;
        }

        int clampedLevel = Math.min(targetLevel, WaterBlock.MAX_LEVEL);
        WaterBlock updated = new WaterBlock(clampedLevel, canFall);

        if (!updated.equals(current)) {
            boolean levelChanged = updated.level() != current.level();
            cells.put(posKey, updated);
            // Neighbors only consume level(); a pure falling-flag flip should not
            // cascade scheduling or remeshing.
            if (levelChanged) {
                scheduleNeighbors(posKey);
                markChunkDirty(posKey);
            }
        }

        spreadHorizontally(posKey, updated);
    }

    private WaterBlock deriveInitialState(long posKey) {
        int posY = unpackY(posKey);
        BlockType above = (posY + 1 < WorldConfiguration.WORLD_HEIGHT)
            ? getBlockViaCco(keyAbove(posKey))
            : BlockType.AIR;
        if (above == BlockType.WATER && canFlowInto(keyBelow(posKey))) {
            return WaterBlock.falling(1);
        }
        return WaterBlock.source();
    }

    private boolean tryFlowDown(long posKey, WaterBlock current) {
        long below = keyBelow(posKey);

        // Already-falling water below means our column is active; keep this block falling too.
        WaterBlock existing = cells.get(below);
        if (existing != null && existing.falling()) {
            return true;
        }

        // No empty cell below → cannot fall. Solid blocks and existing water both block falling.
        if (!hasSpaceBelow(below)) {
            return false;
        }

        if (tryFill(below, WaterBlock.falling(1))) {
            scheduleNeighbors(below);
            return true;
        }

        return false;
    }

    private int computeTargetLevel(long posKey, WaterBlock current) {
        if (current.isSource()) {
            return WaterBlock.SOURCE_LEVEL;
        }

        int minNeighbor = WaterBlock.MAX_LEVEL;
        int sourceNeighbors = 0;

        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            long neighborKey = keyOffset(posKey, dir[0], 0, dir[1]);
            if (!isWithinWorld(unpackY(neighborKey))) {
                continue;
            }
            WaterBlock neighbor = cells.get(neighborKey);
            if (neighbor == null) {
                if (getBlockViaCco(neighborKey) != BlockType.WATER) {
                    continue;
                }
                neighbor = WaterBlock.source();
            }
            if (neighbor.isSource()) {
                sourceNeighbors++;
            }
            minNeighbor = Math.min(minNeighbor, neighbor.level());
        }

        if (sourceNeighbors >= 2 && FlowBlockInteraction.supportsSource(world, unpackX(posKey), unpackY(posKey), unpackZ(posKey))) {
            return WaterBlock.SOURCE_LEVEL;
        }

        WaterBlock above = cells.get(keyAbove(posKey));
        if (above != null) {
            return Math.min(above.level(), minNeighbor + 1);
        }

        // Edge case: All neighbors level 7 or weaker → remove this block.
        if (minNeighbor == WaterBlock.MAX_LEVEL) {
            return WaterBlock.EMPTY_LEVEL;
        }
        return Math.min(minNeighbor + 1, WaterBlock.MAX_LEVEL);
    }

    private void spreadHorizontally(long posKey, WaterBlock state) {
        if (state.falling()) {
            return;
        }

        int spreadLevel = state.isSource() ? 1 : state.level() + 1;
        if (spreadLevel > WaterBlock.MAX_LEVEL) {
            return;
        }

        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            long neighbor = keyOffset(posKey, dir[0], 0, dir[1]);

            if (tryFill(neighbor, WaterBlock.flowing(spreadLevel))) {
                scheduleNeighbors(neighbor);
            }
        }
    }

    private boolean tryFill(long posKey, WaterBlock candidate) {
        int posY = unpackY(posKey);
        if (!isWithinWorld(posY)) {
            return false;
        }

        BlockType blockType = getBlockViaCco(posKey);
        if (!FlowBlockInteraction.canDisplace(blockType)) {
            return false;
        }

        WaterBlock existing = cells.get(posKey);
        boolean levelChanged = false;

        if (existing != null) {
            if (existing.isSource() && !candidate.isSource()) {
                return false;
            }
            if (!candidate.isStrongerThan(existing)) {
                if (existing.falling() && !candidate.falling() && existing.level() == candidate.level()) {
                    // Landing water converting from falling to still at same level is allowed
                } else {
                    return false;
                }
            }
            // Mesh ignores `falling`; treat a pure flag flip as not a visual change.
            levelChanged = existing.level() != candidate.level();
        }

        if (FlowBlockInteraction.isFragile(blockType)) {
            FlowBlockInteraction.dropFragile(world, unpackX(posKey), posY, unpackZ(posKey), blockType);
            setBlockViaCco(posKey, BlockType.AIR);
        }

        boolean blockTypeChanged = (blockType != BlockType.WATER);
        if (blockTypeChanged) {
            setBlockViaCco(posKey, BlockType.WATER);
        }

        cells.put(posKey, candidate);
        enqueue(posKey, WATER_TICK_DELAY);

        if (levelChanged && !blockTypeChanged) {
            markChunkDirty(posKey);
        }

        return true;
    }

    private void removeWater(long posKey) {
        cells.remove(posKey);
        if (getBlockViaCco(posKey) == BlockType.WATER) {
            setBlockViaCco(posKey, BlockType.AIR);
        }
        scheduleNeighbors(posKey);
    }

    /**
     * Marks a chunk as needing a mesh rebuild due to water changes.
     * Updates are batched and applied at the end of each logical tick.
     */
    private void markChunkDirty(long posKey) {
        if (!isWithinWorld(unpackY(posKey))) {
            return;
        }
        int chunkX = Math.floorDiv(unpackX(posKey), WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(unpackZ(posKey), WorldConfiguration.CHUNK_SIZE);
        dirtyChunks.add(chunkKey(chunkX, chunkZ));
    }

    /**
     * Applies all batched mesh updates to chunks.
     * Called once per logical tick after processing all water updates.
     *
     * CRITICAL FIX: Only triggers mesh rebuild, does NOT mark chunks dirty for saving.
     * Water level changes are purely visual and should not trigger chunk saves.
     * Only actual block placement/removal (via setBlockViaCco) marks chunks dirty for saving.
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
                // ONLY trigger mesh rebuild - do NOT mark chunk dirty for saving
                // Water metadata is saved when actual blocks change (via CCO in setBlockViaCco)
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE;
                world.triggerChunkRebuild(worldX, 0, worldZ);
            }
        }

        dirtyChunks.clear();
    }

    private void scheduleNeighbors(long posKey) {
        enqueue(posKey, NEIGHBOR_TICK_DELAY);
        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            enqueue(keyOffset(posKey, dir[0], 0, dir[1]), NEIGHBOR_TICK_DELAY);
        }
        enqueue(keyAbove(posKey), WATER_TICK_DELAY);
        enqueue(keyBelow(posKey), WATER_TICK_DELAY);
    }

    private void enqueueImmediate(long posKey) {
        enqueue(posKey, IMMEDIATE_UPDATE_DELAY);
    }

    private void enqueue(long posKey, int delayTicks) {
        if (!isWithinWorld(unpackY(posKey))) {
            return;
        }

        int clampedDelay = Math.max(0, delayTicks);
        long scheduledTick = logicalTick + clampedDelay;

        Long existing = scheduledTicks.get(posKey);
        if (existing != null && existing <= scheduledTick) {
            return;
        }

        scheduledTicks.put(posKey, scheduledTick);
        synchronized (pendingUpdates) {
            pendingUpdates.add(new ScheduledUpdate(posKey, scheduledTick));
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private boolean canFlowInto(long posKey) {
        if (!isWithinWorld(unpackY(posKey))) {
            return false;
        }
        WaterBlock existing = cells.get(posKey);
        if (existing != null && existing.isSource()) {
            return false;
        }
        return FlowBlockInteraction.canDisplace(getBlockViaCco(posKey));
    }

    /**
     * Whether the cell at posKey is empty space water can occupy by falling.
     * Solid blocks and existing water (source or flow) are not space.
     */
    private boolean hasSpaceBelow(long posKey) {
        if (!isWithinWorld(unpackY(posKey))) {
            return false;
        }
        BlockType blockType = getBlockViaCco(posKey);
        if (blockType == BlockType.WATER) {
            return false;
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
        return cells.get(packKey(x, y, z));
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

    /**
     * Loads water metadata for a chunk from save data.
     * Called when a chunk is loaded from disk to restore water depth states.
     */
    public void loadWaterMetadata(int chunkX, int chunkZ, java.util.Map<String, com.stonebreak.world.save.model.ChunkData.WaterBlockData> waterMetadata) {
        if (waterMetadata.isEmpty()) {
            System.out.println("[WATER-LOAD] Chunk (" + chunkX + "," + chunkZ + "): No water metadata to load");
            return;
        }

        int loadedCount = 0;
        for (java.util.Map.Entry<String, com.stonebreak.world.save.model.ChunkData.WaterBlockData> entry : waterMetadata.entrySet()) {
            String[] coords = entry.getKey().split(",");
            int localX = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int localZ = Integer.parseInt(coords[2]);

            int worldX = chunkX * com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE + localX;
            int worldZ = chunkZ * com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE + localZ;

            WaterBlock loadedState = new WaterBlock(entry.getValue().level(), entry.getValue().falling());
            cells.put(packKey(worldX, y, worldZ), loadedState);
            loadedCount++;
        }

        System.out.println("[WATER-LOAD] Chunk (" + chunkX + "," + chunkZ + "): Loaded " + loadedCount + " flowing water blocks from save data");
    }

    // ===== CCO API HELPERS =====

    /**
     * Gets a cached CCO block reader for the specified chunk coordinates.
     * Caches readers to avoid repeated chunk lookups during water updates.
     */
    private CcoBlockReader getReaderForChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        CcoBlockReader reader = readerCache.get(key);
        if (reader == null) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk != null) {
                reader = chunk.getBlockReader();
                readerCache.put(key, reader);
            }
        }
        return reader;
    }

    /**
     * Gets a block type at world coordinates using CCO API.
     * More efficient than world.getBlockAt() for frequent access patterns.
     */
    private BlockType getBlockViaCco(long posKey) {
        int posY = unpackY(posKey);
        if (!isWithinWorld(posY)) {
            return BlockType.AIR;
        }

        int posX = unpackX(posKey);
        int posZ = unpackZ(posKey);
        int chunkX = Math.floorDiv(posX, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(posZ, WorldConfiguration.CHUNK_SIZE);
        CcoBlockReader reader = getReaderForChunk(chunkX, chunkZ);

        if (reader == null) {
            return BlockType.AIR;
        }

        int localX = Math.floorMod(posX, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(posZ, WorldConfiguration.CHUNK_SIZE);
        return (BlockType) reader.get(localX, posY, localZ);
    }

    /**
     * Sets a block type at world coordinates using CCO API.
     * CCO automatically marks the chunk dirty for mesh regeneration and saving.
     */
    private void setBlockViaCco(long posKey, BlockType type) {
        int posY = unpackY(posKey);
        if (!isWithinWorld(posY)) {
            return;
        }

        int posX = unpackX(posKey);
        int posZ = unpackZ(posKey);
        int chunkX = Math.floorDiv(posX, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(posZ, WorldConfiguration.CHUNK_SIZE);
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);

        if (chunk == null) {
            return;
        }

        int localX = Math.floorMod(posX, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(posZ, WorldConfiguration.CHUNK_SIZE);

        chunk.setBlock(localX, posY, localZ, type);
    }

    // ===== POSITION KEY PACKING =====
    // Packs (x, y, z) into a primitive long so map keys, queue entries, and
    // method parameters never allocate. Layout: [x:24][z:24][y:16], all signed.
    // The wider 16-bit y field lets keyBelow(y=0) and keyAbove(y=WORLD_HEIGHT-1)
    // produce out-of-bounds y values that round-trip correctly through unpackY,
    // so isWithinWorld() can reject them downstream. World x/z range is ±2^23.

    private static final int X_SHIFT = 40;
    private static final int Z_SHIFT = 16;
    private static final long XZ_MASK = 0xFFFFFFL;
    private static final long Y_MASK = 0xFFFFL;
    private static final int XZ_SIGN_BIT = 0x00800000;
    private static final int XZ_SIGN_EXT = 0xFF000000;
    private static final int Y_SIGN_BIT = 0x00008000;
    private static final int Y_SIGN_EXT = 0xFFFF0000;

    private static long packKey(int x, int y, int z) {
        return ((long)(x & 0xFFFFFF) << X_SHIFT)
             | ((long)(z & 0xFFFFFF) << Z_SHIFT)
             | (y & Y_MASK);
    }

    private static int unpackX(long key) {
        int x = (int)((key >>> X_SHIFT) & XZ_MASK);
        return (x & XZ_SIGN_BIT) != 0 ? x | XZ_SIGN_EXT : x;
    }

    private static int unpackY(long key) {
        int y = (int)(key & Y_MASK);
        return (y & Y_SIGN_BIT) != 0 ? y | Y_SIGN_EXT : y;
    }

    private static int unpackZ(long key) {
        int z = (int)((key >>> Z_SHIFT) & XZ_MASK);
        return (z & XZ_SIGN_BIT) != 0 ? z | XZ_SIGN_EXT : z;
    }

    private static long keyOffset(long key, int dx, int dy, int dz) {
        return packKey(unpackX(key) + dx, unpackY(key) + dy, unpackZ(key) + dz);
    }

    private static long keyAbove(long key) {
        return packKey(unpackX(key), unpackY(key) + 1, unpackZ(key));
    }

    private static long keyBelow(long key) {
        return packKey(unpackX(key), unpackY(key) - 1, unpackZ(key));
    }

    private record ScheduledUpdate(long posKey, long scheduledTick) { }
}
