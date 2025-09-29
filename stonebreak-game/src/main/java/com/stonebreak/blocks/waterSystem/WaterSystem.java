package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Self-contained water simulation that mirrors Minecraft's water rules while
 * keeping the implementation intentionally small and readable. The system only
 * tracks a minimal per-block state (level + falling) and recalculates flow
 * lazily through an update queue.
 */
public final class WaterSystem {

    private static final int MAX_UPDATES_PER_TICK = 512;
    private static final int[][] HORIZONTAL_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final float LEVEL_NORMALIZER = WaterBlock.MAX_LEVEL + 1.0f;

    private final World world;
    private final Map<BlockPos, WaterBlock> cells = new HashMap<>();
    private final Deque<BlockPos> pendingUpdates = new ArrayDeque<>();
    private final Set<BlockPos> queued = new HashSet<>();
    private final Set<Long> scannedChunks = new HashSet<>();

    private int suppressedCallbacks;

    public WaterSystem(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Processes pending water updates with a default per-frame budget.
     */
    public void tick() {
        processQueue(MAX_UPDATES_PER_TICK);
    }

    /**
     * Processes pending water updates using the supplied budget.
     */
    public void tick(int budget) {
        processQueue(Math.max(0, budget));
    }

    /**
     * Registers existing water when a chunk finishes generation.
     */
    public void onChunkLoaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long key = chunkKey(chunk.getChunkX(), chunk.getChunkZ());
        if (!scannedChunks.add(key)) {
            return; // Already scanned this chunk
        }

        BlockType[][][] blocks = chunk.getChunkData().getBlocks();
        for (int localX = 0; localX < WorldConfiguration.CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < WorldConfiguration.CHUNK_SIZE; localZ++) {
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                    if (blocks[localX][y][localZ] == BlockType.WATER) {
                        int worldX = chunk.getChunkX() * WorldConfiguration.CHUNK_SIZE + localX;
                        int worldZ = chunk.getChunkZ() * WorldConfiguration.CHUNK_SIZE + localZ;
                        BlockPos pos = new BlockPos(worldX, y, worldZ);
                        cells.put(pos, WaterBlock.source());
                        enqueue(pos);
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

        pendingUpdates.removeIf(pos ->
            Math.floorDiv(pos.x(), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(pos.z(), WorldConfiguration.CHUNK_SIZE) == chunkZ);
        queued.removeIf(pos ->
            Math.floorDiv(pos.x(), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(pos.z(), WorldConfiguration.CHUNK_SIZE) == chunkZ);
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
            cells.putIfAbsent(pos, WaterBlock.source());
            enqueue(pos);
            scheduleNeighbors(pos);
            return;
        }

        WaterBlock removed = cells.remove(pos);
        if (removed != null || previous == BlockType.WATER) {
            enqueue(pos);
            scheduleNeighbors(pos);
        }
    }

    /**
     * Schedules a specific position for re-evaluation.
     */
    public void queueUpdate(int x, int y, int z) {
        if (isWithinWorld(y)) {
            enqueue(new BlockPos(x, y, z));
        }
    }

    private void processQueue(int budget) {
        int processed = 0;
        while (processed < budget && !pendingUpdates.isEmpty()) {
            BlockPos pos = pendingUpdates.poll();
            queued.remove(pos);
            updateCell(pos);
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

        boolean canFall = tryFlowDown(pos, current);
        if (!canFall && current.falling()) {
            current = current.withoutFalling();
            cells.put(pos, current);
        }

        int targetLevel = computeTargetLevel(pos, current);

        if (!current.isSource() && targetLevel >= WaterBlock.EMPTY_LEVEL) {
            removeWater(pos);
            return;
        }

        int clampedLevel = Math.min(targetLevel, WaterBlock.MAX_LEVEL);
        boolean shouldFall = canFall && clampedLevel > WaterBlock.SOURCE_LEVEL;
        WaterBlock updated = new WaterBlock(clampedLevel, shouldFall);

        if (!updated.equals(current)) {
            cells.put(pos, updated);
            scheduleNeighbors(pos);
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
        if (!canFlowInto(below)) {
            return false;
        }

        int level = current.isSource() ? 1 : Math.max(1, current.level());
        boolean filled = tryFill(below, WaterBlock.falling(level));
        if (filled) {
            scheduleNeighbors(below);
            return true;
        }

        WaterBlock existing = cells.get(below);
        return existing != null && existing.falling();
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
        if (above != null && above.falling()) {
            return Math.min(above.level(), minNeighbor + 1);
        }

        if (minNeighbor == WaterBlock.MAX_LEVEL) {
            return WaterBlock.EMPTY_LEVEL;
        }
        return Math.min(minNeighbor + 1, WaterBlock.MAX_LEVEL);
    }

    private void spreadHorizontally(BlockPos pos, WaterBlock state) {
        if (state.falling()) {
            return; // Falling water does not spread sideways until it lands
        }

        int spreadLevel = state.isSource() ? 1 : Math.min(state.level() + 1, WaterBlock.MAX_LEVEL);
        if (spreadLevel > WaterBlock.MAX_LEVEL) {
            return;
        }

        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            BlockPos neighbor = pos.offset(dir[0], 0, dir[1]);
            if (tryFill(neighbor, new WaterBlock(spreadLevel, false))) {
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
        if (existing != null) {
            if (existing.isSource()) {
                return false; // Never replace a true source block
            }
            if (!candidate.isStrongerThan(existing)) {
                if (existing.falling() && !candidate.falling() && existing.level() == candidate.level()) {
                    // Landing water converting from falling to still at same level is allowed
                } else {
                    return false;
                }
            }
        }

        if (FlowBlockInteraction.isFragile(blockType)) {
            FlowBlockInteraction.dropFragile(world, pos.x(), pos.y(), pos.z(), blockType);
            setBlockType(pos, BlockType.AIR);
        }

        if (blockType != BlockType.WATER) {
            setBlockType(pos, BlockType.WATER);
        }

        cells.put(pos, candidate);
        enqueue(pos);
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

    private void enqueue(BlockPos pos) {
        if (!isWithinWorld(pos.y())) {
            return;
        }
        if (queued.add(pos)) {
            pendingUpdates.add(pos);
        }
    }

    private void scheduleNeighbors(BlockPos pos) {
        enqueue(pos);
        for (int[] dir : HORIZONTAL_DIRECTIONS) {
            enqueue(pos.offset(dir[0], 0, dir[1]));
        }
        enqueue(pos.above());
        enqueue(pos.below());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private boolean canFlowInto(BlockPos pos) {
        return isWithinWorld(pos.y()) && FlowBlockInteraction.canDisplace(world.getBlockAt(pos.x(), pos.y(), pos.z()));
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
}
