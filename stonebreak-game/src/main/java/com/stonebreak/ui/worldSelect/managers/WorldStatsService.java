package com.stonebreak.ui.worldSelect.managers;

import com.stonebreak.world.save.WorldFormat;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.io.CubeRegionFiles;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Computes on-disk statistics of world directories off the render thread.
 *
 * <p>A world's size is the sum of every file under its directory (cube store regions, the
 * fastlod cache, the json metadata) and its "chunk" count is the number of saved CUBES: the
 * records listed in the committed headers of its cube store's region files
 * ({@code cubes/r.*.cvr}, read with {@link CubeRegionFiles} without opening the store). Walking
 * that tree is far too slow to do while drawing a frame: stats are computed by a single
 * background worker and cached.
 * Callers get {@link #PENDING} (or {@code null} from {@link #getStats}) until a result
 * lands, and the cached value from then on.
 */
public final class WorldStatsService {

    /** Returned while the size of a world is still being computed. */
    public static final long PENDING = -1L;

    /**
     * What a completed walk of one world directory found. {@code chunkCount} = saved cubes (32^3 records of
     * the cube store; the name is kept for the world-select contract's "chunks" field).
     */
    public record Stats(long bytes, int chunkCount) {}

    /** Resolves a world name to the directory to measure. */
    private final Function<String, Path> worldDirResolver;

    public WorldStatsService() {
        this(WorldStorage::worldDir);
    }

    /** Test seam: measure world directories from somewhere other than the save folder. */
    public WorldStatsService(Function<String, Path> worldDirResolver) {
        this.worldDirResolver = worldDirResolver;
    }

    private final Map<String, Stats> statsCache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Bumped by {@link #invalidateAll()} so results from a walk started before the
     * invalidation are discarded instead of resurrecting a stale size.
     */
    private final AtomicLong generation = new AtomicLong();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "world-size-scanner");
        t.setDaemon(true);
        return t;
    });

    /**
     * Stats for the named world, or {@code null} if they are not known yet.
     * The first call for a world schedules the background walk that computes them.
     */
    public Stats getStats(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        Stats cached = statsCache.get(worldName);
        if (cached != null) {
            return cached;
        }

        if (inFlight.add(worldName)) {
            long startedAt = generation.get();
            worker.execute(() -> {
                try {
                    Stats stats = walk(worldDirResolver.apply(worldName));
                    if (generation.get() == startedAt) {
                        statsCache.put(worldName, stats);
                    }
                } finally {
                    inFlight.remove(worldName);
                }
            });
        }
        return null;
    }

    /**
     * Size of the named world in bytes, or {@link #PENDING} if it is not known yet.
     */
    public long getSizeBytes(String worldName) {
        Stats stats = getStats(worldName);
        return stats == null ? PENDING : stats.bytes();
    }

    /** Drops every cached result; the next request for a world re-walks it. */
    public void invalidateAll() {
        generation.incrementAndGet();
        statsCache.clear();
    }

    /** Stops the background worker. Safe to call more than once. */
    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Formats a byte count for display, e.g. {@code 1.4 GB} / {@code 142 MB} / {@code 820 KB}.
     * Units are binary (1 KB = 1024 B), matching how file managers report save folders.
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        // One decimal below 10 keeps small worlds from all reading "1 MB"
        String number = value < 10.0
                ? String.format("%.1f", value)
                : String.format("%.0f", value);
        return number + " " + units[unit];
    }

    private static Stats walk(Path dir) {
        if (!Files.isDirectory(dir)) {
            return new Stats(0L, 0);
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        total[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A file vanishing mid-walk (or an unreadable one) should not void the total
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error measuring world size for " + dir + ": " + e.getMessage());
        }
        long cubes = CubeRegionFiles.countCubes(dir.resolve(WorldFormat.CUBES_DIR));
        return new Stats(total[0], (int) Math.min(Integer.MAX_VALUE, cubes));
    }
}
