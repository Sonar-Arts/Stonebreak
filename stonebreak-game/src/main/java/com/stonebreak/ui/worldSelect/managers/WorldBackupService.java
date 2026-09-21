package com.stonebreak.ui.worldSelect.managers;

import com.stonebreak.world.save.WorldStorage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Copies a world directory into a timestamped zip under {@link WorldStorage#backupsRoot()}.
 *
 * <p>Zipping a large world reads every chunk file and takes seconds, so the work runs on a
 * background thread and the caller polls {@link #getStatus(String)} each frame to draw
 * progress. A finished status expires on its own after {@link #RESULT_TTL_MS} so a result
 * from minutes ago is not still sitting on the screen.
 */
public final class WorldBackupService {

    /** How long a DONE / FAILED status keeps being reported before it reverts to IDLE. */
    public static final long RESULT_TTL_MS = 12_000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    public enum State { IDLE, RUNNING, DONE, FAILED }

    /**
     * @param state    what the backup of this world is doing
     * @param progress 0..1 while RUNNING, 1 once finished
     * @param message  a line ready to draw, empty when IDLE
     */
    public record Status(State state, float progress, String message) {
        static final Status IDLE = new Status(State.IDLE, 0f, "");
    }

    private record Entry(Status status, long completedAtMs) {}

    private final Map<String, Entry> statuses = new ConcurrentHashMap<>();

    private final Function<String, Path> worldDirResolver;
    private final Supplier<Path> backupsRootSupplier;

    public WorldBackupService() {
        this(WorldStorage::worldDir, WorldStorage::backupsRoot);
    }

    /** Test seam: back worlds up from and into somewhere other than the save folder. */
    public WorldBackupService(Function<String, Path> worldDirResolver, Supplier<Path> backupsRootSupplier) {
        this.worldDirResolver = worldDirResolver;
        this.backupsRootSupplier = backupsRootSupplier;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "world-backup");
        t.setDaemon(true);
        return t;
    });

    /**
     * Current status of the named world's backup. Never null; a world that has never been
     * backed up (or whose last result has expired) reports {@link State#IDLE}.
     */
    public Status getStatus(String worldName) {
        if (worldName == null) {
            return Status.IDLE;
        }
        Entry entry = statuses.get(worldName);
        if (entry == null) {
            return Status.IDLE;
        }
        if (entry.completedAtMs() != 0L && System.currentTimeMillis() - entry.completedAtMs() > RESULT_TTL_MS) {
            statuses.remove(worldName, entry);
            return Status.IDLE;
        }
        return entry.status();
    }

    /** True while a backup of this world is in flight. */
    public boolean isRunning(String worldName) {
        return getStatus(worldName).state() == State.RUNNING;
    }

    /**
     * Starts a backup of the named world, unless one is already running for it.
     * Returns immediately; watch {@link #getStatus(String)} for the outcome.
     */
    public void backup(String worldName) {
        if (worldName == null || worldName.isBlank() || isRunning(worldName)) {
            return;
        }
        set(worldName, new Status(State.RUNNING, 0f, "Backing up..."), false);
        worker.execute(() -> runBackup(worldName));
    }

    /** Stops the background worker. Safe to call more than once. */
    public void shutdown() {
        worker.shutdownNow();
    }

    private void runBackup(String worldName) {
        Path worldDir = worldDirResolver.apply(worldName);
        if (!Files.isDirectory(worldDir)) {
            set(worldName, new Status(State.FAILED, 1f, "Backup failed - world folder missing"), true);
            return;
        }

        String fileName = worldName + "-" + LocalDateTime.now().format(STAMP) + ".zip";
        Path target = backupsRootSupplier.get().resolve(fileName);
        // Write to a temp name first so an interrupted run never leaves a truncated archive
        // sitting next to the real ones looking like a usable backup.
        Path temp = target.resolveSibling(fileName + ".part");

        try {
            Files.createDirectories(target.getParent());
            List<Path> files = listFiles(worldDir);
            long totalBytes = 0L;
            for (Path file : files) {
                totalBytes += sizeOf(file);
            }

            long written = 0L;
            try (OutputStream out = Files.newOutputStream(temp);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Path file : files) {
                    String entryName = worldName + "/" + separatorsToUnix(worldDir.relativize(file));
                    zip.putNextEntry(new ZipEntry(entryName));
                    written += Files.copy(file, zip);
                    zip.closeEntry();
                    set(worldName, new Status(State.RUNNING,
                            totalBytes <= 0L ? 1f : Math.min(1f, written / (float) totalBytes),
                            "Backing up... " + percent(written, totalBytes)), false);
                }
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            set(worldName, new Status(State.DONE, 1f, "Backed up - " + fileName), true);
            System.out.println("Backed up world '" + worldName + "' to " + target);
        } catch (IOException e) {
            deleteQuietly(temp);
            set(worldName, new Status(State.FAILED, 1f, "Backup failed - " + e.getMessage()), true);
            System.err.println("Error backing up world '" + worldName + "': " + e.getMessage());
        }
    }

    private void set(String worldName, Status status, boolean finished) {
        statuses.put(worldName, new Entry(status, finished ? System.currentTimeMillis() : 0L));
    }

    private static List<Path> listFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // Skip what cannot be read rather than abandoning the whole archive
                System.err.println("Skipping unreadable file in backup: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Zip entry names are '/'-separated on every platform, including Windows. */
    private static String separatorsToUnix(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            sb.append(relative.getName(i));
        }
        return sb.toString();
    }

    private static String percent(long written, long total) {
        if (total <= 0L) return "100%";
        return Math.min(100, Math.round(written * 100f / total)) + "%";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing useful to do; the .part name keeps it from being mistaken for a backup
        }
    }
}
