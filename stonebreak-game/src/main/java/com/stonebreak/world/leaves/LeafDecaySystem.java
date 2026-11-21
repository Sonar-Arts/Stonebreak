package com.stonebreak.world.leaves;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.nav.NavFlood;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.physics.WorldPhysics;

/**
 * Vanilla-Minecraft-style leaf decay over a {@link LeafWorld}. A leaf is
 * "anchored" while it remains 6-connected to a log through foliage or trunks
 * within {@value #DECAY_RADIUS} orthogonal steps — modern vanilla leaf distance
 * (leaves at distance 7 decay), so a leaf that only touches a log diagonally,
 * with no orthogonal leaf/log chain, is NOT anchored and decays. The radius
 * must cover the widest generated canopy: elms put leaves up to six steps from
 * their nearest log (see {@code ElmTree}), which the old radius of 4 silently
 * decayed on every cube-load rescan. Once its connection to an anchor is cut it decays
 * — fast, but staggered across a window of a few tenths of a second so the
 * player watches the canopy visibly collapse instead of vanishing instantly.
 *
 * <p><b>Event-driven.</b> Reachability is only ever re-evaluated on a block
 * update that can change support: a log removed, a leaf removed (it may have
 * been the bridge holding the rest of a canopy), or a leaf placed detached
 * (hand-placed floating leaf). Each such update schedules one delayed
 * reachability pass at that position; the pass uses the engine's flood
 * ({@link NavFlood}, Open Mason {@code cn_flood}, over the world's physics
 * cache, where leaves and logs carry {@link WorldPhysics#LEAVES} /
 * {@link WorldPhysics#LOG}) to find the candidate leaves and the remaining log
 * anchors that still hold them.
 *
 * <p><b>Passability mirrors vanilla.</b> Only leaves and logs are entered, along
 * the six orthogonal directions, one step each: air is a wall and diagonals do
 * not connect, so a leaf that merely sits diagonal to a log, with no orthogonal
 * leaf/log chain to it, is unreachable and decays. The flood's start is always
 * expanded whatever it now holds, which is how a removed log's cell still
 * reaches the canopy it held. This is the "check that
 * only fires once upon block updates" the feature asks for — idle worlds spend
 * nothing. The one non-event entry point is {@link #onCubeLoaded}: pending
 * work is dropped on cube unload (and lost on quit), so a cube becoming
 * resident is rescanned once per residency for leaves that cannot reach a log,
 * resuming any collapse that was interrupted mid-cascade.
 *
 * <p><b>Never decides on partial information.</b> Unloaded cells read as AIR,
 * which would make a log just across an evicted seam look absent — so
 * {@link #decayLeaf} refuses to remove a leaf unless every cell its anchor flood
 * can touch is known (resident cube or known air). A recompute may run with
 * partial information (it only schedules); the final check is always complete.
 *
 * <p><b>Positions</b> are full {@code long} triples ({@link Cell}); cubes are
 * {@link CubePos} (no packed keys, #447).
 *
 * <p><b>Runs only on authoritative worlds.</b> Render-only (multiplayer client)
 * worlds never tick this engine or feed it block changes; they display the
 * replicated decays streamed from the server.
 */
public final class LeafDecaySystem {

    /** Farthest a leaf may be from a log and still be anchored (modern vanilla leaf
     *  distance: 7 decays, so 6 is the last supported step). */
    public static final int DECAY_RADIUS = 6;

    /**
     * How far the anchor flood reaches from a trigger. Any log that can support
     * any candidate is within this: a candidate is at most {@link #DECAY_RADIUS}
     * flood-steps from the trigger, and its anchor at most {@link #DECAY_RADIUS}
     * more, and flood paths concatenate.
     */
    private static final int ANCHOR_SCAN_RADIUS = DECAY_RADIUS * 2;

