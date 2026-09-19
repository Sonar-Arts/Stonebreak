package com.openmason.main.systems.io;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.mcp.approval.ApprovalGate;
import com.openmason.main.systems.mcp.approval.McpApprovalGate;
import com.openmason.main.systems.mcp.approval.PromptGate;
import com.openmason.main.systems.mcp.approval.SaveSheetGate;
import com.openmason.main.systems.mcp.approval.SaveSheetRequest;
import com.openmason.main.systems.mcp.approval.SaveSheetResult;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The one seam every agent-initiated file write goes through.
 *
 * <ol>
 *   <li>Resolve the caller's path through the {@link WriteSandbox} (or skip
 *       straight to the Save Sheet when no path / {@code prompt:true}).</li>
 *   <li>Apply the {@link WritePolicy}: write silently, or ask the human in the
 *       in-app Save Sheet ({@link SaveSheetGate}).</li>
 *   <li>Run the caller's {@link Writer} on the main thread against the final
 *       target and report a structured {@link WriteOutcome} — never a protocol
 *       error for "the user said no".</li>
 * </ol>
 *
 * <p>Must be called from a background thread (MCP HTTP handler, assistant
 * loop): the Save Sheet renders on the main thread and would deadlock.
 */
public final class AssetWriteService {

    private static final Logger logger = LoggerFactory.getLogger(AssetWriteService.class);

    private static final int DEFAULT_TIMEOUT_S = 180;
    private static final int MAX_TIMEOUT_S = 600;
    private static final long WRITE_TIMEOUT_S = 60;
    private static final int MAX_SUBFOLDERS = 40;

    /** Performs the actual write on the main thread; true on success. */
    @FunctionalInterface
    public interface Writer {
        boolean write(Path target) throws Exception;
    }

    /**
     * What a tool wants written.
     *
     * @param kind                 file kind (extension, default root)
     * @param rawPath              caller spelling, or null to ask the user
     * @param prompt               force the Save Sheet even with a path
     * @param overwrite            explicit acknowledgement that an existing file may be replaced
     * @param suggestedName        name pre-filled in the sheet
     * @param suggestedRoot        root pre-selected in the sheet (null = kind default)
     * @param suggestedSubdir      sub-folder pre-filled (null = kind default)
     * @param detailLines          extra context shown in the sheet
     * @param requiresPriorOmoSave the sheet explains an .omo save happens first
     * @param agentLabel           who is asking
     * @param timeoutSeconds       how long the sheet may wait (0 = default)
     */
    public record WriteRequest(WriteKind kind, String rawPath, boolean prompt, boolean overwrite,
                               String suggestedName, WriteRoot suggestedRoot, String suggestedSubdir,
                               List<String> detailLines, boolean requiresPriorOmoSave,
                               String agentLabel, int timeoutSeconds) {

        public WriteRequest {
            detailLines = detailLines == null ? List.of() : List.copyOf(detailLines);
        }

        public static WriteRequest of(WriteKind kind, String rawPath, boolean prompt,
                                      boolean overwrite, String suggestedName) {
            return new WriteRequest(kind, rawPath, prompt, overwrite, suggestedName,
                    null, null, List.of(), false, null, 0);
        }

        public WriteRequest withDetails(List<String> lines) {
            return new WriteRequest(kind, rawPath, prompt, overwrite, suggestedName,
                    suggestedRoot, suggestedSubdir, lines, requiresPriorOmoSave, agentLabel,
                    timeoutSeconds);
        }

        public WriteRequest withSuggestedRoot(WriteRoot root, String subdir) {
            return new WriteRequest(kind, rawPath, prompt, overwrite, suggestedName,
                    root, subdir, detailLines, requiresPriorOmoSave, agentLabel, timeoutSeconds);
        }

        public WriteRequest withPriorOmoSave(boolean flag) {
            return new WriteRequest(kind, rawPath, prompt, overwrite, suggestedName,
                    suggestedRoot, suggestedSubdir, detailLines, flag, agentLabel, timeoutSeconds);
        }

        public WriteRequest withTimeout(int seconds) {
            return new WriteRequest(kind, rawPath, prompt, overwrite, suggestedName,
                    suggestedRoot, suggestedSubdir, detailLines, requiresPriorOmoSave, agentLabel,
                    seconds);
        }

        public boolean hasPath() {
            return rawPath != null && !rawPath.isBlank();
        }
    }

    /**
     * Structured result of a write attempt. {@code status} is one of
     * {@code saved}, {@code declined}, {@code failed}.
     */
    public record WriteOutcome(boolean ok, String status, String path, String root,
                               Boolean overwritten, Boolean promptedUser, Boolean inPlace,
                               String reason, String message) {

        public static WriteOutcome saved(WriteTarget t, boolean prompted, boolean overwritten) {
            return new WriteOutcome(true, "saved", t.path().toString(), t.root().prefix(),
                    overwritten, prompted, null, null, null);
        }

        public static WriteOutcome savedInPlace(WriteKind kind, Path path) {
            return new WriteOutcome(true, "saved", path.toString(), null, true, false, true,
                    null, null);
        }

        public static WriteOutcome declined(String reason, String message) {
            return new WriteOutcome(false, "declined", null, null, null, true, null, reason, message);
        }

        public static WriteOutcome failed(String reason, String message, Path path) {
            return new WriteOutcome(false, "failed", path == null ? null : path.toString(), null,
                    null, null, null, reason, message);
        }
    }

