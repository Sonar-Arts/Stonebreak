package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.SearchResult;
import com.openmason.engine.wayfind.voxel.AirNavDomain;
import com.openmason.engine.wayfind.voxel.AirNavProfile;
import com.openmason.engine.wayfind.voxel.GroundNavDomain;
import com.openmason.engine.wayfind.voxel.NavCellCache;
import com.openmason.engine.wayfind.voxel.NavNodes;
import com.openmason.engine.wayfind.voxel.NavProfile;
import com.openmason.engine.wayfind.voxel.NavVolume;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs path searches off the tick, one per world.
 *
 * <p>Searching on worker threads is safe here for a specific reason worth stating: chunk storage is
 * a {@code ConcurrentHashMap} and paletted section reads are lock-free by construction (a volatile
 * read of an immutable-structure state, then plain array reads), which is the same guarantee the
 * chunk mesher and the FastLOD workers already run on. A block changing mid-search is therefore
 * never torn — the search sees the old value or the new one, and either is a legitimate answer to a
 * question asked a few milliseconds ago.
 *
 * <p>What the threading buys is not raw speed — a mob route costs a few hundred microseconds — but
 * insulation: a pathological search in a cave system cannot stretch a server tick.
 *
 * <p>The rules that keep it boring:
 * <ul>
 *   <li>Workers produce an immutable {@link Path} and nothing else. They never touch an entity.</li>
 *   <li>Requests are bounded ({@link #DEFAULT_MAX_IN_FLIGHT}); over the cap, {@link #submit}
 *       returns {@code null} immediately rather than queueing. A rejected caller simply asks again
 *       after its own repath cooldown, so load sheds instead of piling up.</li>
 *   <li>The service belongs to a {@link World}, not to a static singleton, and {@link #close}
 *       cancels everything in flight — a world swap cannot leave workers writing results for
 *       entities that no longer exist.</li>
 *   <li>Threads are daemons, so a leaked service can never hold the JVM open.</li>
 * </ul>
 */
public final class PathfindingService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathfindingService.class);

    /**
     * Concurrent searches allowed. Generous: at the spawner's density a full render distance holds
     * a few hundred mobs, and only the ones actively repathing are ever in here at once.
     */
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    /** How far up and down a start or goal position is snapped onto a standable surface. */
    private static final int SNAP_DOWN = 4;
    private static final int SNAP_UP = 2;

    /**
     * How far an air goal is nudged onto real airspace, in cells. Two cells is plenty to clear a
     * hillside or a treetop the destination happened to land inside; further than that and the
     * search would be answering a different question than the one it was asked.
     */
    private static final int AIR_GOAL_SNAP_CELLS = 2;

    private final NavVolume volume;
    private final Executor executor;
    private final ExecutorService ownedPool;
    private final int maxInFlight;

    private final Set<PathRequest> inFlight = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<AStar> solvers = ThreadLocal.withInitial(AStar::new);

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong partial = new AtomicLong();
    private final AtomicLong searchNanos = new AtomicLong();

    private volatile boolean closed;

    /** Builds a service with its own small daemon pool, reading the given world. */
    public static PathfindingService forWorld(World world) {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        ExecutorService pool = Executors.newFixedThreadPool(threads, new WayfindThreadFactory());
        return new PathfindingService(new WorldNavVolume(world), pool, pool, DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * Builds a service over a caller-supplied executor and volume. Tests pass a same-thread
     * executor, which makes every search complete before {@link #submit} returns and removes
     * timing from the picture entirely.
     */
    public PathfindingService(NavVolume volume, Executor executor) {
        this(volume, executor, null, DEFAULT_MAX_IN_FLIGHT);
    }

    private PathfindingService(NavVolume volume, Executor executor,
                               ExecutorService ownedPool, int maxInFlight) {
        this.volume = volume;
        this.executor = executor;
        this.ownedPool = ownedPool;
        this.maxInFlight = maxInFlight;
    }

    /**
     * Queues a search from {@code feet} to {@code goal}.
     *
     * @param feet   the agent's foot position, not its origin — a mob's origin sits at the top of
     *               its legs, and starting the search a block too high snaps it to the wrong surface
     * @param profile how this agent moves; see {@link NavProfiles}
     * @return the request to poll, or {@code null} if the service is closed or already saturated
     */
    public PathRequest submit(Vector3f feet, Vector3f goal, float goalRadius,
                              NavProfile profile, SearchLimits limits) {
        if (closed || inFlight.size() >= maxInFlight) {
            rejected.incrementAndGet();
            return null;
        }

        PathRequest request = new PathRequest(goal, goalRadius);
        int startX = (int) Math.floor(feet.x);
        int startY = (int) Math.floor(feet.y + 0.001f); // nudge off an exact block boundary
        int startZ = (int) Math.floor(feet.z);
        int goalX = (int) Math.floor(goal.x);
        int goalY = (int) Math.floor(goal.y + 0.001f);
        int goalZ = (int) Math.floor(goal.z);

        if (!NavNodes.inRange(startX, startY, startZ) || !NavNodes.inRange(goalX, goalY, goalZ)) {
            request.publish(Path.EMPTY);
            return request;
        }

        submitted.incrementAndGet();
        inFlight.add(request);
        try {
            executor.execute(() -> runSearch(request, profile, limits,
                    startX, startY, startZ, goalX, goalY, goalZ, goalRadius));
        } catch (RuntimeException rejectedByExecutor) {
            // A shutting-down pool refuses work; the caller must still see a resolved request.
            inFlight.remove(request);
            request.publish(Path.EMPTY);
            rejected.incrementAndGet();
        }
        return request;
    }

    /**
     * Queues an air search from {@code from} to {@code goal}, for something with wings.
     *
     * <p>Same contract as {@link #submit}: asynchronous, bounded, and never null-returning for any
     * reason except a saturated or closed service. The differences are all in the domain — see
     * {@link AirNavDomain} — plus the position, which is the flyer's origin rather than its feet,
     * because nothing about an air route is measured from the ground.
     *
     * @return the request to poll, or {@code null} if the service is closed or already saturated
     */
    public PathRequest submitAir(Vector3f from, Vector3f goal, float goalRadius,
                                 AirNavProfile profile, SearchLimits limits) {
        if (closed || inFlight.size() >= maxInFlight) {
            rejected.incrementAndGet();
            return null;
        }

        PathRequest request = new PathRequest(goal, goalRadius);
        int startX = (int) Math.floor(from.x);
        int startY = (int) Math.floor(from.y);
        int startZ = (int) Math.floor(from.z);
        int goalX = (int) Math.floor(goal.x);
        int goalY = (int) Math.floor(goal.y);
        int goalZ = (int) Math.floor(goal.z);

        if (!AirNavDomain.cellInRange(profile, startX, startY, startZ)
                || !AirNavDomain.cellInRange(profile, goalX, goalY, goalZ)) {
            request.publish(Path.EMPTY);
            return request;
        }

        submitted.incrementAndGet();
        inFlight.add(request);
        try {
            executor.execute(() -> runAirSearch(request, profile, limits,
                    startX, startY, startZ, goalX, goalY, goalZ, goalRadius));
        } catch (RuntimeException rejectedByExecutor) {
            inFlight.remove(request);
            request.publish(Path.EMPTY);
            rejected.incrementAndGet();
        }
        return request;
    }

    private void runAirSearch(PathRequest request, AirNavProfile profile, SearchLimits limits,
                              int startX, int startY, int startZ,
                              int goalX, int goalY, int goalZ, float goalRadius) {
        long began = System.nanoTime();
        try {
            if (closed || request.isCancelled()) {
                request.publish(Path.EMPTY);
                return;
            }

            // One block-level cache behind both domains, so snapping the goal does not re-read the
            // world the second domain is about to read again.
            NavCellCache cache = new NavCellCache(volume);

            long rawGoal = AirNavDomain.cellOf(profile, goalX, goalY, goalZ);
            AirNavDomain snapper = new AirNavDomain(cache, profile, rawGoal, goalRadius);
            long snappedGoal = snapper.snapToFlyable(goalX, goalY, goalZ, AIR_GOAL_SNAP_CELLS);

            AirNavDomain domain = (snappedGoal == AirNavDomain.NO_NODE || snappedGoal == rawGoal)
                    ? snapper
                    : new AirNavDomain(cache, profile, snappedGoal, goalRadius);

            long start = AirNavDomain.cellOf(profile, startX, startY, startZ);
            SearchResult result = solvers.get().search(domain, start, limits, request.cancelToken());
            if (result.status() == SearchResult.Status.PARTIAL_BUDGET
                    || result.status() == SearchResult.Status.PARTIAL_UNREACHABLE) {
                partial.incrementAndGet();
            }
            request.publish(Path.ofCells(domain.stringPull(result.nodes()),
                    profile.cellSize(), result.reachedGoal()));
            completed.incrementAndGet();
        } catch (Throwable failure) {
            failed.incrementAndGet();
            request.publish(Path.EMPTY);
            LOGGER.warn("Air path search failed", failure);
        } finally {
            searchNanos.addAndGet(System.nanoTime() - began);
            inFlight.remove(request);
        }
    }

    private void runSearch(PathRequest request, NavProfile profile, SearchLimits limits,
                           int startX, int startY, int startZ,
                           int goalX, int goalY, int goalZ, float goalRadius) {
        long began = System.nanoTime();
        try {
            if (closed || request.isCancelled()) {
                request.publish(Path.EMPTY);
                return;
            }

            // One cache for the whole search, shared by both domains below.
            NavCellCache cache = new NavCellCache(volume);

            // The goal is wherever the mob was told to go — often a position in mid-air, or inside
            // a block. Snapping it to a real surface first is what lets the search finish on FOUND
            // instead of grinding to a partial every time.
            GroundNavDomain snapper = new GroundNavDomain(cache, profile, goalX, goalY, goalZ, goalRadius);
            long start = snapper.snapToSurface(startX, startY, startZ, SNAP_DOWN, SNAP_UP);
            if (start == GroundNavDomain.NO_NODE) {
                request.publish(Path.EMPTY);
                return;
            }
            long snappedGoal = snapper.snapToSurface(goalX, goalY, goalZ, SNAP_DOWN, SNAP_UP);

            GroundNavDomain domain = snappedGoal == GroundNavDomain.NO_NODE ? snapper
                    : new GroundNavDomain(cache, profile,
                            NavNodes.x(snappedGoal), NavNodes.y(snappedGoal), NavNodes.z(snappedGoal),
                            goalRadius);

            SearchResult result = solvers.get().search(domain, start, limits, request.cancelToken());
            if (result.status() == SearchResult.Status.PARTIAL_BUDGET
                    || result.status() == SearchResult.Status.PARTIAL_UNREACHABLE) {
                partial.incrementAndGet();
            }
            request.publish(Path.of(result.nodes(), domain, result.reachedGoal()));
            completed.incrementAndGet();
        } catch (Throwable failure) {
            // A worker must never die silently: the request would never resolve and its agent
            // would wait forever.
            failed.incrementAndGet();
            request.publish(Path.EMPTY);
            LOGGER.warn("Path search failed", failure);
        } finally {
            searchNanos.addAndGet(System.nanoTime() - began);
            inFlight.remove(request);
        }
    }

    /** Snapshot of service load, for the debug overlay. */
    public Stats stats() {
        long done = completed.get();
        return new Stats(submitted.get(), rejected.get(), done, failed.get(), partial.get(),
                inFlight.size(), done == 0 ? 0 : searchNanos.get() / done / 1000L);
    }

    public record Stats(long submitted, long rejected, long completed, long failed, long partial,
                        int inFlight, long averageMicros) {
    }

    /**
     * Cancels everything in flight and shuts down an owned pool. Idempotent; safe to call from
     * world teardown even if searches are mid-flight.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (PathRequest request : inFlight) {
            request.cancel();
        }
        if (ownedPool != null) {
            ownedPool.shutdownNow();
            try {
                ownedPool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static final class WayfindThreadFactory implements java.util.concurrent.ThreadFactory {
        private int created;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Wayfind-" + created++);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
