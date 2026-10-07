package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.automation.UiPreviewAutomation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.ops.UiInspector;
import com.openmason.main.systems.uiEditor.ops.UiJson;
import com.openmason.main.systems.uiEditor.ops.UiOpBatch;
import com.openmason.main.systems.uiEditor.ops.UiOpException;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.view.UiEditorWorkspace;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Thread-safe facade over the UI Editor for the {@code ui_*} MCP tools (#324). Edits go through
 * {@link UiAutomation} → op batches → the editor's command layer, so every {@code ui_ops} call is
 * one undo step in the History panel; file writes go through the agent write sandbox and Save
 * Sheet ({@link AssetWriteService}); preview and capture only touch runtime and view state.
 *
 * <p>Every document access hops to the UI thread ({@link MainThreadExecutor}) and blocks the
 * caller; writes are resolved and approved off the UI thread, as the Save Sheet requires.
 */
public final class UiEditingService {

    private static final long TIMEOUT_MS = 20_000;

    private final Supplier<UiAutomation> automation;
    private final AssetWriteService writes;
    private final ObjectMapper mapper;
    private final UiPreviewAutomation.FrameGrabber grabber;

    public UiEditingService(Supplier<UiAutomation> automation, AssetWriteService writes, ObjectMapper mapper,
                            UiPreviewAutomation.FrameGrabber grabber) {
        this.automation = automation;
        this.writes = writes;
        this.mapper = mapper;
        this.grabber = grabber;
    }

    /** The production wiring: the workspace the shell installed, one automation per workspace. */
    public static UiEditingService forInterface(MainImGuiInterface mainInterface, AssetWriteService writes,
                                                ObjectMapper mapper) {
        Supplier<UiAutomation> supplier = new Supplier<>() {
            private UiEditorWorkspace bound;
            private UiAutomation cached;

            @Override
            public synchronized UiAutomation get() {
                UiEditorWorkspace ws = mainInterface == null ? null : mainInterface.getUiEditor();
                if (ws == null) {
                    throw new IllegalStateException("The UI Editor is not available (open a project first)");
                }
                if (ws != bound) {
                    bound = ws;
                    cached = new UiAutomation(ws.context(), ws::reveal, ws::offerRecovery);
                }
                return cached;
            }
        };
        return new UiEditingService(supplier, writes, mapper, new UiPreviewFrameGrabber());
    }

    // ── documents ───────────────────────────────────────────────────────────

    public Object documents() {
        return onMain(() -> automation.get().documents());
    }

    public Object create(String template, String id, String name) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.info(ui.create(template, id, name));
        });
    }

    public Object open(String path) {
        Path file = writes.sandbox().resolveExisting(path);
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.info(ui.open(file));
        });
    }

    public Object close(String doc, boolean discard) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            UiEditorDocument d = ui.document(doc);
            String id = d.archive().manifest().documentId();
            ui.close(d, discard);
            return McpAck.ok().with("closed", id).with("open", ui.service().documents().size());
        });
    }

    public Object activate(String doc) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            UiEditorDocument d = ui.document(doc);
            ui.activate(d);
            return ui.info(d);
        });
    }

    // ── inspection ──────────────────────────────────────────────────────────

    public Object tree(String doc, boolean internals) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.tree(ui.document(doc), internals);
        });
    }

    public Object get(String doc, String key, boolean computed) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.element(ui.document(doc), key, computed);
        });
    }

    public Object diagnostics(String doc) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            List<Map<String, Object>> rows = ui.diagnostics(ui.document(doc));
            return Map.of("count", rows.size(), "findings", rows);
        });
    }

    public Object styleSheets(String doc, String key) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.styleSheets(ui.document(doc), key);
        });
    }

    // ── edits ───────────────────────────────────────────────────────────────

    /**
     * Validates the batch here (no document access), then runs it as one undo step on the UI
     * thread. A refusal names the failing op; the document is unchanged.
     */
    public Object ops(String doc, JsonNode opsArg, String label, boolean verbose) {
        UiOpBatch batch = parse(opsArg, label);
        return onMain(() -> {
            UiAutomation ui = automation.get();
            UiEditorDocument d = ui.document(doc);
            Map<String, Object> result = ui.apply(d, batch);
            if (verbose) {
                result.put("tree", ui.tree(d, false));
            }
            return result;
        });
    }

    /** {@code ops} is an op array, a batch object, or either as JSON text. */
    UiOpBatch parse(JsonNode opsArg, String label) {
        if (opsArg == null || opsArg.isNull()) {
            throw new IllegalArgumentException("ops is required: [{\"op\":\"create\",\"type\":\"Label\",...}]");
        }
        JsonNode root = opsArg.isTextual() ? UiJson.parse(mapper, opsArg.asText()) : opsArg;
        if (label != null && root.isArray()) {
            root = mapper.createObjectNode().put("label", label).set("ops", root);
        } else if (label != null && root.isObject()) {
            root = ((com.fasterxml.jackson.databind.node.ObjectNode) root.deepCopy()).put("label", label);
        }
        try {
            return UiOpBatch.parse(root);
        } catch (UiOpException e) {
            throw teach(e);
        }
    }

    static IllegalArgumentException teach(UiOpException e) {
        String where = e.opIndex() >= 0 && !e.getMessage().startsWith("op ") ? "op " + e.opIndex() + ": " : "";
        return new IllegalArgumentException(where + e.getMessage() + (e.hint() != null ? " — " + e.hint() : "")
            + " (nothing was applied)");
    }

    public Object undo(String doc, boolean redo) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.undo(ui.document(doc), redo);
        });
    }

    // ── preview ─────────────────────────────────────────────────────────────

    public Object preview(String doc, String mode, Map<String, List<String>> force, boolean clearForced,
                          UiPreviewAutomation.Frame frame) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.preview().set(ui.document(doc), mode, force, clearForced, frame);
        });
    }

    public McpImageContent capture(String doc, String source, UiPreviewAutomation.Frame frame, int maxSize) {
        BufferedImage img = onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.preview().capture(ui.document(doc), source, frame, grabber);
        });
        BufferedImage out = maxSize > 0 ? McpImageCodec.downscale(img, Math.clamp(maxSize, 64, 4096)) : img;
        return new McpImageContent(McpImageCodec.encodePngBase64(out), "image/png");
    }

    public Object input(String doc, JsonNode events, int advanceMs) {
        if (events == null || !events.isArray() || events.isEmpty()) {
            throw new IllegalArgumentException("events is a non-empty array, e.g. [{\"type\":\"click\",\"key\":\"quit\"}]");
        }
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.preview().input(ui.document(doc), events, advanceMs);
        });
    }

    public Object console(String doc, int limit) {
        return onMain(() -> {
            UiAutomation ui = automation.get();
            return ui.preview().console(ui.document(doc), Math.clamp(limit, 1, 500));
        });
    }

    // ── files ───────────────────────────────────────────────────────────────

    private record Target(UiEditorDocument doc, Path file, Path fallback, String stem) {
    }

    /**
     * Saves the document: in place when it has a file, else at its convention path
     * {@code UI/<ns>/<path>.omui}; {@code path} saves as (sandboxed); {@code prompt} opens the
     * Save Sheet. The policy decides whether the user is asked.
     */
    public Object save(String doc, String path, boolean prompt, boolean overwrite) {
        Target t = onMain(() -> {
            UiAutomation ui = automation.get();
            UiEditorDocument d = ui.document(doc);
            ui.requireSavable(d);
            String id = d.archive().manifest().documentId();
            return new Target(d, d.file(), ui.defaultSaveTarget(d), id.substring(id.lastIndexOf('/') + 1));
        });
        AssetWriteService.Writer writer = target -> {
            automation.get().saveTo(t.doc(), target);
            return true;
        };
        WriteOutcome outcome;
        if (path == null && !prompt && t.file() != null && !insideGame(t.file())) {
            // the document's own file: re-saved in place like every editor's Ctrl+S
            outcome = writes.saveInPlace(WriteKind.OMUI, t.file().toString(), writer);
        } else if (path == null && !prompt && t.file() != null) {
            // a shipped game resource: the write policy decides (ASK_RISKY asks the user)
            outcome = writes.save(WriteRequest.of(WriteKind.OMUI, t.file().toString(), false, true, t.stem())
                .withDetails(List.of("UI document " + t.doc().title() + " (game resources)")), writer);
        } else {
            String raw = path;
            if (raw == null && !prompt) {
                if (t.fallback() == null) {
                    throw new IllegalArgumentException("No project is open, so the document has no convention path:"
                        + " pass path (exports:<name>.omui) or prompt:true");
                }
                raw = t.fallback().toString();
            }
            outcome = writes.save(WriteRequest.of(WriteKind.OMUI, raw, prompt, overwrite, t.stem())
                .withDetails(List.of("UI document " + t.doc().title())), writer);
        }
        if (!outcome.ok()) {
            return outcome;
        }
        return onMain(() -> {
            Map<String, Object> m = new LinkedHashMap<>(automation.get().info(t.doc()));
            m.put("saved", outcome.path());
            m.put("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
            return m;
        });
    }

    /** True when {@code file} lies under the game-resources root (writes there always go through the policy). */
    private boolean insideGame(Path file) {
        try {
            return writes.sandbox().check(WriteKind.OMUI, file.toAbsolutePath()).insideGame();
        } catch (IllegalArgumentException outsideRoots) {
            return false; // opened by the author from elsewhere: their own file
        }
    }

    /** Exports an SBUI (and its report) through the sandbox; default {@code Exports/UI/<stem>.sbui}. */
    public Object export(String doc, String path, String mode, boolean prompt, boolean overwrite) {
        ExportMode m = exportMode(mode);
        Target t = onMain(() -> {
            UiAutomation ui = automation.get();
            UiEditorDocument d = ui.document(doc);
            Path def = ui.defaultExportTarget(d);
            String name = def.getFileName().toString();
            return new Target(d, null, def, name.substring(0, name.length() - ".sbui".length()));
        });
        UiDocumentService.ExportResult[] result = new UiDocumentService.ExportResult[1];
        String raw = path != null || prompt ? path : t.fallback().toString();
        WriteOutcome outcome = writes.save(WriteRequest.of(WriteKind.SBUI, raw, prompt, overwrite, t.stem())
            .withDetails(List.of("SBUI export of " + t.doc().title() + " (" + m.name().toLowerCase(Locale.ROOT) + ")",
                "also writes <name>.report.json beside it")),
            target -> {
                String name = target.getFileName().toString();
                Path report = target.resolveSibling(name.substring(0, name.length() - ".sbui".length())
                    + ".report.json");
                if (java.nio.file.Files.isSymbolicLink(report)) {
                    throw new IllegalArgumentException("path_outside_sandbox: " + report + " is a symbolic link");
                }
                result[0] = automation.get().export(t.doc(), target, m);
                return true;
            });
        if (!outcome.ok()) {
            return outcome;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", outcome.path());
        out.put("mode", m.name().toLowerCase(Locale.ROOT));
        String p = outcome.path();
        out.put("report", p.substring(0, p.length() - ".sbui".length()) + ".report.json");
        if (result[0] != null && !result[0].diagnostics().isEmpty()) {
            out.put("diagnostics", result[0].diagnostics().stream()
                .filter(x -> x.severity() != com.openmason.engine.format.omui.UiDiagnostic.Severity.INFO)
                .map(x -> x.severity().name().toLowerCase(Locale.ROOT) + ": " + x.message()).limit(20).toList());
        }
        out.put("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
        return out;
    }

    private static ExportMode exportMode(String mode) {
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("shared")) {
            return ExportMode.SHARED;
        }
        if (mode.equalsIgnoreCase("collect_all") || mode.equalsIgnoreCase("collect-all")) {
            return ExportMode.COLLECT_ALL;
        }
        throw McpErrors.invalidEnum("mode", mode, List.of("shared", "collect_all"));
    }

    /**
     * Imports an {@code .sbui} into the open project: its collected assets become project assets
     * (never overwriting different files; colliding ids get {@code -imported}) and the document is
     * saved at a fresh convention path and opened. Only new files, only inside the project root.
     */
    public Object importSbui(String path) {
        Path file = writes.sandbox().resolveExisting(path);
        Path target = onMain(() -> automation.get().importTarget(file));
        if (target == null) {
            throw new IllegalStateException("Importing writes into the project: open a project first");
        }
        UiEditorDocument[] opened = new UiEditorDocument[1];
        // the document's write goes through the policy; collected assets are written only once it is approved
        WriteOutcome outcome = writes.save(WriteRequest.of(WriteKind.OMUI, target.toString(), false, false,
                target.getFileName().toString().replaceFirst("\\.omui$", ""))
            .withDetails(List.of("Import of " + file.getFileName() + " (its collected assets become project assets)")),
            approved -> {
                opened[0] = automation.get().importSbui(file, approved);
                return true;
            });
        if (!outcome.ok()) {
            return outcome;
        }
        return onMain(() -> automation.get().info(opened[0]));
    }

    // ── threading ───────────────────────────────────────────────────────────

    private static <T> T onMain(Callable<T> task) {
        try {
            return MainThreadExecutor.submit(task).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("UI editor call timed out on the main thread", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof UiOpException uoe) {
                throw teach(uoe);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