    private final MainImGuiInterface mainInterface;
    private final WriteSandbox sandbox;
    private final Supplier<WritePolicy> policy;

    public AssetWriteService(MainImGuiInterface mainInterface, WriteSandbox sandbox,
                             Supplier<WritePolicy> policy) {
        this.mainInterface = mainInterface;
        this.sandbox = sandbox;
        this.policy = policy;
    }

    public WriteSandbox sandbox() {
        return sandbox;
    }

    // ---------------------------------------------------------------- save

    /** Resolve, ask if the policy says so, then write. */
    public WriteOutcome save(WriteRequest req, Writer writer) {
        WriteTarget target = null;
        if (req.hasPath()) {
            target = sandbox.resolve(req.kind(), req.rawPath()); // throws IAE when refused
        }
        boolean ask = target == null || req.prompt() || mustAsk(target, req, policy.get());
        boolean prompted = false;
        if (ask) {
            SaveSheetResult answer = askUser(buildSheetRequest(req, target));
            if (!answer.saved()) {
                return switch (answer.status()) {
                    case UNAVAILABLE -> WriteOutcome.declined("prompt_unavailable",
                            "the Open Mason UI is not running, so nobody can pick a target — "
                                    + "pass an explicit file_path inside a writable root");
                    case BUSY -> WriteOutcome.declined("busy",
                            "another dialog is already open in Open Mason — retry shortly");
                    case TIMEOUT -> WriteOutcome.declined("timeout",
                            "the Save Sheet timed out without a user decision");
                    default -> WriteOutcome.declined("user_declined",
                            "the user cancelled the save");
                };
            }
            // The sheet only offers sandboxed targets, but re-check — it is cheap.
            target = sandbox.check(req.kind(), answer.path());
            prompted = true;
        }
        return write(target, writer, prompted);
    }

    /**
     * Re-save the file the user already has open (its own path, no dialog).
     * The user's working file is theirs; the policy is about <em>new</em>
     * places an agent may write to.
     */
    public WriteOutcome saveInPlace(WriteKind kind, String currentPath, Writer writer) {
        if (currentPath == null || currentPath.isBlank()) {
            return WriteOutcome.failed("no_current_path",
                    "nothing is open under a file path yet — pass file_path or prompt:true", null);
        }
        Path path = Path.of(currentPath).toAbsolutePath().normalize();
        try {
            boolean ok = runWriter(writer, path);
            if (!ok) {
                return WriteOutcome.failed("write_failed",
                        "the editor reported a failed save — see the Open Mason log", path);
            }
            logger.info("Agent re-saved {} in place", path);
            return WriteOutcome.savedInPlace(kind, path);
        } catch (Exception e) {
            return WriteOutcome.failed("write_failed", e.getMessage(), path);
        }
    }

    private WriteOutcome write(WriteTarget target, Writer writer, boolean prompted) {
        boolean overwritten = Files.exists(target.path());
        try {
            boolean ok = runWriter(writer, target.path());
            if (!ok) {
                return WriteOutcome.failed("write_failed",
                        "the editor reported a failed save — see the Open Mason log", target.path());
            }
            logger.info("Agent wrote {} ({}, root={}, prompted={})", target.path(),
                    target.kind().id(), target.root().prefix(), prompted);
            return WriteOutcome.saved(target, prompted, overwritten);
        } catch (Exception e) {
            logger.warn("Agent write to {} failed: {}", target.path(), e.toString());
            return WriteOutcome.failed("write_failed", e.getMessage(), target.path());
        }
    }

