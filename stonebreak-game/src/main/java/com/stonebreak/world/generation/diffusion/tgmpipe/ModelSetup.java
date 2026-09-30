package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.world.generation.diffusion.TGMPipeException;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sets up {@value TGMPipe#MODEL_NAME} on game launch, so a player only ever starts the game:
 *
 * <ol>
 *   <li><b>Graphics card</b> — an NVIDIA GPU ({@code nvidia-smi}); without one the model cannot run and
 *       nothing is downloaded.</li>
 *   <li><b>Package manager</b> — {@code uv}: the one this game downloaded before, one on the PATH, or a
 *       pinned release downloaded into {@code Models/tools/uv/} ({@link UvTool}).</li>
 *   <li><b>Python environment</b> — {@code uv sync --frozen} in {@code Models/DaedalusTGM-Exp/} (Python,
 *       CUDA PyTorch, Triton: several GB the first time, seconds afterwards).</li>
 *   <li><b>Model check</b> and <b>GPU kernels</b> — {@code python -m terrain_slm.tgmpipe.warmup}: loads the
 *       model on the GPU and compiles the custom kernels into {@code Models/triton_cache/}, where the
 *       TGMPipe service finds them, so the first world never waits on a compiler.</li>
 * </ol>
 *
 * <p>It runs on its own thread from the moment the game starts (under the intro). A stamp in the
 * environment records what was set up (hashes of {@code uv.lock} + {@code pyproject.toml} and of the
 * kernel sources), so an unchanged install costs a few file reads and shows nothing; an update redoes
 * only what changed. Everything the steps print goes to {@code Models/logs/setup.log}.
 *
 * <p>{@code -Dstonebreak.modelSetup=off} disables it (the service is then launched as before);
 * {@code -Dstonebreak.modelSetup.force=true} ignores the stamp and runs every step.
 */
public final class ModelSetup {

    private static final Logger LOG = Logger.getLogger(ModelSetup.class.getName());
    private static final ModelSetup INSTANCE = new ModelSetup();
    private static final String STAMP = ".stonebreak-setup";
    private static final String UNAVAILABLE_MARKER = ".setup-unavailable";

    public enum StepId {
        GPU("Checking graphics card"),
        UV("Getting package manager"),
        ENVIRONMENT("Installing Python environment"),
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
     * @param activity   the latest thing a step printed (a download, a compile), for the screen
     * @param message    why setup failed or the model is unavailable, else null
     * @param workNeeded this launch had something to install or compile
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
    private volatile Process child;

    private ModelSetup() {
        resetSteps();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = child;
            if (p != null) p.descendants().forEach(ProcessHandle::destroy);
            if (p != null) p.destroy();
        }, "model-setup-shutdown"));
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
     * failed, the first time the model turns out unavailable, and briefly after a launch that installed
     * or compiled something (so the player sees it finish). An unchanged install shows nothing.
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
        Properties stamp = readStamp(p);
        String envHash = hashFiles(List.of(p.slmDir.resolve("uv.lock"), p.slmDir.resolve("pyproject.toml")));
        String kernelHash = kernelHash(p);
        boolean envOk = !force && Files.isExecutable(p.python) && envHash.equals(stamp.getProperty("env"));
        boolean kernelsOk = !force && envOk && kernelHash.equals(stamp.getProperty("kernels"))
                && Files.isDirectory(p.tritonCache);
        boolean knownUnavailable = Files.exists(p.slmDir.resolve(UNAVAILABLE_MARKER));
        synchronized (lock) {
            unavailableKnownBefore = knownUnavailable;
            workNeeded = !(envOk && kernelsOk);
        }
        if (envOk && kernelsOk) {
            for (StepId id : StepId.values()) set(id, Status.SKIPPED, "up to date", -1);
            finish(Outcome.READY, null);
            return;
        }
        log.println("env " + (envOk ? "up to date" : "needs sync") + ", kernels " + (kernelsOk ? "up to date" : "need compiling"));

        // 1. Graphics card
        set(StepId.GPU, Status.RUNNING, "", -1);
        String gpu = nvidiaGpu(log);
        if (gpu == null) {
            String why = "no NVIDIA graphics card found (the terrain model needs one); standard terrain still works";
            set(StepId.GPU, Status.FAILED, "No NVIDIA GPU found", -1);
            for (StepId id : List.of(StepId.UV, StepId.ENVIRONMENT, StepId.VERIFY, StepId.KERNELS)) {
                set(id, Status.SKIPPED, "", -1);
            }
            try {
                Files.writeString(p.slmDir.resolve(UNAVAILABLE_MARKER), why + "\n");
            } catch (IOException ignored) {
                // the marker only keeps the screen from reappearing every launch
            }
            finish(Outcome.UNAVAILABLE, why);
            return;
        }
        set(StepId.GPU, Status.DONE, gpu, -1);

        // 2 + 3. Package manager and Python environment
        if (envOk) {
            set(StepId.UV, Status.SKIPPED, "not needed", -1);
            set(StepId.ENVIRONMENT, Status.SKIPPED, "already installed", -1);
        } else {
            set(StepId.UV, Status.RUNNING, "", -1);
            Path uv;
            try {
                uv = UvTool.locate(p.toolsDir, log, (text, fraction) -> {
                    detail(StepId.UV, text, fraction);
                    activity(text);
                });
            } catch (IOException | RuntimeException e) {
                throw new StepFailure(StepId.UV, "could not get uv: " + e.getMessage());
            }
            set(StepId.UV, Status.DONE, UvTool.describe(uv, p.toolsDir), -1);
            syncEnvironment(uv, p, log);
            writeStamp(p, envHash, null);
        }

        // 4 + 5. Model check and kernels
        warmUp(p, log);
        writeStamp(p, envHash, kernelHash);
        finish(Outcome.READY, null);
    }

    private static final Pattern DL_START = Pattern.compile("^\\s*Downloading (\\S+).*\\(([\\d.]+)\\s*([KMGT]?i?B)\\)\\s*$");
    private static final Pattern DL_DONE = Pattern.compile("^\\s*Downloaded (\\S+)");

    private void syncEnvironment(Path uv, Paths p, PrintWriter log) {
        set(StepId.ENVIRONMENT, Status.RUNNING, Files.isDirectory(p.slmDir.resolve(".venv"))
                ? "Updating" : "First time: downloads a few GB, this can take several minutes", -1);
        Map<String, Double> sizes = new LinkedHashMap<>();
        List<String> done = new ArrayList<>();
        int exit = runProcess(List.of(uv.toString(), "sync", "--frozen", "--color", "never"), p.slmDir,
                Map.of("TRITON_CACHE_DIR", p.tritonCache.toString()), log, line -> {
                    String l = line.strip();
                    if (l.isEmpty()) return;
                    Matcher start = DL_START.matcher(l);
                    Matcher end = DL_DONE.matcher(l);
                    if (start.find()) {
                        sizes.put(start.group(1), bytes(start.group(2), start.group(3)));
                    } else if (end.find()) {
                        done.add(end.group(1));
                    }
                    String say = friendlyUvLine(l);
                    if (say == null) return;   // warnings and per-package lines: the log keeps them
                    double total = sizes.values().stream().mapToDouble(Double::doubleValue).sum();
                    double got = done.stream().mapToDouble(n -> sizes.getOrDefault(n, 0.0)).sum();
                    String progressText = sizes.isEmpty() || done.size() == sizes.size() ? say
                            : String.format("%s of %s downloaded (%d of %d large packages)", mb(got), mb(total),
                            done.size(), sizes.size());
                    detail(StepId.ENVIRONMENT, progressText, total > 0 && done.size() < sizes.size() ? got / total : -1);
                    activity(say);
                });
        if (exit != 0) {
            throw new StepFailure(StepId.ENVIRONMENT, "uv sync failed (exit " + exit + "): " + lastActivity());
        }
        set(StepId.ENVIRONMENT, Status.DONE, sizes.isEmpty() ? "installed" : mb(
                sizes.values().stream().mapToDouble(Double::doubleValue).sum()) + " downloaded", -1);
    }

    /** What the screen says for one line of {@code uv sync} output; null = not worth showing. */
    static String friendlyUvLine(String line) {
        if (line.startsWith("warning:") || line.startsWith("+ ") || line.startsWith("- ")
                || line.startsWith("~ ") || line.startsWith("If ")) {
            return null;
        }
        if (line.startsWith("Using CPython")) return "Using Python " + line.substring("Using CPython".length()).strip();
        if (line.startsWith("Creating virtual environment")) return "Creating the Python environment";
        if (line.startsWith("Building terrain-slm") || line.startsWith("Built terrain-slm")) return "Building the model package";
        // uv prints nothing while it copies the packages into place, which is the long part without downloads.
        if (line.startsWith("Prepared")) return line + " - installing (copies a few GB)";
        return line;
    }

    private void warmUp(Paths p, PrintWriter log) {
        if (!Files.isExecutable(p.python)) {
            throw new StepFailure(StepId.VERIFY, "Python environment missing: " + p.python);
        }
        set(StepId.VERIFY, Status.RUNNING, "", -1);
        ObjectMapper json = new ObjectMapper();
        String[] gpuName = {""};
        int[] compiled = {0};
        String[] error = {null};
        List<String> cmd = List.of(p.python.toString(), "-m", "terrain_slm.tgmpipe.warmup",
                System.getProperty("stonebreak.tgmpipe.model", TGMPipe.DEFAULT_MODEL),
                "--device", System.getProperty("stonebreak.tgmpipe.device", "cuda"));
        int exit = runProcess(cmd, p.slmDir, Map.of("TRITON_CACHE_DIR", p.tritonCache.toString()), log, line -> {
            if (!line.startsWith("{")) {
                if (!line.isBlank()) activity(line.strip());
                return;
            }
            JsonNode n;
            try {
                n = json.readTree(line);
            } catch (IOException e) {
                return;
            }
            if (n.has("error")) {
                error[0] = n.get("error").asText();
            } else if (n.has("done")) {
                gpuName[0] = n.path("gpu").asText();
                set(StepId.VERIFY, Status.DONE, gpuName[0] + " (" + n.path("device").asText() + ")", -1);
                set(StepId.KERNELS, Status.DONE, String.format("%d kernel groups in %.1f s", compiled[0],
                        n.path("seconds").asDouble()), -1);
            } else if ("compile".equals(n.path("stage").asText())) {
                if (steps.get(StepId.VERIFY).status() == Status.RUNNING) {
                    set(StepId.VERIFY, Status.DONE, "model loaded", -1);
                }
                compiled[0]++;
                String what = n.path("detail").asText();
                set(StepId.KERNELS, Status.RUNNING, "Compiling " + what + " (" + compiled[0] + " of 3)",
                        (compiled[0] - 1) / 3.0);
                activity("Compiling " + what);
            } else if (n.has("detail")) {
                detail(StepId.VERIFY, n.get("detail").asText(), -1);
                activity(n.get("detail").asText());
            }
        });
        if (exit != 0 || error[0] != null) {
            StepId at = steps.get(StepId.KERNELS).status() == Status.RUNNING ? StepId.KERNELS : StepId.VERIFY;
            throw new StepFailure(at, error[0] != null ? error[0] : "exit " + exit + ": " + lastActivity());
        }
    }

    // ------------------------------------------------------------------ helpers

    private String nvidiaGpu(PrintWriter log) {
        if (Boolean.getBoolean("stonebreak.modelSetup.skipGpuCheck")) return "not checked";
        List<String> names = new ArrayList<>();
        try {
            int exit = runProcess(List.of("nvidia-smi", "--query-gpu=name", "--format=csv,noheader"), null,
                    Map.of(), log, l -> {
                        if (!l.isBlank()) names.add(l.strip());
                    });
            if (exit != 0 || names.isEmpty()) return null;
        } catch (StepFailure e) {
            return null;
        }
        return names.size() == 1 ? names.get(0) : names.size() + " GPUs: " + String.join(", ", names);
    }

    /** Runs {@code cmd}, logging and handing every output line (stdout + stderr) to {@code onLine}. */
    private int runProcess(List<String> cmd, Path dir, Map<String, String> env, PrintWriter log, Consumer<String> onLine) {
        log.println("$ " + String.join(" ", cmd));
        log.flush();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().putAll(env);
        pb.environment().put("PYTHONUNBUFFERED", "1");
        try {
            Process proc = pb.start();
            child = proc;
            proc.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.println(line);
                    log.flush();
                    onLine.accept(line);
                }
            }
            int exit = proc.waitFor();
            log.println("(exit " + exit + ")");
            return exit;
        } catch (IOException e) {
            log.println("could not run: " + e.getMessage());
            throw new StepFailure(currentStep(), "could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailure(currentStep(), "interrupted");
        } finally {
            child = null;
        }
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

    private String lastActivity() {
        synchronized (lock) {
            return activity;
        }
    }

    private static double bytes(String number, String unit) {
        double v = Double.parseDouble(number);
        return switch (unit.charAt(0)) {
            case 'K' -> v * 1024;
            case 'M' -> v * 1024 * 1024;
            case 'G' -> v * 1024 * 1024 * 1024;
            case 'T' -> v * 1024 * 1024 * 1024 * 1024;
            default -> v;
        };
    }

    private static String mb(double bytes) {
        return bytes >= 1024.0 * 1024 * 1024 ? String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
                : String.format("%.0f MB", bytes / (1024.0 * 1024));
    }

    private static String kernelHash(Paths p) {
        Path dir = p.slmDir.resolve("terrain_slm/kernels");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(f -> f.toString().endsWith(".py")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.add(p.slmDir.resolve("terrain_slm/tgmpipe/warmup.py"));
        return hashFiles(files) + ":" + System.getProperty("stonebreak.tgmpipe.model", TGMPipe.DEFAULT_MODEL);
    }

    static String hashFiles(List<Path> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files) {
                md.update(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                if (Files.exists(f)) md.update(Files.readAllBytes(f));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties readStamp(Paths p) {
        Properties props = new Properties();
        Path f = p.venv().resolve(STAMP);
        if (Files.exists(f)) {
            try (var in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException ignored) {
                // unreadable = not set up
            }
        }
        return props;
    }

    /** The stamp lives inside the environment: deleting .venv forgets the setup with it. */
    private static void writeStamp(Paths p, String env, String kernels) {
        Properties props = new Properties();
        props.setProperty("env", env);
        if (kernels != null) props.setProperty("kernels", kernels);
        try (var out = Files.newOutputStream(p.venv().resolve(STAMP))) {
            props.store(out, TGMPipe.MODEL_NAME + " setup (written by the game; delete to redo it)");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not write the setup stamp; setup will run again next launch", e);
        }
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
        return new Paths(TGMPipe.slmDir(), TGMPipe.pythonExe(), TGMPipe.tritonCacheDir(),
                TGMPipe.modelsDir().resolve("tools"), TGMPipe.logDir().resolve("setup.log"));
    }

    private record Paths(Path slmDir, Path python, Path tritonCache, Path toolsDir, Path logFile) {
        Path venv() {
            return slmDir.resolve(".venv");
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
