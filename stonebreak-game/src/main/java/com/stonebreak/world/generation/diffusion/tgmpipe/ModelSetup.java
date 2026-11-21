package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gets {@value TGMPipe#MODEL_NAME} ready on game launch, so a player only ever starts the game. Everything is native:
 * no Python, no PyTorch (those are for training and for writing the model's release export only).
 *
 * <ol>
 *   <li><b>Graphics card</b> - an NVIDIA GPU ({@code nvidia-smi}); without one the model cannot run and nothing is
 *       downloaded.</li>
 *   <li><b>CUDA compiler</b> - the pinned NVRTC ({@link NvrtcTool}, ~90 MB download the first time).</li>
 *   <li><b>Model files</b> - the release's native export is there and not Git LFS pointers
 *       ({@link TerrainModels#installProblem}).</li>
 *   <li><b>Model on the GPU</b> and <b>GPU kernels</b> - the native engine opens the export (loads the networks) and
 *       warms up, compiling its kernels into {@code Models/kernel_cache/} (content-addressed), where the world's
 *       pipeline finds them, so the first world never waits on the compiler. Then it is closed again: nothing of the
 *       model stays loaded on the main menu.</li>
 * </ol>
 *
 * <p><b>When it runs.</b> Only when something that decides those kernels or that model changed since the last
 * successful setup, judged by <em>content</em>, never by timestamps or paths: the stamp
 * ({@code Models/kernel_cache/.stonebreak-setup}) holds a fingerprint of the SHA-256 of the native terrain library,
 * the engine library (inference runtime), the NVRTC library and the model export's {@code manifest.json} (which names
 * the export and its networks), plus the GPUs' compute capabilities and the device used. A file whose size and mtime
 * match the stamp's record reuses its recorded hash; one that changed is hashed again, so a rebuild or checkout that
 * produces the same bytes (an incremental build relinking, a fresh clone, an LFS pull) does not count as a change. An
 * unchanged install costs a few file stats and one {@code nvidia-smi} call and shows nothing. Deleting the kernel
 * cache forgets the stamp with it. Everything the steps do goes to {@code Models/logs/setup.log}.
 *
 * <p>It runs on its own thread from the moment the game starts (under the intro). {@code -Dstonebreak.modelSetup=off}
 * disables it (the world's start then does the work); {@code -Dstonebreak.modelSetup.force=true} ignores the stamp.
 */
public final class ModelSetup {

    private static final Logger LOG = Logger.getLogger(ModelSetup.class.getName());
    private static final ModelSetup INSTANCE = new ModelSetup();
    static final String STAMP = ".stonebreak-setup";
    private static final String UNAVAILABLE_MARKER = ".setup-unavailable";
    private static final String FINGERPRINT_KEY = "fingerprint";
    private static final String FILE_KEY = "file.";

    public enum StepId {
        GPU("Checking graphics card"),
        NVRTC("Getting the CUDA compiler"),
        MODEL("Checking the model files"),
        VERIFY("Checking the model on the GPU"),
        KERNELS("Compiling GPU kernels");

        public final String title;

        StepId(String title) {
            this.title = title;
        }
    }

    public enum Status { PENDING, RUNNING, DONE, SKIPPED, FAILED }

    /** Where setup stands as a whole. */
    public enum Outcome { NOT_STARTED, RUNNING, READY, UNAVAILABLE, FAILED }

    /** @param fraction 0..1 when the step can measure itself, else negative */
    public record Step(StepId id, Status status, String detail, double fraction) { }

    /**
     * @param activity   the latest thing a step did (a download, a compile), for the screen
     * @param message    why setup failed or the model is unavailable, else null
     * @param workNeeded this launch had something to download, load or compile
     */
    public record Snapshot(Outcome outcome, List<Step> steps, String activity, String message,
                           long elapsedMs, boolean workNeeded) { }

    public static ModelSetup getInstance() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Map<StepId, Step> steps = new LinkedHashMap<>();
    private Outcome outcome = Outcome.NOT_STARTED;
    private String activity = "";
    private String message;
    private boolean workNeeded;
    private boolean unavailableKnownBefore;
    private long startNanos;
    private long endNanos;

    private ModelSetup() {
        resetSteps();
    }

    // ------------------------------------------------------------------ public API

    /** Starts setup on a background thread; a no-op once started (see {@link #retry}). */
    public void start() {
        synchronized (lock) {
            if (outcome != Outcome.NOT_STARTED) return;
            if ("off".equalsIgnoreCase(System.getProperty("stonebreak.modelSetup", "on"))) {
                LOG.info("Model setup disabled (-Dstonebreak.modelSetup=off)");
                return;
            }
            launchLocked();
        }
    }

    /** Runs setup again after a failure (or to re-check an unavailable GPU). */
    public void retry() {
        synchronized (lock) {
            if (outcome == Outcome.RUNNING) return;
            try {
                Files.deleteIfExists(paths().slmDir.resolve(UNAVAILABLE_MARKER));
            } catch (IOException ignored) {
                // a stale marker only hides the screen next launch
            }
            resetSteps();
            launchLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long end = outcome == Outcome.RUNNING || endNanos == 0 ? System.nanoTime() : endNanos;
            long elapsed = startNanos == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(end - startNanos);
            return new Snapshot(outcome, List.copyOf(steps.values()), activity, message, elapsed, workNeeded);
        }
    }

    /**
     * Whether the setup screen belongs between the intro and the main menu: while setup works, when it
     * failed, the first time the model turns out unavailable, and briefly after a launch that downloaded,
     * loaded or compiled something (so the player sees it finish). An unchanged install shows nothing.
     */
    public boolean shouldShowScreen() {
        synchronized (lock) {
            return switch (outcome) {
                case RUNNING, FAILED -> true;
                case UNAVAILABLE -> !unavailableKnownBefore;
                case READY -> workNeeded;
                case NOT_STARTED -> false;
            };
        }
    }

    /**
     * Blocks until setup is done (called by {@link TGMPipe#ensureRunning} off the render thread). Returns at
     * once when setup never started (tests, tools, {@code -Dstonebreak.modelSetup=off}).
     *
     * @throws TGMPipeException if the model cannot be used on this machine or setup failed
     */
    public void awaitUsable(DoubleConsumer progress) {
        synchronized (lock) {
            boolean reported = false;
            while (outcome == Outcome.RUNNING) {
                if (!reported) {
                    progress.accept(0.0);
                    reported = true;
                }
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TGMPipeException("interrupted while " + TGMPipe.MODEL_NAME + " was being set up");
                }
            }
            switch (outcome) {
                case UNAVAILABLE -> throw new TGMPipeException(TGMPipe.MODEL_NAME + " is unavailable: " + message);
                case FAILED -> throw new TGMPipeException(TGMPipe.MODEL_NAME + " setup failed: " + message
                        + " (see " + paths().logFile + "; retry from the setup screen or restart the game)");
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ state

    private void resetSteps() {
        steps.clear();
        for (StepId id : StepId.values()) {
            steps.put(id, new Step(id, Status.PENDING, "", -1));
        }
        activity = "";
        message = null;
    }

    private void set(StepId id, Status status, String detail, double fraction) {
        synchronized (lock) {
            steps.put(id, new Step(id, status, detail == null ? "" : detail, fraction));
            lock.notifyAll();
        }
    }

    private void detail(StepId id, String detail, double fraction) {
        synchronized (lock) {
            Step s = steps.get(id);
            steps.put(id, new Step(id, s.status(), detail == null ? "" : detail, fraction));
        }
    }

    private void activity(String line) {
        synchronized (lock) {
            activity = line;
        }
    }

    private void finish(Outcome result, String why) {
        synchronized (lock) {
            outcome = result;
            message = why;
            endNanos = System.nanoTime();
            lock.notifyAll();
        }
        LOG.info(() -> "Model setup: " + result + (why != null ? " (" + why + ")" : ""));
    }

    private void launchLocked() {
        outcome = Outcome.RUNNING;
        startNanos = System.nanoTime();
        endNanos = 0;
        Thread t = new Thread(this::run, "model-setup");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ the steps

    private void run() {
        Paths p = paths();
        try (PrintWriter log = openLog(p.logFile)) {
            log.println("=== " + TGMPipe.MODEL_NAME + " setup, " + LocalDateTime.now() + " ===");
            try {
                runSteps(p, log);
            } catch (StepFailure e) {
                log.println("FAILED: " + e.getMessage());
                set(e.step, Status.FAILED, e.getMessage(), -1);
                finish(Outcome.FAILED, e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "model setup crashed", e);
                e.printStackTrace(log);
                failRunningStep(e.toString());
                finish(Outcome.FAILED, e.toString());
            }
        }
    }

    private void runSteps(Paths p, PrintWriter log) {
        boolean force = Boolean.getBoolean("stonebreak.modelSetup.force");
        boolean knownUnavailable = Files.exists(p.slmDir.resolve(UNAVAILABLE_MARKER));
        synchronized (lock) {
            unavailableKnownBefore = knownUnavailable;
        }
        // Before the up-to-date shortcut: an installed model is still disabled on a machine below the bar.
        DaedalusRequirements.Verdict requirements = DaedalusRequirements.check();
        if (!requirements.met()) {
            log.println("system: " + requirements.specs());
            synchronized (lock) {
                workNeeded = false;
            }
            unavailable(p, "Below system requirements", DaedalusRequirements.disabledMessage(requirements));
            return;
        }
        List<String> gpus = nvidiaGpus(log);
        if (gpus == null) {
            synchronized (lock) {
                workNeeded = true;
            }
            unavailable(p, "No NVIDIA GPU found",
                    "no NVIDIA graphics card found (the terrain model needs one); standard terrain still works");
            return;
        }

        Properties stamp = readStamp(p.stampFile());
        Path nvrtcHere = NvrtcTool.installed(p.toolsDir);
        Fingerprint now = nvrtcHere == null ? null : fingerprint(p, nvrtcHere, gpus, stamp);
        boolean upToDate = !force && now != null && Files.isDirectory(p.kernelCache)
                && now.value().equals(stamp.getProperty(FINGERPRINT_KEY));
        synchronized (lock) {
            workNeeded = !upToDate;
        }
        if (upToDate) {
            for (StepId id : StepId.values()) set(id, Status.SKIPPED, "up to date", -1);
            finish(Outcome.READY, null);
            return;
        }
        log.println(now == null ? "no CUDA compiler yet" : "changed since the last setup: "
                + now.changedFrom(stamp));

        // 1. Graphics card
        set(StepId.GPU, Status.DONE, gpus.size() == 1 ? gpus.get(0) : gpus.size() + " GPUs: " + String.join(", ", gpus), -1);

        // 2. CUDA compiler
        Path nvrtc;
        if (nvrtcHere != null) {
            set(StepId.NVRTC, Status.SKIPPED, describeNvrtc(nvrtcHere, p.toolsDir), -1);
            nvrtc = nvrtcHere;
        } else {
            set(StepId.NVRTC, Status.RUNNING, "First time: downloads about 90 MB", -1);
            try {
                nvrtc = NvrtcTool.locate(p.toolsDir, log, (text, fraction) -> {
                    detail(StepId.NVRTC, text, fraction);
                    activity(text);
                });
            } catch (TGMPipeException e) {
                throw new StepFailure(StepId.NVRTC, e.getMessage());
            }
            set(StepId.NVRTC, Status.DONE, describeNvrtc(nvrtc, p.toolsDir), -1);
        }

        // 3. Model files
        set(StepId.MODEL, Status.RUNNING, "", -1);
        String model = TerrainModels.defaultModelPath();   // the default (new worlds); a saved world's other model warms on first use
        String problem = TerrainModels.installProblem(model);
        if (problem != null) {
            throw new StepFailure(StepId.MODEL, problem);
        }
        set(StepId.MODEL, Status.DONE, model, -1);

        // 4 + 5. Model on the GPU, kernels
        warmUp(log);
        writeStamp(p.stampFile(), fingerprint(p, nvrtc, gpus, stamp));
        finish(Outcome.READY, null);
    }

    /** Opens the engine on the export (loads the networks), warms it up (compiles the kernels), closes it again. */
    private void warmUp(PrintWriter log) {
        set(StepId.VERIFY, Status.RUNNING, "", -1);
        activity("Loading the model");
        long t0 = System.nanoTime();
        NativeTerrainLauncher.Opened opened;
        try {
            opened = NativeTerrainLauncher.open();
        } catch (TGMPipeException e) {
            throw new StepFailure(StepId.VERIFY, e.getMessage());
        }
        try (NativeTerrain terrain = opened.terrain()) {
            log.println("opened: " + terrain.info());
            set(StepId.VERIFY, Status.DONE, "cuda:" + opened.device(), -1);
            set(StepId.KERNELS, Status.RUNNING, "", -1);
            activity("Compiling GPU kernels");
            long t1 = System.nanoTime();
            try {
                terrain.setLanes(1);   // one context is enough to compile; the world picks its own lane count
                terrain.warmup();
            } catch (RuntimeException e) {
                throw new StepFailure(StepId.KERNELS, e.getMessage());
            }
            double s = (System.nanoTime() - t1) / 1e9;
            log.println(String.format("warm-up %.1f s (open %.1f s)", s, (t1 - t0) / 1e9));
            set(StepId.KERNELS, Status.DONE, String.format("ready in %.1f s", s), -1);
        }
    }

    /** Ends setup with the model unusable on this machine: the GPU step failed, the rest skipped, nothing downloaded. */
    private void unavailable(Paths p, String gpuDetail, String why) {
        set(StepId.GPU, Status.FAILED, gpuDetail, -1);
        for (StepId id : List.of(StepId.NVRTC, StepId.MODEL, StepId.VERIFY, StepId.KERNELS)) {
            set(id, Status.SKIPPED, "", -1);
        }
        try {
            Files.writeString(p.slmDir.resolve(UNAVAILABLE_MARKER), why + "\n");
        } catch (IOException ignored) {
            // the marker only keeps the screen from reappearing every launch
        }
        finish(Outcome.UNAVAILABLE, why);
    }

    private static String describeNvrtc(Path lib, Path toolsDir) {
        return lib.startsWith(toolsDir) ? "NVRTC " + NvrtcTool.VERSION : "NVRTC (" + lib + ")";
    }

    // ------------------------------------------------------------------ fingerprint

    /**
     * What decides the setup's work, by content.
     *
     * @param value   the fingerprint the stamp compares
     * @param parts   role -> SHA-256 / fact, for the log
     * @param records the file records (path|size|mtime|sha256) the stamp keeps for the next launch
     */
    record Fingerprint(String value, Map<String, String> parts, Map<String, String> records) {

        /** The roles whose value differs from what {@code stamp} recorded (for the log). */
        String changedFrom(Properties stamp) {
            List<String> changed = new ArrayList<>();
            for (var e : parts.entrySet()) {
                if (!e.getValue().equals(stamp.getProperty("part." + e.getKey()))) {
                    changed.add(e.getKey());
                }
            }
            return changed.isEmpty() ? (stamp.isEmpty() ? "no earlier setup" : "nothing (forced)") : String.join(", ", changed);
        }
    }

    private static Fingerprint fingerprint(Paths p, Path nvrtc, List<String> gpus, Properties stamp) {
        Map<String, Path> files = new TreeMap<>();
        files.put("terrain_lib", NativeTerrain.libraryPath());
        files.put("engine_lib", cendaLibraryOrNull());
        files.put("nvrtc", nvrtc);
        files.put("export_manifest", NativeTerrainLauncher.nativeDir().resolve("manifest.json"));
        Map<String, String> facts = new TreeMap<>();
        facts.put("gpus", String.join(";", gpus));
        facts.put("device", deviceOrUnknown());
        return fingerprint(files, facts, stamp);
    }

    /**
     * The fingerprint of {@code files} (role -> file; null or missing = "missing") and {@code facts} (role -> text).
     * A file whose absolute path, size and mtime match its record in {@code stamp} reuses the recorded SHA-256; any
     * other is hashed, so only a change of bytes changes the fingerprint.
     */
    static Fingerprint fingerprint(Map<String, Path> files, Map<String, String> facts, Properties stamp) {
        Map<String, String> parts = new TreeMap<>(facts);
        Map<String, String> records = new TreeMap<>();
        for (var e : files.entrySet()) {
            Path f = e.getValue();
            if (f == null || !Files.isRegularFile(f)) {
                parts.put(e.getKey(), "missing");
                continue;
            }
            Path abs = f.toAbsolutePath().normalize();
            try {
                long size = Files.size(abs);
                long mtime = Files.getLastModifiedTime(abs).toMillis();
                String id = abs + "|" + size + "|" + mtime + "|";
                String recorded = stamp.getProperty(FILE_KEY + e.getKey(), "");
                String sha = recorded.startsWith(id) ? recorded.substring(id.length()) : sha256(abs);
                parts.put(e.getKey(), sha);
                records.put(e.getKey(), id + sha);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        StringBuilder all = new StringBuilder();
        parts.forEach((k, v) -> all.append(k).append('=').append(v).append('\n'));
        return new Fingerprint(sha256(all.toString().getBytes(StandardCharsets.UTF_8)), parts, records);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path cendaLibraryOrNull() {
        try {
            return NativeTerrainLauncher.cendaLibrary();
        } catch (TGMPipeException e) {
            return null;
        }
    }

    private static String deviceOrUnknown() {
        try {
            return "cuda:" + NativeTerrainLauncher.device();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    static Properties readStamp(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // unreadable = not set up
            }
        }
        return props;
    }

    /** The stamp lives in the kernel cache: deleting the cache forgets the setup with it. */
    static void writeStamp(Path file, Fingerprint f) {
        Properties props = new Properties();
        props.setProperty(FINGERPRINT_KEY, f.value());
        f.parts().forEach((k, v) -> props.setProperty("part." + k, v));
        f.records().forEach((k, v) -> props.setProperty(FILE_KEY + k, v));
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, TGMPipe.MODEL_NAME + " setup (written by the game; delete to redo it)");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not write the setup stamp; setup will run again next launch", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "name (compute capability)" of every NVIDIA GPU, or null without a working {@code nvidia-smi}. */
    private static List<String> nvidiaGpus(PrintWriter log) {
        if (Boolean.getBoolean("stonebreak.modelSetup.skipGpuCheck")) return List.of("not checked");
        List<String> out = new ArrayList<>();
        try {
            Process proc = new ProcessBuilder("nvidia-smi", "--query-gpu=name,compute_cap", "--format=csv,noheader")
                    .redirectErrorStream(true).start();
            proc.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.isBlank()) continue;
                    int comma = line.lastIndexOf(',');
                    out.add(comma < 0 ? line.strip()
                            : line.substring(0, comma).strip() + " (sm " + line.substring(comma + 1).strip() + ")");
                }
            }
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0 || out.isEmpty()) return null;
        } catch (IOException e) {
            log.println("nvidia-smi: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out;
    }

    private StepId currentStep() {
        synchronized (lock) {
            return steps.values().stream().filter(s -> s.status() == Status.RUNNING).map(Step::id)
                    .findFirst().orElse(StepId.GPU);
        }
    }

    private void failRunningStep(String why) {
        StepId id = currentStep();
        set(id, Status.FAILED, why, -1);
    }

    private static PrintWriter openLog(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            return new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Paths paths() {
        return new Paths(TGMPipe.slmDir(), TGMPipe.kernelCacheDir(), TGMPipe.toolsDir(),
                TGMPipe.logDir().resolve("setup.log"));
    }

    private record Paths(Path slmDir, Path kernelCache, Path toolsDir, Path logFile) {
        Path stampFile() {
            return kernelCache.resolve(STAMP);
        }
    }

    /** A step that could not complete; its message is shown to the player. */
    private static final class StepFailure extends RuntimeException {
        final StepId step;

        StepFailure(StepId step, String message) {
            super(message);
            this.step = step;
        }
    }
}
