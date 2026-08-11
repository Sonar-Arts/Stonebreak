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
 * within {@value #DECAY_RADIUS} orthogonal steps — vanilla leaf distance, so a
 * leaf that only touches a log diagonally, with no orthogonal leaf/log chain,
 * is NOT anchored and decays. Once its connection to an anchor is cut it decays
 * — fast, but staggered across a window of a few tenths of a second so the
 * player watches the canopy visibly collapse instead of vanishing instantly.
 *
 * <p><b>Event-driven.</b> Reachability is only ever re-evaluated on a block
 * update that can change support: a log removed, or a leaf placed detached
 * (hand-placed floating leaf). Each such update schedules one delayed
 * reachability pass at that position; the pass uses the engine's A* core as a
 * bounded flood ({@link LeafReachabilityDomain}) to find the candidate leaves
 * and the remaining log anchors that still hold them. This is the "check that
 * only fires once upon block updates" the feature asks for — idle worlds spend
 * nothing.
 *
 * <p><b>Runs only on authoritative worlds.</b> Render-only (multiplayer client)
 * worlds never tick this engine or feed it block changes; they display the
 * replicated decays streamed from the server.
 */
public final class LeafDecaySystem {

    /** Farthest a leaf may be from a log and still be anchored (vanilla leaf distance). */
    public static final int DECAY_RADIUS = 4;

    /** How wide the box around a trigger is scanned for remaining log anchors. */
    private static final int ANCHOR_SCAN_RADIUS = DECAY_RADIUS * 2;

    /** Hard cap on expanded nodes per A* flood — a radius-4 ball easily fits. */
    private static final int SEARCH_EXPANSION_LIMIT = 2048;

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

    private final LeafWorld world;
    private final AStar solver = new AStar();
    private final SearchLimits limits = new SearchLimits(SEARCH_EXPANSION_LIMIT, DECAY_RADIUS, 1.0f);

    // Delayed reachability re-evaluations, keyed by position (deduped; a newer
    // schedule supersedes an older one for the same cell).
    private final PriorityQueue<ScheduledUpdate> pendingRecomputes = new PriorityQueue<>(SCHEDULER);
    private final Map<Long, Long> scheduledRecomputes = new ConcurrentHashMap<>();

    // Leaves waiting to disappear (staggered within the decay window).
    private final PriorityQueue<ScheduledUpdate> pendingDecays = new PriorityQueue<>(SCHEDULER);
    private final Map<Long, Long> scheduledDecays = new ConcurrentHashMap<>();

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
     * worlds only). Fires only on the two updates that can change leaf support:
     * a log removed, or a leaf placed detached. Log placement and every other
     * change are ignored — the idle cost is zero.
     */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (previous != null && previous.isLog()) {
            if (next != null && next.isLog()) {
                return; // log replaced by log — support unchanged
            }
            scheduleRecompute(x, y, z); // log gone — its former canopy is suspect
            return;
        }
        if (next != null && next.isLeaves()) {
            if (previous != null && previous.isLeaves()) {
                return; // leaf swapped for a leaf — support unchanged
            }
            scheduleRecompute(x, y, z); // hand-placed/detached leaf — verify it is anchored
        }
    }

    /** Drops pending work for an unloading chunk (mirrors WaterSim.onChunkUnloaded). */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
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
     *   <li><b>Candidates</b> — A* flood from the trigger through open air/foliage,
     *       radius {@link #DECAY_RADIUS}; every leaf reached depends on whatever
     *       used to sit at the trigger cell.</li>
     *   <li><b>Anchors</b> — every remaining log within {@link #ANCHOR_SCAN_RADIUS}
     *       of the trigger. Any candidate that can still reach an anchor within the
     *       radius is still supported.</li>
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

        long[] anchors = scanAnchors(NavNodes.x(posKey), NavNodes.y(posKey), NavNodes.z(posKey));
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

    /** Logs remaining within the anchor scan box around the trigger. */
    private long[] scanAnchors(int x, int y, int z) {
        Set<Long> anchors = new HashSet<>();
        for (int dx = -ANCHOR_SCAN_RADIUS; dx <= ANCHOR_SCAN_RADIUS; dx++) {
            for (int dy = -ANCHOR_SCAN_RADIUS; dy <= ANCHOR_SCAN_RADIUS; dy++) {
                for (int dz = -ANCHOR_SCAN_RADIUS; dz <= ANCHOR_SCAN_RADIUS; dz++) {
                    BlockType block = world.getBlock(x + dx, y + dy, z + dz);
                    if (block != null && block.isLog()
                        && NavNodes.inRange(x + dx, y + dy, z + dz)) {
                        anchors.add(NavNodes.pack(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
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

    private record ScheduledUpdate(long posKey, long dueTick, long sequence) {
    }

    private static final java.util.Comparator<ScheduledUpdate> SCHEDULER =
        (a, b) -> a.dueTick() != b.dueTick()
            ? Long.compare(a.dueTick(), b.dueTick())
            : Long.compare(a.sequence(), b.sequence());
}