    private static boolean runWriter(Writer writer, Path path) throws Exception {
        try {
            return MainThreadExecutor.submit(() -> {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                return writer.write(path);
            }).get(WRITE_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("write timed out on the main thread");
        }
    }

    // -------------------------------------------------------------- policy

    /** True when the policy wants the human to see this write. */
    static boolean mustAsk(WriteTarget target, WriteRequest req, WritePolicy policy) {
        return switch (policy) {
            case ASK_ALWAYS -> true;
            case ASK_RISKY -> target.exists() || target.insideGame();
            case ASK_NEVER_IN_PROJECT -> {
                if (target.insideGame()) {
                    yield true;
                }
                if (target.exists() && !req.overwrite()) {
                    throw new IllegalArgumentException("file_exists: " + target.path()
                            + " — pass overwrite:true to replace it, or prompt:true to let the user decide");
                }
                yield false;
            }
        };
    }

    private SaveSheetRequest buildSheetRequest(WriteRequest req, WriteTarget target) {
        WriteKind kind = req.kind();
        WriteRoot root;
        String subdir;
        String name;
        Path overwrite = null;
        if (target != null) {
            root = target.root();
            Path rootPath = sandbox.roots().path(root);
            Path rel = rootPath != null ? WriteSandbox.canonicalize(rootPath).relativize(target.path()) : null;
            subdir = rel != null && rel.getParent() != null ? rel.getParent().toString() : "";
            name = target.fileName();
            overwrite = target.exists() ? target.path() : null;
        } else {
            root = req.suggestedRoot() != null ? req.suggestedRoot() : kind.defaultRoot();
            subdir = req.suggestedSubdir() != null ? req.suggestedSubdir() : kind.defaultSubdir();
            String suggested = req.suggestedName();
            name = suggested == null || suggested.isBlank() ? kind.fallbackName() : suggested;
        }
        int timeout = req.timeoutSeconds() <= 0 ? DEFAULT_TIMEOUT_S
                : Math.min(req.timeoutSeconds(), MAX_TIMEOUT_S);
        String agent = req.agentLabel() != null ? req.agentLabel() : "an agent";
        String title = "Save " + kind.extension() + " — requested by " + agent;
        return new SaveSheetRequest(kind, title, agent, root, subdir, name, req.detailLines(),
                overwrite, req.requiresPriorOmoSave(), Duration.ofSeconds(timeout));
    }

    private SaveSheetResult askUser(SaveSheetRequest request) {
        if (MainThreadExecutor.isMainThread()) {
            throw new IllegalStateException("AssetWriteService.save must not run on the main thread");
        }
        SaveSheetGate gate = mainInterface != null ? mainInterface.getSaveSheetGate() : null;
        if (gate == null) {
            return SaveSheetResult.UNAVAILABLE;
        }
        try {
            return gate.request(request).toCompletableFuture()
                    .get(request.timeout().toSeconds() + 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return SaveSheetResult.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SaveSheetResult.DECLINED;
        } catch (ExecutionException e) {
            logger.warn("Save Sheet failed: {}", e.getCause() != null ? e.getCause() : e);
            return SaveSheetResult.DECLINED;
        }
    }

    // --------------------------------------------------------- save_targets

    /** Everything a model needs to pick a valid path without a prompt. */
    public Map<String, Object> targets() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", policy.get().name());
        out.put("policyMeaning", policy.get().label());
        out.put("pathGrammar", "absolute | project:<rel> | game:<rel> | exports:<rel> | "
                + "bare name (kind's default root + sub-folder); the extension is added when missing");
        out.put("saveSheetAvailable", mainInterface != null && mainInterface.getSaveSheetGate() != null);
        List<Map<String, Object>> roots = new ArrayList<>();
        for (WriteRoot r : WriteRoot.values()) {
            Path p = sandbox.roots().path(r);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("root", r.prefix());
            row.put("present", p != null);
            if (p != null) {
                row.put("path", p.toString());
                row.put("subfolders", subfolders(p));
            }
            roots.add(row);
        }
        out.put("roots", roots);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (WriteKind k : WriteKind.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", k.id());
            row.put("extension", k.extension());
            row.put("defaultRoot", k.defaultRoot().prefix());
            row.put("defaultSubdir", k.defaultSubdir());
            row.put("example", k.defaultRoot().prefix() + ":"
                    + (k.defaultSubdir().isEmpty() ? "" : k.defaultSubdir() + "/")
                    + "My" + capitalize(k.fallbackName()) + k.extension());
            kinds.add(row);
        }
        out.put("kinds", kinds);
        return out;
    }

    /** Whether a Save Sheet / approval dialog is waiting on the human right now. */
    public Map<String, Object> promptStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean pending = false;
        SaveSheetGate sheet = mainInterface != null ? mainInterface.getSaveSheetGate() : null;
        if (sheet != null) {
            PromptGate.Pending<SaveSheetRequest, SaveSheetResult> p = sheet.pending();
            if (p != null) {
                pending = true;
                out.put("dialog", "save_sheet");
                out.put("title", p.request().title());
                out.put("kind", p.request().kind().id());
                out.put("secondsRemaining", sheet.secondsRemaining());
            }
        }
        ApprovalGate approval = mainInterface != null ? mainInterface.getApprovalGate() : null;
        if (!pending && approval instanceof McpApprovalGate mag) {
            PromptGate.Pending<ApprovalGate.ApprovalRequest, ApprovalGate.Decision> p = mag.pending();
            if (p != null) {
                pending = true;
                out.put("dialog", "approval");
                out.put("title", p.request().title());
                out.put("action", p.request().action());
                out.put("secondsRemaining", mag.secondsRemaining());
            }
        }
        out.put("pending", pending);
        if (!pending) {
            out.put("dialog", "none");
        }
        return out;
    }

    private static List<String> subfolders(Path root) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .limit(MAX_SUBFOLDERS)
                    .forEach(out::add);
        } catch (IOException ignored) {
            // unreadable root — report none
        }
        return out;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
