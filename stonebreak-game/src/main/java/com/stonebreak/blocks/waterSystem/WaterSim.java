package com.stonebreak.blocks.waterSystem;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Vanilla-Minecraft water flow simulation over a {@link FlowWorld}.
 *
 * <p>State lives in the world's WATER layer (0 = source); this class
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
    private final Map<Pos, Long> scheduledTicks = new ConcurrentHashMap<>();
    private final Set<CubePos> scannedCubes = new HashSet<>();
    /** Resident cubes waiting for their load scan, oldest first; drained by the logical tick. */
    private final java.util.LinkedHashSet<CubePos> pendingScans = new java.util.LinkedHashSet<>();
    /** Scratch block ids of the cube being scanned (tick thread only). */
    private final short[] scanIds = new short[CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE];
    /** Wall-clock budget per logical tick for load scans (at least one runs per tick). */
    private static final long SCAN_BUDGET_NANOS = 3_000_000L;

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
            processScans();
            processQueue(MAX_UPDATES_PER_TICK);
            flow.onTickComplete();
        }
    }

    public int getQueuedUpdateCount() {
        return scheduledTicks.size();
    }

    // ===== External triggers =====

    /** Schedules a position for evaluation after the standard flow delay. */
    public void schedule(long x, long y, long z) {
        enqueue(new Pos(x, y, z), FLOW_DELAY);
    }

    /**
     * The single block-change funnel (called from World.setBlockAt only).
     * Placed water schedules itself (no layer entry = it is a source); any
     * change schedules adjacent water for recompute — recession when supply
     * or support was cut, renewed spread when an obstruction cleared.
     */
    public void onBlockChanged(long x, long y, long z, BlockType previous, BlockType next) {
        if (!isWithinWorld(y)) {
            return;
        }
        if (next == BlockType.WATER) {
            schedule(x, y, z);
        }
        scheduleWaterNeighbors(new Pos(x, y, z));
    }

    /**
     * Registers existing water when a cube becomes resident. Schedules — never
     * seeds — WATER cells that are exposed (flowable neighbor inside the cube, or
     * air below) or carry a non-source water value; ocean interiors are sources
     * that never tick. Water in already-loaded neighbouring cubes that touches
     * this cube's faces is re-scheduled so flow stalled at the edge resumes.
     *
     * <p>The scan runs on the next logical tick under a time budget: a world
     * reload installs hundreds of cubes in one server tick, and scanning each
     * inline pushed that tick past its 50 ms (#345).
     */
    public void onCubeInstalled(Cube cube) {
        if (cube != null) {
            onCubeInstalled(cube.cx(), cube.cy(), cube.cz());
        }
    }

    /** {@link #onCubeInstalled(Cube)} by cube coordinates. */
    public void onCubeInstalled(long cx, long cy, long cz) {
        CubePos pos = new CubePos(cx, cy, cz);
        if (scannedCubes.add(pos)) {
            pendingScans.add(pos);
        }
    }

    private void processScans() {
        if (pendingScans.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        var it = pendingScans.iterator();
        while (it.hasNext()) {
            CubePos cube = it.next();
            it.remove();
            scanLoadedCube(cube);
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    private void scanLoadedCube(CubePos cube) {
        // Flowing / falling / river cells: the layer scan skips empty sections natively.
        flow.forEachWaterValue(cube.x(), cube.y(), cube.z(), (x, y, z, value) -> schedule(x, y, z));

        short[] ids = scanIds;
        if (!flow.readCubeIds(cube.x(), cube.y(), cube.z(), ids)) {
            return;
        }
        final short water = (short) BlockType.WATER.getId();
        boolean any = false;
        for (short id : ids) {
            if (id == water) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        final short air = (short) BlockType.AIR.getId();
        final int edge = CubeWorld.CUBE_EDGE;
        long x0 = cube.minBlockX(), y0 = cube.minBlockY(), z0 = cube.minBlockZ();
        for (int ly = 0; ly < edge; ly++) {
            for (int lz = 0; lz < edge; lz++) {
                for (int lx = 0; lx < edge; lx++) {
                    if (ids[CubeWorld.cellIndex(lx, ly, lz)] != water) {
                        continue;
                    }
                    long x = x0 + lx, y = y0 + ly, z = z0 + lz;
                    boolean airBelow = ly > 0
                        ? ids[CubeWorld.cellIndex(lx, ly - 1, lz)] == air
                        : isWithinWorld(y - 1) && flow.isLoaded(x, y - 1, z) && flow.getBlock(x, y - 1, z) == BlockType.AIR;
                    boolean exposed = airBelow
                        || (lx > 0 && ids[CubeWorld.cellIndex(lx - 1, ly, lz)] == air)
                        || (lx < edge - 1 && ids[CubeWorld.cellIndex(lx + 1, ly, lz)] == air)
                        || (lz > 0 && ids[CubeWorld.cellIndex(lx, ly, lz - 1)] == air)
                        || (lz < edge - 1 && ids[CubeWorld.cellIndex(lx, ly, lz + 1)] == air);
                    if (exposed) {
                        schedule(x, y, z);
                    }
                    // Wake water just outside this cube that stalled waiting for it.
                    if (lx == 0) wakeIfWater(x - 1, y, z);
                    if (lx == edge - 1) wakeIfWater(x + 1, y, z);
                    if (lz == 0) wakeIfWater(x, y, z - 1);
                    if (lz == edge - 1) wakeIfWater(x, y, z + 1);
                    if (ly == 0) wakeIfWater(x, y - 1, z);
                    if (ly == edge - 1) wakeIfWater(x, y + 1, z);
                }
            }
        }
    }

    private void wakeIfWater(long x, long y, long z) {
        if (isWithinWorld(y) && flow.getBlock(x, y, z) == BlockType.WATER) {
            schedule(x, y, z);
        }
    }

    /** Drops pending work for an unloading cube. The water layer leaves with the cube. */
    public void onCubeUnloaded(Cube cube) {
        if (cube != null) {
            onCubeUnloaded(cube.cx(), cube.cy(), cube.cz());
        }
    }

    /** {@link #onCubeUnloaded(Cube)} by cube coordinates. */
    public void onCubeUnloaded(long cx, long cy, long cz) {
        CubePos pos = new CubePos(cx, cy, cz);
        scannedCubes.remove(pos);
        pendingScans.remove(pos);

        synchronized (pendingUpdates) {
            Iterator<ScheduledUpdate> it = pendingUpdates.iterator();
            while (it.hasNext()) {
                ScheduledUpdate update = it.next();
                if (update != null && update.pos().inCube(cx, cy, cz)) {
                    it.remove();
                }
            }
        }
        scheduledTicks.keySet().removeIf(p -> p.inCube(cx, cy, cz));
        flow.onCubeUnloaded(cx, cy, cz);
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
            Long tracked = scheduledTicks.get(next.pos());
            if (tracked == null || tracked != next.scheduledTick()) {
                continue; // superseded by an earlier reschedule
            }
            scheduledTicks.remove(next.pos());
            onScheduledUpdate(next.pos());
            processed++;
        }
    }

    private void enqueue(Pos posKey, int delayTicks) {
        if (!isWithinWorld(posKey.y())) {
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
    private void scheduleWaterNeighbors(Pos posKey) {
        for (int[] dir : HORIZONTALS) {
            scheduleIfWater(posKey.offset(dir[0], 0, dir[1]));
        }
        scheduleIfWater(posKey.offset(0, 1, 0));
        scheduleIfWater(posKey.offset(0, -1, 0));
    }

    private void scheduleIfWater(Pos posKey) {
        if (isWithinWorld(posKey.y()) && blockAt(posKey) == BlockType.WATER) {
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
    private void onScheduledUpdate(Pos posKey) {
        if (blockAt(posKey) != BlockType.WATER) {
            return; // stale — the block write already cleared any layer entry
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

        Pos below = posKey.offset(0, -1, 0);
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
            Pos neighbor = posKey.offset(HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
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
    private int computeState(Pos posKey) {
        int sourceNeighbors = 0;
        int minNeighbor = Integer.MAX_VALUE;
        for (int[] dir : HORIZONTALS) {
            Pos neighbor = posKey.offset(dir[0], 0, dir[1]);
            int neighborState = waterAt(neighbor);
            if (neighborState == EMPTY) {
                continue;
            }
            if (neighborState == SOURCE) {
                sourceNeighbors++;
            }
            minNeighbor = Math.min(minNeighbor, effectiveLevel(neighborState));
        }

        Pos belowKey = posKey.offset(0, -1, 0);
        if (sourceNeighbors >= 2
            && (isSolidAt(belowKey) || waterAt(belowKey) == SOURCE)) {
            return SOURCE;
        }

        if (waterAt(posKey.offset(0, 1, 0)) != EMPTY) {
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
    private void fill(Pos posKey, int candidate) {
        if (!canFlowInto(posKey)) {
            return;
        }
        BlockType block = blockAt(posKey);
        if (block == BlockType.WATER && effectiveLevel(candidate) >= effectiveLevel(waterAt(posKey))) {
            return;
        }

        if (FlowBlockInteraction.isFragile(block)) {
            flow.dropFragile(posKey.x(), posKey.y(), posKey.z(), block);
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
    private int pickFlowDirections(Pos posKey) {
        int bestDistance = NO_HOLE;
        int mask = 0;
        for (int i = 0; i < HORIZONTALS.length; i++) {
            Pos neighbor = posKey.offset(HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
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
    private int slopeDistance(Pos posKey, int distance, int fromDirection) {
        if (distance >= SLOPE_SEARCH_RANGE) {
            return NO_HOLE;
        }
        int best = NO_HOLE;
        for (int i = 0; i < HORIZONTALS.length; i++) {
            if (isOpposite(i, fromDirection)) {
                continue;
            }
            Pos next = posKey.offset(HORIZONTALS[i][0], 0, HORIZONTALS[i][1]);
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
    private boolean isHole(Pos posKey) {
        return canFlowInto(posKey.offset(0, -1, 0));
    }

    // ===== Cell predicates =====

    /** Whether water may flow into the cell: loaded, displaceable, and not a source. */
    private boolean canFlowInto(Pos posKey) {
        long y = posKey.y();
        if (!isWithinWorld(y) || !flow.isLoaded(posKey.x(), y, posKey.z())) {
            return false;
        }
        BlockType block = blockAt(posKey);
        if (!FlowBlockInteraction.canDisplace(block)) {
            return false;
        }
        return !(block == BlockType.WATER && waterAt(posKey) == SOURCE);
    }

    /**
     * Water state at the cell: EMPTY when not water, else the layer value in
     * the 0..8 vocabulary this engine's rules are written in.
     *
     * <p>A worldgen river surface ({@link ChunkWaterLayer#RIVER}+octant) is a
     * SOURCE that also records which way it runs, and it is normalised here so
     * that it reads as one EVERYWHERE — spread, dissipation, the hole search,
     * the infinite-source rule, {@link #canFlowInto}. That is deliberate and it
     * is the whole safety argument for the marker: the flow rules never see it,
     * so nothing about how far water travels changes, and the containment the
     * water kernel's banks are measured against still holds exactly. The marker
     * survives the sim for the same reason — a cell that reads as a source is
     * never rewritten by {@link #onScheduledUpdate}.
     */
    private int waterAt(Pos posKey) {
        if (!isWithinWorld(posKey.y()) || blockAt(posKey) != BlockType.WATER) {
            return EMPTY;
        }
        return ChunkWaterLayer.level(
            flow.getWater(posKey.x(), posKey.y(), posKey.z()));
    }

    /** Horizontal flow strength: sources and falling columns are full strength (0). */
    private static int effectiveLevel(int state) {
        return state == FALLING ? 0 : state;
    }

    private BlockType blockAt(Pos posKey) {
        return flow.getBlock(posKey.x(), posKey.y(), posKey.z());
    }

    private void setBlockAt(Pos posKey, BlockType type) {
        flow.setBlock(posKey.x(), posKey.y(), posKey.z(), type);
    }

    private void setWaterAt(Pos posKey, int value) {
        flow.setWater(posKey.x(), posKey.y(), posKey.z(), value);
    }

    private void markChanged(Pos posKey, int newValue) {
        flow.markWaterChanged(posKey.x(), posKey.y(), posKey.z(), newValue);
    }

    private boolean isSolidAt(Pos posKey) {
        return isWithinWorld(posKey.y())
            && flow.isSolid(posKey.x(), posKey.y(), posKey.z());
    }

    private static boolean isWithinWorld(long y) {
        return WorldConfiguration.inBounds(y);
    }

    /** A block position: full 64-bit triple (no packed keys), hashed like the engine's cube keys. */
    private record Pos(long x, long y, long z) {
        Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }

        boolean inCube(long cx, long cy, long cz) {
            return (x >> 5) == cx && (y >> 5) == cy && (z >> 5) == cz;
        }

        @Override
        public int hashCode() {
            return (int) CubePos.hash(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }
    }

    private record ScheduledUpdate(Pos pos, long scheduledTick, long sequence) {
    }
}
