package com.stonebreak.world.leaves;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.CancelToken;
import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.voxel.NavNodes;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Vanilla-Minecraft-style leaf decay over a {@link LeafWorld}. A leaf is
 * "anchored" while it remains 6-connected to a log through foliage or trunks
 * within {@value #DECAY_RADIUS} orthogonal steps — modern vanilla leaf distance
 * (leaves at distance 7 decay), so a leaf that only touches a log diagonally,
 * with no orthogonal leaf/log chain, is NOT anchored and decays. The radius
 * must cover the widest generated canopy: elms put leaves up to six steps from
 * their nearest log (see {@code ElmTree}), which the old radius of 4 silently
 * decayed on every chunk-load rescan. Once its connection to an anchor is cut it decays
 * — fast, but staggered across a window of a few tenths of a second so the
 * player watches the canopy visibly collapse instead of vanishing instantly.
 *
 * <p><b>Event-driven.</b> Reachability is only ever re-evaluated on a block
 * update that can change support: a log removed, a leaf removed (it may have
 * been the bridge holding the rest of a canopy), or a leaf placed detached
 * (hand-placed floating leaf). Each such update schedules one delayed
 * reachability pass at that position; the pass uses the engine's A* core as a
 * bounded flood ({@link LeafReachabilityDomain}) to find the candidate leaves
 * and the remaining log anchors that still hold them. This is the "check that
 * only fires once upon block updates" the feature asks for — idle worlds spend
 * nothing. The one non-event entry point is {@link #onChunkLoaded}: pending
 * work is dropped on chunk unload (and lost on quit), so a chunk becoming
 * resident is rescanned once per residency for leaves that cannot reach a log,
 * resuming any collapse that was interrupted mid-cascade.
 *
 * <p><b>Never decides on partial information.</b> Unloaded chunks read as AIR,
 * which would make a log just across an evicted seam look absent — so
 * {@link #decayLeaf} refuses to remove a leaf unless every chunk column its
 * anchor flood can touch is resident. A recompute may run with partial
 * information (it only schedules); the final check is always complete.
 *
 * <p><b>Runs only on authoritative worlds.</b> Render-only (multiplayer client)
 * worlds never tick this engine or feed it block changes; they display the
 * replicated decays streamed from the server.
 */
public final class LeafDecaySystem {

    /** Farthest a leaf may be from a log and still be anchored (modern vanilla leaf
     *  distance: 7 decays, so 6 is the last supported step). Must stay below
     *  {@code WorldConfiguration.CHUNK_SIZE} — {@link #decayNeighborhoodLoaded}
     *  relies on the reach box spanning at most 2×2 chunk columns. */
    public static final int DECAY_RADIUS = 6;

    /**
     * How far the anchor flood reaches from a trigger. Any log that can support
     * any candidate is within this: a candidate is at most {@link #DECAY_RADIUS}
     * flood-steps from the trigger, and its anchor at most {@link #DECAY_RADIUS}
     * more, and flood paths concatenate.
     */
    private static final int ANCHOR_SCAN_RADIUS = DECAY_RADIUS * 2;

    /** Hard cap on expanded nodes per A* flood. A fully foliated 6-connected ball of
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

    static {
        if (DECAY_RADIUS >= WorldConfiguration.CHUNK_SIZE) {
            throw new IllegalStateException("DECAY_RADIUS must be smaller than a chunk (2x2 column residency check)");
        }
    }

    private final LeafWorld world;
    private final AStar solver = new AStar();
    private final SearchLimits limits = new SearchLimits(SEARCH_EXPANSION_LIMIT, DECAY_RADIUS, 1.0f);
    private final SearchLimits anchorLimits = new SearchLimits(SEARCH_EXPANSION_LIMIT, ANCHOR_SCAN_RADIUS, 1.0f);

    // Delayed reachability re-evaluations, keyed by position (deduped; the
    // earliest due tick wins — a later schedule for the same cell is dropped).
    private final PriorityQueue<ScheduledUpdate> pendingRecomputes = new PriorityQueue<>(SCHEDULER);
    private final Map<Long, Long> scheduledRecomputes = new ConcurrentHashMap<>();

    // Leaves waiting to disappear (staggered within the decay window).
    private final PriorityQueue<ScheduledUpdate> pendingDecays = new PriorityQueue<>(SCHEDULER);
    private final Map<Long, Long> scheduledDecays = new ConcurrentHashMap<>();

    // Chunks rescanned this residency (mirrors WaterSim.scannedChunks).
    private final Set<Long> scannedChunks = new HashSet<>();

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
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
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
     * Rescan of a chunk that just became resident (authoritative worlds; called
     * once per residency, guarded like WaterSim's scannedChunks). Pending work
     * is dropped on unload and lost on quit, so a collapse interrupted
     * mid-cascade would otherwise freeze forever — this pass schedules a
     * reachability recompute for every in-chunk leaf that cannot reach a log
     * through in-chunk foliage. Cheap where it matters: freshly generated
     * chunks have no trees yet at listener time (features populate later), and
     * a healthy tree's leaves all reach their own trunk, scheduling nothing.
     * Seam-supported leaves (trunk in the neighbor chunk) do get scheduled, and
     * their recompute — which floods across the seam — finds the trunk and
     * decays nothing.
     */
    public void onChunkLoaded(int chunkX, int chunkZ) {
        if (!scannedChunks.add(chunkKey(chunkX, chunkZ))) {
            return;
        }
        int baseX = chunkX * WorldConfiguration.CHUNK_SIZE;
        int baseZ = chunkZ * WorldConfiguration.CHUNK_SIZE;

        // One pass over the chunk: the passable network (leaves + logs) and the
        // BFS sources (logs).
        Set<Long> foliage = new HashSet<>();
        Set<Long> leaves = new HashSet<>();
        java.util.ArrayDeque<Long> frontier = new java.util.ArrayDeque<>();
        for (int lx = 0; lx < WorldConfiguration.CHUNK_SIZE; lx++) {
            for (int lz = 0; lz < WorldConfiguration.CHUNK_SIZE; lz++) {
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                    int x = baseX + lx;
                    int z = baseZ + lz;
                    BlockType block = world.getBlock(x, y, z);
                    if (block == null || !NavNodes.inRange(x, y, z)) {
                        continue;
                    }
                    if (block.isLeaves()) {
                        long key = NavNodes.pack(x, y, z);
                        foliage.add(key);
                        leaves.add(key);
                    } else if (block.isLog()) {
                        long key = NavNodes.pack(x, y, z);
                        foliage.add(key);
                        frontier.add(key);
                    }
                }
            }
        }
        if (leaves.isEmpty()) {
            return;
        }

        // Multi-source BFS from the logs, depth-capped at the decay radius,
        // confined to the in-chunk network by construction (the foliage set
        // only holds in-chunk cells). Leaves it reaches are provably supported.
        Set<Long> reached = new HashSet<>(frontier);
        for (int depth = 0; depth < DECAY_RADIUS && !frontier.isEmpty(); depth++) {
            int layer = frontier.size();
            for (int i = 0; i < layer; i++) {
                long node = frontier.poll();
                int x = NavNodes.x(node);
                int y = NavNodes.y(node);
                int z = NavNodes.z(node);
                for (int[] dir : ORTHOGONALS) {
                    int ny = y + dir[1];
                    if (ny < 0 || ny >= WorldConfiguration.WORLD_HEIGHT
                        || !NavNodes.inRange(x + dir[0], ny, z + dir[2])) {
                        continue;
                    }
                    long neighbor = NavNodes.pack(x + dir[0], ny, z + dir[2]);
                    if (foliage.contains(neighbor) && reached.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
        }

        for (long leaf : leaves) {
            if (!reached.contains(leaf)) {
                enqueue(pendingRecomputes, scheduledRecomputes, leaf, EVICT_DELAY_TICKS);
            }
        }
    }

    /** Drops pending work for an unloading chunk (mirrors WaterSim.onChunkUnloaded). */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        scannedChunks.remove(chunkKey(chunkX, chunkZ));
        purgeChunk(pendingRecomputes, scheduledRecomputes, chunkX, chunkZ);
        purgeChunk(pendingDecays, scheduledDecays, chunkX, chunkZ);
        world.onChunkUnloaded(chunkX, chunkZ);
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
            Long tracked = scheduledRecomputes.get(next.posKey());
            if (tracked == null || tracked != next.dueTick()) {
                continue; // superseded by an earlier reschedule
            }
            scheduledRecomputes.remove(next.posKey());
            recompute(next.posKey());
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
            Long tracked = scheduledDecays.get(next.posKey());
            if (tracked == null || tracked != next.dueTick()) {
                continue; // superseded
            }
            scheduledDecays.remove(next.posKey());
            decayLeaf(next.posKey());
            processed++;
        }
    }

    private void scheduleRecompute(int x, int y, int z) {
        enqueue(pendingRecomputes, scheduledRecomputes, NavNodes.pack(x, y, z), EVICT_DELAY_TICKS);
    }

    private void scheduleDecay(long posKey) {
        int jitter = Math.floorMod((int) (posKey ^ (posKey >>> 32)), DECAY_DELAY_JITTER_TICKS + 1);
        enqueue(pendingDecays, scheduledDecays, posKey, DECAY_DELAY_BASE_TICKS + jitter);
    }

    private void enqueue(PriorityQueue<ScheduledUpdate> queue, Map<Long, Long> tracked, long posKey, int delayTicks) {
        long dueTick = logicalTick + Math.max(0, delayTicks);
        Long existing = tracked.get(posKey);
        if (existing != null && existing <= dueTick) {
            return; // already scheduled to fire no later
        }
        tracked.put(posKey, dueTick);
        synchronized (queue) {
            queue.add(new ScheduledUpdate(posKey, dueTick, sequenceCounter++));
        }
    }

    // ===== Reachability (the A* pass) =====

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
    private void recompute(long posKey) {
        Set<Long> candidates = floodLeaves(posKey);
        if (candidates.isEmpty()) {
            return;
        }

        long[] anchors = scanAnchors(posKey);
        Set<Long> supported = new HashSet<>();

        if (anchors.length == 0) {
            // No log within reach of the whole region — every candidate decays.
            for (long candidate : candidates) {
                scheduleDecay(candidate);
            }
            return;
        }

        if (anchors.length <= MAX_ANCHOR_FLOODS) {
            for (long anchor : anchors) {
                supported.addAll(floodLeaves(anchor));
            }
        } else {
            // Dense forest — a flood per log is too costly, so fall back to a
            // Manhattan probe. It ignores wall blockers, so it can only over-keep
            // (under-decay) a leaf, never wrongly decay one; an acceptable
            // conservative trade for a pathological case.
            for (long candidate : candidates) {
                if (withinManhattanAny(candidate, anchors)) {
                    supported.add(candidate);
                }
            }
        }

        for (long candidate : candidates) {
            if (!supported.contains(candidate)) {
                scheduleDecay(candidate);
            }
        }
    }

    /** Bounded A* flood through open air/foliage; returns the leaves it reached. */
    private Set<Long> floodLeaves(long start) {
        LeafReachabilityDomain domain = new LeafReachabilityDomain(world);
        solver.search(domain, start, limits, CancelToken.NEVER);
        return domain.reachedLeaves();
    }

    /**
     * Logs that can still support the trigger's region: one radius-
     * {@link #ANCHOR_SCAN_RADIUS} flood from the trigger, collecting every log
     * it touches. Replaces the old 17³ brute-force box scan — an order of
     * magnitude fewer world reads, and strictly fewer useless anchors (a log
     * separated from the region by solid blocks can support nothing in it and
     * is no longer collected).
     */
    private long[] scanAnchors(long trigger) {
        LeafReachabilityDomain domain = new LeafReachabilityDomain(world);
        solver.search(domain, trigger, anchorLimits, CancelToken.NEVER);
        Set<Long> anchors = domain.reachedLogs();
        long[] result = new long[anchors.size()];
        int i = 0;
        for (long anchor : anchors) {
            result[i++] = anchor;
        }
        return result;
    }

    private static boolean withinManhattanAny(long candidate, long[] anchors) {
        int cx = NavNodes.x(candidate);
        int cy = NavNodes.y(candidate);
        int cz = NavNodes.z(candidate);
        for (long anchor : anchors) {
            if (Math.abs(cx - NavNodes.x(anchor)) + Math.abs(cy - NavNodes.y(anchor))
                    + Math.abs(cz - NavNodes.z(anchor)) <= DECAY_RADIUS) {
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
    private void decayLeaf(long posKey) {
        int x = NavNodes.x(posKey);
        int y = NavNodes.y(posKey);
        int z = NavNodes.z(posKey);

        if (!world.isLoaded(x, y, z)) {
            return;
        }
        if (!decayNeighborhoodLoaded(x, y, z)) {
            // A chunk the anchor flood could reach is not resident, and unloaded
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
        if (isAnchored(x, y, z)) {
            return; // a log is orthogonally within reach again — keep the leaf
        }

        world.setBlock(x, y, z, BlockType.AIR);
        scheduleRecompute(x, y, z); // cascade holds up leaves that depended on this one
    }

    /**
     * Whether every chunk column the leaf's anchor flood can touch is resident.
     * The flood reaches at most {@link #DECAY_RADIUS} cells from the leaf, so
     * the four corners of that box cover every chunk column it can enter
     * (the radius is smaller than a chunk — asserted below — so the box spans
     * at most 2×2 columns). With this true, {@link #isAnchored} decides on complete
     * information — the earlier recompute may have run with less, but it only
     * schedules; this is the last word before a block is destroyed.
     */
    private boolean decayNeighborhoodLoaded(int x, int y, int z) {
        return world.isLoaded(x - DECAY_RADIUS, y, z - DECAY_RADIUS)
            && world.isLoaded(x - DECAY_RADIUS, y, z + DECAY_RADIUS)
            && world.isLoaded(x + DECAY_RADIUS, y, z - DECAY_RADIUS)
            && world.isLoaded(x + DECAY_RADIUS, y, z + DECAY_RADIUS);
    }

    /**
     * Whether a leaf still 6-connects to a log within the decay radius (same
     * reachability as the eviction pass, vanilla-style — a merely diagonal log
     * does not anchor it). The cheap guarantee that a leaf re-supported during
     * its decay window survives.
     */
    private boolean isAnchored(int x, int y, int z) {
        LeafReachabilityDomain domain = new LeafReachabilityDomain(world);
        solver.search(domain, NavNodes.pack(x, y, z), limits, CancelToken.NEVER);
        return domain.reachedLog();
    }

    private static boolean isLeaves(BlockType block) {
        return block != null && block.isLeaves();
    }

    // ===== Chunk purge =====

    private static void purgeChunk(PriorityQueue<ScheduledUpdate> queue, Map<Long, Long> tracked,
                                   int chunkX, int chunkZ) {
        synchronized (queue) {
            Iterator<ScheduledUpdate> it = queue.iterator();
            while (it.hasNext()) {
                ScheduledUpdate update = it.next();
                if (update != null && isInChunk(update.posKey(), chunkX, chunkZ)) {
                    it.remove();
                }
            }
        }
        tracked.keySet().removeIf(posKey -> isInChunk(posKey, chunkX, chunkZ));
    }

    private static boolean isInChunk(long posKey, int chunkX, int chunkZ) {
        return Math.floorDiv(NavNodes.x(posKey), WorldConfiguration.CHUNK_SIZE) == chunkX
            && Math.floorDiv(NavNodes.z(posKey), WorldConfiguration.CHUNK_SIZE) == chunkZ;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Six orthogonal neighbors (the same connectivity the flood uses). */
    private static final int[][] ORTHOGONALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private record ScheduledUpdate(long posKey, long dueTick, long sequence) {
    }

    private static final java.util.Comparator<ScheduledUpdate> SCHEDULER =
        (a, b) -> a.dueTick() != b.dueTick()
            ? Long.compare(a.dueTick(), b.dueTick())
            : Long.compare(a.sequence(), b.sequence());
}