    /** Hard cap on expanded cells per flood. A fully foliated 6-connected ball of
     *  radius {@link #ANCHOR_SCAN_RADIUS} (=12) has ~2.5k cells, so this leaves
     *  headroom for the anchor scan in a dense forest without truncating it. */
    private static final int SEARCH_EXPANSION_LIMIT = 8192;

    /** Delay (20 TPS ticks) between a block update and the reachability pass. */
    private static final int EVICT_DELAY_TICKS = 3;

    /** Base delay before a scheduled leaf actually disappears. */
    private static final int DECAY_DELAY_BASE_TICKS = 8;

    /** Per-leaf random-looking extra delay (deterministic hash, 0..JITTER) so the
     *  cascade is staggered and the player sees leaves go one by one. */
    private static final int DECAY_DELAY_JITTER_TICKS = 24;

    /** Bounded work per logical tick: recomputes first, then due decays. */
    private static final int MAX_RECOMPUTES_PER_TICK = 4;
    private static final int MAX_DECAY_PER_TICK = 12;
    private static final int MAX_TICKS_PER_FRAME = 2;

    /** More than this many anchors means a dense forest region — fall back to a
     *  cheap Manhattan support check instead of one flood per log. */
    private static final int MAX_ANCHOR_FLOODS = 24;

    private static final float TICK_INTERVAL = 1.0f / 20.0f;

    /** What the flood may enter: foliage and trunks. */
    private static final int FOLIAGE_MASK = WorldPhysics.LEAVES | WorldPhysics.LOG;

    private final LeafWorld world;
    /** Created on first use (it needs the native library; the scheduling paths do not). */
    private NavFlood flood;

    // Delayed reachability re-evaluations, keyed by position (deduped; the
    // earliest due tick wins — a later schedule for the same cell is dropped).
    private final PriorityQueue<ScheduledUpdate> pendingRecomputes = new PriorityQueue<>(SCHEDULER);
    private final Map<Cell, Long> scheduledRecomputes = new ConcurrentHashMap<>();

    // Leaves waiting to disappear (staggered within the decay window).
    private final PriorityQueue<ScheduledUpdate> pendingDecays = new PriorityQueue<>(SCHEDULER);
    private final Map<Cell, Long> scheduledDecays = new ConcurrentHashMap<>();

    // Cubes rescanned this residency (mirrors WaterSim's scanned cubes).
    private final Set<CubePos> scannedCubes = new HashSet<>();
    // Cubes waiting for their load rescan; drained by the logical tick under a time budget.
    private final java.util.ArrayDeque<CubePos> pendingRescans = new java.util.ArrayDeque<>();

    /** Wall-clock budget per logical tick for cube-load rescans (at least one runs per tick). */
    private static final long RESCAN_BUDGET_NANOS = 2_000_000L;

    private float tickAccumulator;
    private long logicalTick;
    private long sequenceCounter;

