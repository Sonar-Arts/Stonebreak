package com.stonebreak.blocks.waterSystem;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Vanilla-Minecraft water flow simulation over a {@link FlowWorld}.
 *
 * <p>State lives in the chunk-owned water layer (absence = source); this class
 * is a pure tick engine: a scheduled-update queue plus the vanilla rules —
 * sources spread level 1, flow loses one level per horizontal block (reach 7),
 * water over a flowable cell falls as a full-height column and spreads at full
 * source strength where it lands, flowing cells recompute from neighbors each
 * scheduled tick (which is also how flows recede when supply is cut), flow
 * seeks holes up to {@value #SLOPE_SEARCH_RANGE} blocks away, and a flowing
 * cell flanked by two sources over solid ground becomes a source itself.
 *
 * <p>Runs only on authoritative worlds — render-only (multiplayer client)
 * worlds never tick this engine or feed it block changes; they display
 * replicated layer values.
 */
public final class WaterSim {

    /** Scheduled-tick delay between flow steps (vanilla water cadence: 5 ticks @ 20 TPS). */
    public static final int FLOW_DELAY = 5;

    /** How far flowing water scans for a hole to prefer flowing toward. */
    public static final int SLOPE_SEARCH_RANGE = 4;

    private static final int MAX_UPDATES_PER_TICK = 256;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final float TICK_INTERVAL = 1.0f / 20.0f;
    private static final int NO_HOLE = Integer.MAX_VALUE;

    /** Sentinel "state" values shared with the layer encoding (0..8) plus EMPTY. */
    private static final int SOURCE = ChunkWaterLayer.SOURCE;
    private static final int FALLING = ChunkWaterLayer.FALLING;
    private static final int MAX_LEVEL = ChunkWaterLayer.MAX_FLOW_LEVEL;
    private static final int EMPTY = -1;

    private static final int[][] HORIZONTALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final FlowWorld flow;

    private final PriorityQueue<ScheduledUpdate> pendingUpdates = new PriorityQueue<>(
        (a, b) -> a.scheduledTick() != b.scheduledTick()
            ? Long.compare(a.scheduledTick(), b.scheduledTick())
            : Long.compare(a.sequence(), b.sequence()));
    private final Map<Long, Long> scheduledTicks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = new HashSet<>();

    private float tickAccumulator;
    private long logicalTick;
    private long sequenceCounter;

    public WaterSim(FlowWorld flow) {
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    // ===== Tick driving =====

    /** Frame-time entry point: accumulates delta and runs logical ticks at 20 TPS. */
    public void tick(float deltaTimeSeconds) {
        float delta = Float.isFinite(deltaTimeSeconds) ? Math.max(0.0f, deltaTimeSeconds) : 0.0f;
        tickAccumulator += delta;

        int ticksToRun = 0;
        while (tickAccumulator >= TICK_INTERVAL && ticksToRun < MAX_TICKS_PER_FRAME) {
            tickAccumulator -= TICK_INTERVAL;
            ticksToRun++;
        }
        if (ticksToRun > 0) {
            advanceTicks(ticksToRun);
        }
    }

    /** Advances whole logical ticks directly — deterministic driver for tests. */
    public void advanceTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            logicalTick++;
            processQueue(MAX_UPDATES_PER_TICK);
            flow.onTickComplete();
        }
    }

    public int getQueuedUpdateCount() {
        return scheduledTicks.size();
    }

    // ===== External triggers =====

    /** Schedules a position for evaluation after the standard flow delay. */
    public void schedule(int x, int y, int z) {
        enqueue(packKey(x, y, z), FLOW_DELAY);
    }

    /**
     * The single block-change funnel (called from World.setBlockAt only).
     * Placed water schedules itself (no layer entry = it is a source); any
     * change schedules adjacent water for recompute — recession when supply
     * or support was cut, renewed spread when an obstruction cleared.
     */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (!isWithinWorld(y)) {
            return;
        }
        if (next == BlockType.WATER) {
            schedule(x, y, z);
        }
        scheduleWaterNeighbors(packKey(x, y, z));
    }

    /**
     * Registers existing water when a chunk becomes resident. Schedules — never
     * seeds — WATER cells that are exposed (flowable orthogonal neighbor) or
     * carry a saved flow entry; ocean interiors are sources that never tick.
     * Border water in already-loaded adjacent chunks is re-scheduled so flow
     * stalled at the chunk edge resumes.
     */
    public void onChunkLoaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long chunkKey = chunkKey(chunk.getChunkX(), chunk.getChunkZ());
        if (!scannedChunks.add(chunkKey)) {
            return;
        }

        int baseX = chunk.getChunkX() * WorldConfiguration.CHUNK_SIZE;
        int baseZ = chunk.getChunkZ() * WorldConfiguration.CHUNK_SIZE;
        var reader = chunk.getBlockReader();

        chunk.getWaterLayer().forEach((lx, y, lz, value) -> schedule(baseX + lx, y, baseZ + lz));

        for (int lx = 0; lx < WorldConfiguration.CHUNK_SIZE; lx++) {
            for (int lz = 0; lz < WorldConfiguration.CHUNK_SIZE; lz++) {
                boolean border = lx == 0 || lx == WorldConfiguration.CHUNK_SIZE - 1
                              || lz == 0 || lz == WorldConfiguration.CHUNK_SIZE - 1;
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                    if (reader.get(lx, y, lz) != BlockType.WATER) {
                        continue;
                    }
                    if (isExposed(reader, lx, y, lz)) {
                        schedule(baseX + lx, y, baseZ + lz);
                    }
                    if (border) {
                        // Wake water just outside this chunk that stalled waiting for it.
                        for (int[] dir : HORIZONTALS) {
                            int nx = lx + dir[0];
                            int nz = lz + dir[1];
                            if (nx < 0 || nx >= WorldConfiguration.CHUNK_SIZE
                                || nz < 0 || nz >= WorldConfiguration.CHUNK_SIZE) {
                                int wx = baseX + nx;
                                int wz = baseZ + nz;
                                if (flow.getBlock(wx, y, wz) == BlockType.WATER) {
                                    schedule(wx, y, wz);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isExposed(com.openmason.engine.voxel.cco.operations.CcoBlockReader reader,
                                     int lx, int y, int lz) {
        if (y > 0 && reader.isAir(lx, y - 1, lz)) {
            return true;
        }
        return (lx > 0 && reader.isAir(lx - 1, y, lz))
            || (lx < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(lx + 1, y, lz))
            || (lz > 0 && reader.isAir(lx, y, lz - 1))
            || (lz < WorldConfiguration.CHUNK_SIZE - 1 && reader.isAir(lx, y, lz + 1));
    }

    /** Drops pending work for an unloading chunk. The water layer leaves with the chunk. */
    public void onChunkUnloaded(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        scannedChunks.remove(chunkKey(chunkX, chunkZ));

        synchronized (pendingUpdates) {
            Iterator<ScheduledUpdate> it = pendingUpdates.iterator();
            while (it.hasNext()) {
                ScheduledUpdate update = it.next();
                if (update != null && isInChunk(update.posKey(), chunkX, chunkZ)) {
                    it.remove();
                }
            }
        }
        scheduledTicks.keySet().removeIf(posKey -> isInChunk(posKey, chunkX, chunkZ));
        flow.onChunkUnloaded(chunkX, chunkZ);
    }

    // ===== Queue =====

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
            Long tracked = scheduledTicks.get(next.posKey());
            if (tracked == null || tracked != next.scheduledTick()) {
                continue; // superseded by an earlier reschedule
            }
            scheduledTicks.remove(next.posKey());
            onScheduledUpdate(next.posKey());
            processed++;
        }
    }

    private void enqueue(long posKey, int delayTicks) {
        if (!isWithinWorld(unpackY(posKey))) {
            return;
        }
        long scheduledTick = logicalTick + Math.max(0, delayTicks);
        Long existing = scheduledTicks.get(posKey);
        if (existing != null && existing <= scheduledTick) {
            return;
        }
        scheduledTicks.put(posKey, scheduledTick);
        synchronized (pendingUpdates) {
            pendingUpdates.add(new ScheduledUpdate(posKey, scheduledTick, sequenceCounter++));
        }
    }

    /** Schedules the six orthogonal neighbors that currently hold water. */
    private void scheduleWaterNeighbors(long posKey) {
        for (int[] dir : HORIZONTALS) {
            scheduleIfWater(keyOffset(posKey, dir[0], 0, dir[1]));
        }
        scheduleIfWater(keyOffset(posKey, 0, 1, 0));
        scheduleIfWater(keyOffset(posKey, 0, -1, 0));
    }

    private void scheduleIfWater(long posKey) {
        if (isWithinWorld(unpackY(posKey)) && blockAt(posKey) == BlockType.WATER) {
            enqueue(posKey, FLOW_DELAY);
        }
    }

    // ===== The vanilla update =====

    /**
     * Full update of one cell: recompute the state of non-source water from
     * its neighbors (dissipation, falling transitions, infinite-source rule),
     * then spread — down as falling water when the cell below is flowable,
     * otherwise horizontally toward the nearest hole.
     */
    private void onScheduledUpdate(long posKey) {
        if (blockAt(posKey) != BlockType.WATER) {
            return; // stale — Chunk.setBlock already cleared any layer entry
        }

        int state = waterAt(posKey);

        if (state != SOURCE) {
            int desired = computeState(posKey);
            if (desired == EMPTY) {
                setBlockAt(posKey, BlockType.AIR);
                markChanged(posKey, SOURCE);
                scheduleWaterNeighbors(posKey);
                return;
            }
            if (desired != state) {
                setWaterAt(posKey, desired);
                markChanged(posKey, desired);
                scheduleWaterNeighbors(posKey);
                enqueue(posKey, FLOW_DELAY); // keep advancing (e.g. landed column starts spreading)
                state = desired;
            }
        }

        long below = keyOffset(posKey, 0, -1, 0);
        if (canFlowInto(below)) {
            fill(below, FALLING);
            return; // water pouring down never spreads sideways
        }

        int spreadLevel = effectiveLevel(state) + 1; // source & landed falling → 1 (full strength)
        if (spreadLevel > MAX_LEVEL) {
            return;
        }
        int dirMask = pickFlowDirections(posKey);
        for (int i = 0; i < HORIZONTALS.length; i++) {
            if ((dirMask & (1 << i)) == 0) {
                continue;
            }
            long neighbor = keyOffset(posKey, HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
            fill(neighbor, spreadLevel);
        }
    }

    /**
     * Desired state of a non-source cell derived from its neighbors:
     * water above → falling; otherwise min horizontal supply + 1, drying up
     * when nothing within reach supplies it. A falling neighbor supplies at
     * full strength (vanilla: falling = amount 8) but never counts as a source,
     * and the infinite-source rule needs solid ground or a source below —
     * together these keep waterfall bases from minting sources.
     */
    private int computeState(long posKey) {
        int sourceNeighbors = 0;
        int minNeighbor = Integer.MAX_VALUE;
        for (int[] dir : HORIZONTALS) {
            long neighbor = keyOffset(posKey, dir[0], 0, dir[1]);
            int neighborState = waterAt(neighbor);
            if (neighborState == EMPTY) {
                continue;
            }
            if (neighborState == SOURCE) {
                sourceNeighbors++;
            }
            minNeighbor = Math.min(minNeighbor, effectiveLevel(neighborState));
        }

        long belowKey = keyOffset(posKey, 0, -1, 0);
        if (sourceNeighbors >= 2
            && (isSolidAt(belowKey) || waterAt(belowKey) == SOURCE)) {
            return SOURCE;
        }

        if (waterAt(keyOffset(posKey, 0, 1, 0)) != EMPTY) {
            return FALLING;
        }

        if (minNeighbor == Integer.MAX_VALUE || minNeighbor + 1 > MAX_LEVEL) {
            return EMPTY;
        }
        return minNeighbor + 1;
    }

    /**
     * Flows water into a cell if the candidate state is strictly stronger than
     * what is there. Breaks fragile blocks, writes WATER into the block array
     * when needed, and schedules the cell's own update.
     */
    private void fill(long posKey, int candidate) {
        if (!canFlowInto(posKey)) {
            return;
        }
        BlockType block = blockAt(posKey);
        if (block == BlockType.WATER && effectiveLevel(candidate) >= effectiveLevel(waterAt(posKey))) {
            return;
        }

        if (FlowBlockInteraction.isFragile(block)) {
            flow.dropFragile(unpackX(posKey), unpackY(posKey), unpackZ(posKey), block);
        }
        if (block != BlockType.WATER) {
            setBlockAt(posKey, BlockType.WATER);
        }
        setWaterAt(posKey, candidate);
        markChanged(posKey, candidate);
        enqueue(posKey, FLOW_DELAY);
    }

    // ===== Hole-seeking =====

    /**
     * Returns a bitmask over {@link #HORIZONTALS} of the directions water at
     * this cell should spread: the direction(s) with the shortest path (≤
     * {@value #SLOPE_SEARCH_RANGE}) to a hole, or every flowable direction
     * when no hole is within range.
     */
    private int pickFlowDirections(long posKey) {
        int bestDistance = NO_HOLE;
        int mask = 0;
        for (int i = 0; i < HORIZONTALS.length; i++) {
            long neighbor = keyOffset(posKey, HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
            if (!canFlowInto(neighbor)) {
                continue;
            }
            int distance = isHole(neighbor) ? 0 : slopeDistance(neighbor, 1, i);
            if (distance < bestDistance) {
                bestDistance = distance;
                mask = 1 << i;
            } else if (distance == bestDistance) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Shortest hop count to a hole reachable through flowable cells, or NO_HOLE. */
    private int slopeDistance(long posKey, int distance, int fromDirection) {
        if (distance >= SLOPE_SEARCH_RANGE) {
            return NO_HOLE;
        }
        int best = NO_HOLE;
        for (int i = 0; i < HORIZONTALS.length; i++) {
            if (isOpposite(i, fromDirection)) {
                continue;
            }
            long next = keyOffset(posKey, HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
            if (!canFlowInto(next)) {
                continue;
            }
            if (isHole(next)) {
                return distance;
            }
            best = Math.min(best, slopeDistance(next, distance + 1, i));
        }
        return best;
    }

    private static boolean isOpposite(int dirA, int dirB) {
        // HORIZONTALS pairs: (0,1) = ±x, (2,3) = ±z
        return (dirA ^ 1) == dirB && (dirA / 2) == (dirB / 2);
    }

    /** Whether water occupying this cell could fall out of it. */
    private boolean isHole(long posKey) {
        return canFlowInto(keyOffset(posKey, 0, -1, 0));
    }

    // ===== Cell predicates =====

    /** Whether water may flow into the cell: loaded, displaceable, and not a source. */
    private boolean canFlowInto(long posKey) {
        int y = unpackY(posKey);
        if (!isWithinWorld(y) || !flow.isLoaded(unpackX(posKey), y, unpackZ(posKey))) {
            return false;
        }
        BlockType block = blockAt(posKey);
        if (!FlowBlockInteraction.canDisplace(block)) {
            return false;
        }
        return !(block == BlockType.WATER && waterAt(posKey) == SOURCE);
    }

    /** Water state at the cell: EMPTY when not water, else the layer value. */
    private int waterAt(long posKey) {
        if (!isWithinWorld(unpackY(posKey)) || blockAt(posKey) != BlockType.WATER) {
            return EMPTY;
        }
        return flow.getWater(unpackX(posKey), unpackY(posKey), unpackZ(posKey));
    }

    /** Horizontal flow strength: sources and falling columns are full strength (0). */
    private static int effectiveLevel(int state) {
        return state == FALLING ? 0 : state;
    }

    private BlockType blockAt(long posKey) {
        return flow.getBlock(unpackX(posKey), unpackY(posKey), unpackZ(posKey));
    }

    private void setBlockAt(long posKey, BlockType type) {
        flow.setBlock(unpackX(posKey), unpackY(posKey), unpackZ(posKey), type);
    }

    private void setWaterAt(long posKey, int value) {
        flow.setWater(unpackX(posKey), unpackY(posKey), unpackZ(posKey), value);
    }

    private void markChanged(long posKey, int newValue) {
        flow.markWaterChanged(unpackX(posKey), unpackY(posKey), unpackZ(posKey), newValue);
    }

    private boolean isSolidAt(long posKey) {
        return isWithinWorld(unpackY(posKey))
            && flow.isSolid(unpackX(posKey), unpackY(posKey), unpackZ(posKey));
    }

    private static boolean isWithinWorld(int y) {
        return y >= 0 && y < WorldConfiguration.WORLD_HEIGHT;
    }

    private static boolean isInChunk(long posKey, int chunkX, int chunkZ) {
        return Math.floorDiv(unpackX(posKey), WorldConfiguration.CHUNK_SIZE) == chunkX
            && Math.floorDiv(unpackZ(posKey), WorldConfiguration.CHUNK_SIZE) == chunkZ;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    // ===== Position key packing =====
    // Packs (x, y, z) into a primitive long so map keys and queue entries never
    // allocate. Layout: [x:24][z:24][y:16], all signed; the 16-bit y field lets
    // one-step out-of-bounds offsets round-trip so isWithinWorld can reject them.

    private static final int X_SHIFT = 40;
    private static final int Z_SHIFT = 16;
    private static final long XZ_MASK = 0xFFFFFFL;
    private static final long Y_MASK = 0xFFFFL;
    private static final int XZ_SIGN_BIT = 0x00800000;
    private static final int XZ_SIGN_EXT = 0xFF000000;
    private static final int Y_SIGN_BIT = 0x00008000;
    private static final int Y_SIGN_EXT = 0xFFFF0000;

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << X_SHIFT)
             | ((long) (z & 0xFFFFFF) << Z_SHIFT)
             | (y & Y_MASK);
    }

    private static int unpackX(long key) {
        int x = (int) ((key >>> X_SHIFT) & XZ_MASK);
        return (x & XZ_SIGN_BIT) != 0 ? x | XZ_SIGN_EXT : x;
    }

    private static int unpackY(long key) {
        int y = (int) (key & Y_MASK);
        return (y & Y_SIGN_BIT) != 0 ? y | Y_SIGN_EXT : y;
    }

    private static int unpackZ(long key) {
        int z = (int) ((key >>> Z_SHIFT) & XZ_MASK);
        return (z & XZ_SIGN_BIT) != 0 ? z | XZ_SIGN_EXT : z;
    }

    private static long keyOffset(long key, int dx, int dy, int dz) {
        return packKey(unpackX(key) + dx, unpackY(key) + dy, unpackZ(key) + dz);
    }

    private record ScheduledUpdate(long posKey, long scheduledTick, long sequence) {
    }
}