    public LeafDecaySystem(LeafWorld world) {
        this.world = Objects.requireNonNull(world, "world");
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
            processRescans();
            processRecomputes(MAX_RECOMPUTES_PER_TICK);
            processDecays(MAX_DECAY_PER_TICK);
            world.onTickComplete();
        }
    }

    public int getQueuedRecomputeCount() {
        return scheduledRecomputes.size();
    }

    public int getQueuedDecayCount() {
        return scheduledDecays.size();
    }

    // ===== External triggers =====

    /**
     * The single block-change funnel (called from World.setBlockAt, authoritative
     * worlds only). Fires only on the three updates that can change leaf support:
     * a log removed, a leaf removed (support routes <em>through</em> foliage, so
     * a broken leaf may have been the bridge holding the rest of a canopy), or a
     * leaf placed detached. Log placement and every other change are ignored —
     * the idle cost is zero.
     */
    public void onBlockChanged(long x, long y, long z, BlockType previous, BlockType next) {
        if (previous != null && previous.isLog()) {
            if (next != null && next.isLog()) {
                return; // log replaced by log — support unchanged
            }
            scheduleRecompute(x, y, z); // log gone — its former canopy is suspect
            return;
        }
        if (previous != null && previous.isLeaves()) {
            if (next != null && next.isLeaves()) {
                return; // leaf swapped for a leaf — support unchanged
            }
            scheduleRecompute(x, y, z); // leaf gone — anything it bridged is suspect
            return;
        }
        if (next != null && next.isLeaves()) {
            scheduleRecompute(x, y, z); // hand-placed/detached leaf — verify it is anchored
        }
    }

    /**
     * Rescan of a cube that just became resident (authoritative worlds; called
     * once per residency, guarded like WaterSim's scanned cubes). Pending work
     * is dropped on unload and lost on quit, so a collapse interrupted
     * mid-cascade would otherwise freeze forever — this pass schedules a
     * reachability recompute for every in-cube leaf that cannot reach a log
     * through in-cube foliage. Cheap where it matters: uniform cubes (air,
     * stone) are skipped without reading cells, freshly generated cubes hold
     * only complete populated trees, and a healthy tree's leaves all reach their
     * own trunk, scheduling nothing. Seam-supported leaves (trunk in the
     * neighbour cube) do get scheduled, and their recompute — which floods
     * across the seam — finds the trunk and decays nothing.
     *
     * <p>The scan itself runs on the next logical tick, under a time budget: a
     * world reload installs thousands of cubes in one server tick (#345).
     */
    public void onCubeLoaded(CubePos pos) {
        if (scannedCubes.add(pos)) {
            pendingRescans.add(pos);
        }
    }

    /** {@link #onCubeLoaded} for the world's install event. */
    public void onCubeInstalled(Cube cube) {
        onCubeLoaded(cube.pos());
    }

    private void processRescans() {
        if (pendingRescans.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + RESCAN_BUDGET_NANOS;
        CubePos pos;
        while ((pos = pendingRescans.poll()) != null) {
            if (scannedCubes.contains(pos)) { // dropped on unload: the cube is gone
                rescanCube(pos);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    private void rescanCube(CubePos pos) {
        BlockType[] blocks = world.cubeBlocks(pos);
        if (blocks == null) {
            return; // not resident, or provably no leaf and no log
        }
        long bx = pos.minBlockX(), by = pos.minBlockY(), bz = pos.minBlockZ();

        // One pass over the cube: the passable network (leaves + logs) and the
        // BFS sources (logs), as cube-local cell indices.
        boolean[] foliage = new boolean[blocks.length];
        java.util.List<Integer> leaves = new java.util.ArrayList<>();
        java.util.ArrayDeque<Integer> frontier = new java.util.ArrayDeque<>();
        for (int cell = 0; cell < blocks.length; cell++) {
            BlockType block = blocks[cell];
            if (block == null) {
                continue;
            }
            if (block.isLeaves()) {
                foliage[cell] = true;
                leaves.add(cell);
            } else if (block.isLog()) {
                foliage[cell] = true;
                frontier.add(cell);
            }
        }
        if (leaves.isEmpty()) {
            return;
        }

        // Multi-source BFS from the logs, depth-capped at the decay radius,
        // confined to the in-cube network by construction. Leaves it reaches
        // are provably supported.
        boolean[] reached = new boolean[blocks.length];
        for (int cell : frontier) {
            reached[cell] = true;
        }
        for (int depth = 0; depth < DECAY_RADIUS && !frontier.isEmpty(); depth++) {
            int layer = frontier.size();
            for (int i = 0; i < layer; i++) {
                int node = frontier.poll();
                int lx = Cube.localX(node), ly = Cube.localY(node), lz = Cube.localZ(node);
                for (int[] dir : ORTHOGONALS) {
                    int nx = lx + dir[0], ny = ly + dir[1], nz = lz + dir[2];
                    if (nx < 0 || nx > 31 || ny < 0 || ny > 31 || nz < 0 || nz > 31) {
                        continue;
                    }
                    int neighbor = CubeWorld.cellIndex(nx, ny, nz);
                    if (foliage[neighbor] && !reached[neighbor]) {
                        reached[neighbor] = true;
                        frontier.add(neighbor);
                    }
                }
            }
        }

        for (int leaf : leaves) {
            if (!reached[leaf]) {
                Cell c = new Cell(bx + Cube.localX(leaf), by + Cube.localY(leaf), bz + Cube.localZ(leaf));
                enqueue(pendingRecomputes, scheduledRecomputes, c, EVICT_DELAY_TICKS);
            }
        }
    }

    /** Drops pending work for an unloading cube (mirrors WaterSim). */
    public void onCubeUnloaded(CubePos pos) {
        scannedCubes.remove(pos);
        purgeCube(pendingRecomputes, scheduledRecomputes, pos);
        purgeCube(pendingDecays, scheduledDecays, pos);
        world.onCubeUnloaded(pos);
    }

    /** {@link #onCubeUnloaded(CubePos)} for the world's unload event. */
    public void onCubeUnloaded(Cube cube) {
        onCubeUnloaded(cube.pos());
    }

    // ===== Queues =====

    private void processRecomputes(int budget) {
        int processed = 0;
        while (processed < budget) {
            ScheduledUpdate next;
            synchronized (pendingRecomputes) {
                next = pendingRecomputes.peek();
                if (next == null || next.dueTick() > logicalTick) {
                    break;
                }
                pendingRecomputes.poll();
            }
            Long tracked = scheduledRecomputes.get(next.pos());
            if (tracked == null || tracked != next.dueTick()) {
                continue; // superseded by an earlier reschedule
            }
            scheduledRecomputes.remove(next.pos());
            recompute(next.pos());
            processed++;
        }
    }

    private void processDecays(int budget) {
        int processed = 0;
        while (processed < budget) {
            ScheduledUpdate next;
            synchronized (pendingDecays) {
                next = pendingDecays.peek();
                if (next == null || next.dueTick() > logicalTick) {
                    break;
                }
                pendingDecays.poll();
            }
            Long tracked = scheduledDecays.get(next.pos());
            if (tracked == null || tracked != next.dueTick()) {
                continue; // superseded
            }
            scheduledDecays.remove(next.pos());
            decayLeaf(next.pos());
            processed++;
        }
    }

    private void scheduleRecompute(long x, long y, long z) {
        enqueue(pendingRecomputes, scheduledRecomputes, new Cell(x, y, z), EVICT_DELAY_TICKS);
    }

    private void scheduleDecay(Cell pos) {
        if (world.isPersistent(pos.x(), pos.y(), pos.z())) {
            return; // player-placed leaves never decay
        }
        long h = pos.mix();
        int jitter = Math.floorMod((int) (h ^ (h >>> 32)), DECAY_DELAY_JITTER_TICKS + 1);
        enqueue(pendingDecays, scheduledDecays, pos, DECAY_DELAY_BASE_TICKS + jitter);
    }

    private void enqueue(PriorityQueue<ScheduledUpdate> queue, Map<Cell, Long> tracked, Cell pos, int delayTicks) {
        long dueTick = logicalTick + Math.max(0, delayTicks);
        Long existing = tracked.get(pos);
        if (existing != null && existing <= dueTick) {
            return; // already scheduled to fire no later
        }
        tracked.put(pos, dueTick);
        synchronized (queue) {
            queue.add(new ScheduledUpdate(pos, dueTick, sequenceCounter++));
        }
    }

    // ===== Reachability (the flood pass) =====

    /**
     * One reachability pass over the foliage region a trigger touched.
     *
     * <ol>
     *   <li><b>Candidates</b> — A* flood from the trigger through foliage,
     *       radius {@link #DECAY_RADIUS}; every leaf reached depends on whatever
     *       used to sit at the trigger cell.</li>
     *   <li><b>Anchors</b> — every log a radius-{@link #ANCHOR_SCAN_RADIUS} flood
     *       from the trigger reaches. Complete by path concatenation: a log that
     *       supports any candidate is within candidate-distance + support-distance
     *       ≤ 2×{@link #DECAY_RADIUS} flood-steps of the trigger.</li>
     *   <li><b>Supported</b> — one flood per anchor (bounded by
     *       {@link #MAX_ANCHOR_FLOODS}, with a Manhattan fallback past that) marks
     *       the leaves the anchors still hold.</li>
     * </ol>
     *
     * Candidates the anchors no longer hold are scheduled for staggered decay, and
     * each removal re-triggers a pass so the collapse climbs the cluster.
     */
    private void recompute(Cell pos) {
        Set<Cell> candidates = floodLeaves(pos);
        if (candidates.isEmpty()) {
            return;
        }

        Cell[] anchors = scanAnchors(pos);
        Set<Cell> supported = new HashSet<>();

        if (anchors.length == 0) {
            // No log within reach of the whole region — every candidate decays.
            for (Cell candidate : candidates) {
                scheduleDecay(candidate);
            }
            return;
        }

        if (anchors.length <= MAX_ANCHOR_FLOODS) {
            for (Cell anchor : anchors) {
                supported.addAll(floodLeaves(anchor));
            }
        } else {
            // Dense forest — a flood per log is too costly, so fall back to a
            // Manhattan probe. It ignores wall blockers, so it can only over-keep
            // (under-decay) a leaf, never wrongly decay one; an acceptable
            // conservative trade for a pathological case.
            for (Cell candidate : candidates) {
                if (withinManhattanAny(candidate, anchors)) {
                    supported.add(candidate);
                }
            }
        }

        for (Cell candidate : candidates) {
            if (!supported.contains(candidate)) {
                scheduleDecay(candidate);
            }
        }
    }

    /** Bounded flood through foliage; returns the leaves it reached, in the order it reached them. */
    private Set<Cell> floodLeaves(Cell start) {
        NavFlood f = flood(start, DECAY_RADIUS);
        Set<Cell> leaves = new java.util.LinkedHashSet<>();
        for (int i = 0; i < f.count(); i++) {
            if ((f.category(i) & WorldPhysics.LEAVES) != 0) {
                leaves.add(new Cell(f.x(i), f.y(i), f.z(i)));
            }
        }
        return leaves;
    }

    /**
     * Logs that can still support the trigger's region: one radius-
     * {@link #ANCHOR_SCAN_RADIUS} flood from the trigger, collecting every log
     * it touches. A log separated from the region by solid blocks can support
     * nothing in it and is not collected.
     */
    private Cell[] scanAnchors(Cell trigger) {
        NavFlood f = flood(trigger, ANCHOR_SCAN_RADIUS);
        Set<Cell> anchors = new java.util.LinkedHashSet<>();
        for (int i = 0; i < f.count(); i++) {
            if ((f.category(i) & WorldPhysics.LOG) != 0) {
                anchors.add(new Cell(f.x(i), f.y(i), f.z(i)));
            }
        }
        return anchors.toArray(new Cell[0]);
    }

    private NavFlood flood(Cell start, int radius) {
        if (flood == null) {
            flood = new NavFlood(SEARCH_EXPANSION_LIMIT);
        }
        flood.run(world.physics(), start.x(), start.y(), start.z(), radius, FOLIAGE_MASK,
            world.minY(), world.maxY());
        return flood;
    }

    private static boolean withinManhattanAny(Cell candidate, Cell[] anchors) {
        for (Cell anchor : anchors) {
            if (Math.abs(candidate.x() - anchor.x()) + Math.abs(candidate.y() - anchor.y())
                    + Math.abs(candidate.z() - anchor.z()) <= DECAY_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // ===== The actual decay =====

    /**
     * Removes one due leaf, unless a log has been placed within reach since it was
     * scheduled (rescued leaves stay). The removal re-triggers a reachability pass
     * so neighbors that were only held by this leaf follow in the next ticks.
     */
    private void decayLeaf(Cell pos) {
        long x = pos.x();
        long y = pos.y();
        long z = pos.z();

        if (!world.isLoaded(x, y, z)) {
            return;
        }
        if (!decayNeighborhoodLoaded(x, y, z)) {
            // A cell the anchor flood could reach is not known, and unloaded
            // cells read as AIR — a log just across the evicted seam would look
            // absent. Never remove a block on partial information; the leaf
            // stays, and the neighbor's own load rescan resumes the collapse if
            // it really is orphaned.
            return;
        }
        BlockType block = world.getBlock(x, y, z);
        if (!isLeaves(block)) {
            return; // already gone or replaced
        }
        if (world.isPersistent(x, y, z)) {
            return; // marked as player-placed after it was scheduled
        }
        if (isAnchored(x, y, z)) {
            return; // a log is orthogonally within reach again — keep the leaf
        }

        world.setBlock(x, y, z, BlockType.AIR);
        scheduleRecompute(x, y, z); // cascade holds up leaves that depended on this one
    }

    /**
     * Whether every cell the leaf's anchor flood can touch is known (resident or
     * known air): the flood reaches at most {@link #DECAY_RADIUS} cells from the
     * leaf, so the box of that radius (clamped to the world's height) covers it.
     * With this true, {@link #isAnchored} decides on complete information — the
     * earlier recompute may have run with less, but it only schedules; this is
     * the last word before a block is destroyed.
     */
    private boolean decayNeighborhoodLoaded(long x, long y, long z) {
        long y0 = Math.max(world.minY(), y - DECAY_RADIUS);
        long y1 = Math.min(world.maxY() - 1, y + DECAY_RADIUS);
        return world.isRegionLoaded(x - DECAY_RADIUS, y0, z - DECAY_RADIUS, x + DECAY_RADIUS, y1, z + DECAY_RADIUS);
    }

    /**
     * Whether a leaf still 6-connects to a log within the decay radius (same
     * reachability as the eviction pass, vanilla-style — a merely diagonal log
     * does not anchor it). The cheap guarantee that a leaf re-supported during
     * its decay window survives.
     */
    private boolean isAnchored(long x, long y, long z) {
        NavFlood f = flood(new Cell(x, y, z), DECAY_RADIUS);
        for (int i = 0; i < f.count(); i++) {
            if ((f.category(i) & WorldPhysics.LOG) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLeaves(BlockType block) {
        return block != null && block.isLeaves();
    }

    // ===== Cube purge =====

    private static void purgeCube(PriorityQueue<ScheduledUpdate> queue, Map<Cell, Long> tracked, CubePos cube) {
        synchronized (queue) {
            Iterator<ScheduledUpdate> it = queue.iterator();
            while (it.hasNext()) {
                ScheduledUpdate update = it.next();
                if (update != null && update.pos().inCube(cube)) {
                    it.remove();
                }
            }
        }
        tracked.keySet().removeIf(pos -> pos.inCube(cube));
    }

    /** Six orthogonal neighbors (the same connectivity the flood uses). */
    private static final int[][] ORTHOGONALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private record ScheduledUpdate(Cell pos, long dueTick, long sequence) {
    }

    /** A block position: a full {@code long} triple (never packed), hashed like the engine's {@link CubePos}. */
    record Cell(long x, long y, long z) {

        boolean inCube(CubePos cube) {
            return (x >> 5) == cube.x() && (y >> 5) == cube.y() && (z >> 5) == cube.z();
        }

        /** Chained polynomial + splitmix64 finaliser ({@link CubePos#hash}). */
        long mix() {
            return CubePos.hash(x, y, z);
        }

        @Override
        public int hashCode() {
            long h = mix();
            return (int) (h ^ (h >>> 32));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cell c && c.x == x && c.y == y && c.z == z;
        }
    }

    private static final java.util.Comparator<ScheduledUpdate> SCHEDULER =
        (a, b) -> a.dueTick() != b.dueTick()
            ? Long.compare(a.dueTick(), b.dueTick())
            : Long.compare(a.sequence(), b.sequence());
}
