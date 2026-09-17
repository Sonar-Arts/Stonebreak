package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.assets.AssetCatalog;
import com.openmason.main.systems.assets.AssetEntry;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteRoot;
import com.openmason.main.systems.menus.dialogs.GameResourceDirs;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SBO/SBE authoring for agents.
 *
 * <ul>
 *   <li>{@code sbo_export} / {@code sbe_export}: the current model → a new
 *       asset, through the same {@code ExportParameters} the export windows
 *       build, the same validation, and the write sandbox / Save Sheet. The
 *       model is saved as .omo first when it has to be (the serializers read
 *       it from disk). The written file is handed to the matching editor
 *       window, exactly as the UI export does.</li>
 *   <li>{@code sbo_editor_*} / {@code sbe_editor_*}: drive the editor windows
 *       the human sees — open a shipped asset, read/patch the draft manifest,
 *       save through the sandbox (a shipped asset lives under game resources,
 *       so overwriting it always asks).</li>
 * </ul>
 */
public final class AssetExportService {

    private static final Logger logger = LoggerFactory.getLogger(AssetExportService.class);
    private static final long MAIN_TIMEOUT_S = 30;

    public enum Kind { SBO, SBE }

    private final MainImGuiInterface mainInterface;
    private final AssetWriteService writes;
    private final ModelFileService modelFiles;
    private final SBOSerializer sboSerializer = new SBOSerializer();
    private final SBESerializer sbeSerializer = new SBESerializer();

    public AssetExportService(MainImGuiInterface mainInterface, AssetWriteService writes,
                              ModelFileService modelFiles) {
        this.mainInterface = mainInterface;
        this.writes = writes;
        this.modelFiles = modelFiles;
    }

    // ================================================================ export

    public Map<String, Object> exportSbo(JsonNode params, String filePath, boolean prompt,
                                         boolean overwrite) {
        OmoOnDisk omo = ensureOmoOnDisk("sbo");
        if (omo.failure() != null) {
            return omo.failure();
        }
        SBOFormat.ExportParameters p = AssetExportBuilder.sbo(params, omo.path(), omo.modelName(),
                writes.sandbox()::resolveExisting);
        String err = p.getValidationError();
        if (!err.isEmpty()) {
            throw new IllegalArgumentException("invalid_params: " + err);
        }
        NumericIdValidator.Domain domain = NumericIdValidator.domainFor(p.getObjectType().getId());
        if (p.getGameProperties() != null) {
            int id = p.getGameProperties().numericId();
            if (domain == NumericIdValidator.Domain.BLOCK && id <= 0) {
                throw new IllegalArgumentException("invalid_params: numericId must be > 0 for blocks");
            }
            NumericIdValidator.Result vr = NumericIdValidator.validate(domain, id, p.getObjectId());
            if (vr instanceof NumericIdValidator.Result.Conflict c) {
                throw new IllegalArgumentException("numeric_id_conflict: " + c.numericId()
                        + " is taken by " + c.existingObjectId()
                        + " — pass gameProperties.numericId (next free: "
                        + NumericIdValidator.suggestNextFreeId(domain) + ")");
            }
        }
        String compat = sboClipCompatibility(p);
        if (compat != null) {
            throw new IllegalArgumentException("clip_incompatible: " + compat);
        }
        String folder = GameResourceDirs.sboFolderFor(p.getObjectType().getId());
        String suggested = GameResourceDirs.suggestedFileName(p.getObjectName(), "sbo", "object.sbo");
        Path omoPath = omo.path();
        WriteOutcome out = writes.save(
                WriteRequest.of(WriteKind.SBO, filePath, prompt, overwrite, suggested)
                        .withSuggestedRoot(WriteRoot.GAME_RESOURCES, relativeToGame(folder, "sbo/blocks"))
                        .withDetails(List.of("Object: " + p.getObjectId() + " (" + p.getObjectType().getId() + ")",
                                "Model: " + omoPath.getFileName())),
                target -> sboSerializer.export(p, omoPath, target.toString()));
        Map<String, Object> result = outcomeMap(out, "exported");
        result.put("objectId", p.getObjectId());
        if (out.ok()) {
            handToEditor(Kind.SBO, out.path());
        }
        return result;
    }

    public Map<String, Object> exportSbe(JsonNode params, String filePath, boolean prompt,
                                         boolean overwrite) {
        OmoOnDisk omo = ensureOmoOnDisk("sbe");
        if (omo.failure() != null) {
            return omo.failure();
        }
        SBEFormat.ExportParameters p = AssetExportBuilder.sbe(params, omo.modelName(),
                writes.sandbox()::resolveExisting);
        String err = p.getValidationError();
        if (!err.isEmpty()) {
            throw new IllegalArgumentException("invalid_params: " + err);
        }
        String bind = AssetExportBuilder.validateSbeBindings(p);
        if (bind != null) {
            throw new IllegalArgumentException("invalid_params: " + bind);
        }
        String compat = sbeClipCompatibility(p, omo.path());
        if (compat != null) {
            throw new IllegalArgumentException("clip_incompatible: " + compat);
        }
        String folder = GameResourceDirs.sbeFolderFor(p.getEntityType().getId());
        String suggested = GameResourceDirs.suggestedFileName(p.getObjectName(), "sbe", "entity.sbe");
        Path omoPath = omo.path();
        WriteOutcome out = writes.save(
                WriteRequest.of(WriteKind.SBE, filePath, prompt, overwrite, suggested)
                        .withSuggestedRoot(WriteRoot.GAME_RESOURCES, relativeToGame(folder, "sbe/Mobs"))
                        .withDetails(List.of("Entity: " + p.getObjectId() + " (" + p.getEntityType().getId() + ")",
                                "Model: " + omoPath.getFileName())),
                target -> sbeSerializer.export(p, omoPath, target.toString()));
        Map<String, Object> result = outcomeMap(out, "exported");
        result.put("objectId", p.getObjectId());
        if (out.ok()) {
            handToEditor(Kind.SBE, out.path());
        }
        return result;
    }

    // ================================================================ editor

    public Map<String, Object> editorOpen(Kind kind, String asset, int timeoutSeconds) {
        Path path = resolveAsset(kind, asset);
        Editor editor = editor(kind);
        boolean dirty = onMain(editor::dirty);
        if (dirty) {
            Map<String, Object> declined = McpApprovals.confirm(
                    mainInterface != null ? mainInterface.getApprovalGate() : null,
                    kind.name().toLowerCase(Locale.ROOT) + "_editor_open",
                    "An agent asks to open '" + path.getFileName() + "' in the " + kind + " editor",
                    List.of("File: " + path, "The editor has unsaved changes that will be discarded."),
                    timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        boolean ok = onMain(() -> editor.open(path.toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opened", ok);
        out.put("path", path.toString());
        if (!ok) {
            out.put("reason", "load_failed");
            out.put("message", "the file did not parse — see the Open Mason log");
        }
        return out;
    }

    public Map<String, Object> editorGet(Kind kind) {
        Editor editor = editor(kind);
        return onMain(() -> {
            requireLoaded(editor, kind);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", editor.path() == null ? null : editor.path().toString());
            out.put("dirty", editor.dirty());
            out.put("document", editor.describe());
            return out;
        });
    }

    public Map<String, Object> editorSet(Kind kind, JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new IllegalArgumentException("patch must be a non-empty object of manifest fields");
        }
        Editor editor = editor(kind);
        return onMain(() -> {
            requireLoaded(editor, kind);
            editor.apply(patch);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", editor.path() == null ? null : editor.path().toString());
            out.put("dirty", editor.dirty());
            out.put("document", editor.describe());
            return out;
        });
    }

    public Map<String, Object> editorSave(Kind kind, String filePath, boolean prompt, boolean overwrite) {
        Editor editor = editor(kind);
        String problem = onMain(() -> {
            requireLoaded(editor, kind);
            return editor.validate();
        });
        if (problem != null) {
            throw new IllegalArgumentException("invalid_draft: " + problem);
        }
        Path current = onMain(editor::path);
        WriteKind wk = kind == Kind.SBO ? WriteKind.SBO : WriteKind.SBE;
        String suggested = current != null ? current.getFileName().toString() : wk.fallbackName();
        AssetWriteService.Writer writer = target -> editor.write(target.toString());
        WriteOutcome out;
        String raw = filePath != null ? filePath : current != null ? current.toString() : null;
        try {
            out = writes.save(WriteRequest.of(wk, raw, prompt, overwrite, suggested)
                    .withDetails(List.of("Editor: " + kind + (current != null ? " (" + current.getFileName() + ")" : ""))),
                    writer);
        } catch (IllegalArgumentException e) {
            if (filePath == null && current != null && e.getMessage() != null
                    && e.getMessage().startsWith("path_outside_sandbox")) {
                // The user opened this file from outside the roots; let them pick a home for it.
                out = writes.save(WriteRequest.of(wk, null, true, overwrite, suggested), writer);
            } else {
                throw e;
            }
        }
        Map<String, Object> result = outcomeMap(out, "saved");
        if (out.ok()) {
            result.put("objectId", onMain(() -> editor.describe().get("objectId")));
        }
        return result;
    }

    // =============================================================== helpers

    private record OmoOnDisk(Path path, String modelName, Map<String, Object> failure) {
    }

    /** The current model's .omo, saving first (in place or via the Save Sheet) when needed. */
    private OmoOnDisk ensureOmoOnDisk(String ext) {
        ModelState st = mainInterface != null ? mainInterface.getModelState() : null;
        if (st == null) {
            throw new IllegalStateException("Model editor unavailable — is the UI running?");
        }
        boolean[] flags = onMain(() -> new boolean[]{st.isModelLoaded(), st.hasOMOFile(), st.hasUnsavedChanges()});
        if (!flags[0]) {
            throw new IllegalArgumentException("no_model: nothing is loaded in the model editor");
        }
        if (!flags[1] || flags[2]) {
            WriteOutcome omo = modelFiles.save(null, false, false,
                    List.of("Needed before the ." + ext + " export — the exporter reads the .omo from disk."),
                    true);
            if (!omo.ok()) {
                Map<String, Object> fail = outcomeMap(omo, "exported");
                fail.put("reason", "model_save_" + (omo.reason() == null ? omo.status() : omo.reason()));
                fail.put("message", "the model must be saved as .omo first: " + omo.message());
                return new OmoOnDisk(null, null, fail);
            }
        }
        String[] info = onMain(() -> new String[]{st.getCurrentOMOFilePath(), st.getCurrentModelPath()});
        return new OmoOnDisk(Path.of(info[0]), info[1], null);
    }

    private String sboClipCompatibility(SBOFormat.ExportParameters params) {
        for (SBOFormat.StateSpec spec : params.getStates()) {
            if (!spec.hasClip()) continue;
            List<String> required;
            List<String> available;
            try {
                required = AnimationCompatibility.readOMARequiredParts(Path.of(spec.clipSourcePath()));
                available = AnimationCompatibility.readOMOPartIds(Path.of(spec.sourcePath()));
            } catch (IOException e) {
                logger.warn("Could not check clip compatibility for state '{}': {}", spec.name(), e.getMessage());
                continue;
            }
            if (available.isEmpty()) continue;
            AnimationCompatibility.Result r = AnimationCompatibility.check(required, available);
            if (!r.isCompatible()) {
                return "state '" + spec.name() + "' clip references parts missing from its model: "
                        + r.describeMissing();
            }
        }
        return null;
    }

    private String sbeClipCompatibility(SBEFormat.ExportParameters params, Path baseOmo) {
        if (params.getStates().isEmpty()) return null;
        List<String> baseParts;
        try {
            baseParts = AnimationCompatibility.readOMOPartIds(baseOmo);
        } catch (IOException e) {
            return null;
        }
        if (baseParts.isEmpty()) return null;
        for (SBEFormat.StateBinding b : params.getStates()) {
            if (b.clipSource() == null) continue;
            List<String> target = baseParts;
            if (b.modelOverrideSource() != null) {
                try {
                    List<String> override = AnimationCompatibility.readOMOPartIds(b.modelOverrideSource());
                    if (!override.isEmpty()) target = override;
                } catch (IOException e) {
                    continue;
                }
            }
            List<String> required;
            try {
                required = AnimationCompatibility.readOMARequiredParts(b.clipSource());
            } catch (IOException e) {
                continue;
            }
            AnimationCompatibility.Result r = AnimationCompatibility.check(required, target);
            if (!r.isCompatible()) {
                return "state '" + b.name() + "' clip references parts missing from its model: "
                        + r.describeMissing();
            }
        }
        return null;
    }

    private static String relativeToGame(String absoluteFolder, String fallback) {
        Path root = GameResourceDirs.resourcesRoot();
        if (absoluteFolder == null || root == null) {
            return fallback;
        }
        Path p = Path.of(absoluteFolder);
        return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : fallback;
    }

    private void handToEditor(Kind kind, String path) {
        try {
            MainThreadExecutor.submit(() -> {
                Editor e = editor(kind);
                e.open(path);
                return null;
            }).get(MAIN_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Exported {} but could not open it in the editor: {}", path, e.toString());
        }
    }

    private Path resolveAsset(Kind kind, String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required (objectId such as 'stonebreak:cow', or a path)");
        }
        AssetEntry entry = AssetCatalog.shared().find(asset);
        if (entry != null) {
            AssetEntry.Kind want = kind == Kind.SBO ? AssetEntry.Kind.SBO : AssetEntry.Kind.SBE;
            if (entry.kind() != want) {
                throw new IllegalArgumentException("wrong_kind: '" + asset + "' is a ." + entry.kind().lower()
                        + " — use " + entry.kind().lower() + "_editor_open");
            }
            return entry.sourcePath();
        }
        Path p = writes.sandbox().resolveExisting(asset);
        String ext = kind == Kind.SBO ? ".sbo" : ".sbe";
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)) {
            throw new IllegalArgumentException("wrong_kind: " + asset + " is not a " + ext + " file");
        }
        return p;
    }

    private static Map<String, Object> outcomeMap(WriteOutcome out, String verb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, out.ok());
        m.put("status", out.status());
        if (out.path() != null) m.put("path", out.path());
        if (out.root() != null) m.put("root", out.root());
        if (out.overwritten() != null) m.put("overwritten", out.overwritten());
        if (out.promptedUser() != null) m.put("promptedUser", out.promptedUser());
        if (out.reason() != null) m.put("reason", out.reason());
        if (out.message() != null) m.put("message", out.message());
        return m;
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> task) {
        try {
            return MainThreadExecutor.submit(task).get(MAIN_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private static void requireLoaded(Editor editor, Kind kind) {
        if (!editor.has()) {
            throw new IllegalStateException("nothing is loaded in the " + kind + " editor — call "
                    + kind.name().toLowerCase(Locale.ROOT) + "_editor_open first");
        }
    }

    // ----------------------------------------------------------- adapters

    /** Uniform view over the two editor windows (main-thread only). */
    private interface Editor {
        boolean open(String path);
        boolean has();
        boolean dirty();
        Path path();
        Map<String, Object> describe();
        void apply(JsonNode patch);
        String validate();
        boolean write(String path);
    }

    private Editor editor(Kind kind) {
        if (mainInterface == null) {
            throw new IllegalStateException("Open Mason UI is not running — the editors are unavailable");
        }
        return kind == Kind.SBO ? new SboEditor(requireWindow(mainInterface.getSBOEditorWindow(), kind))
                : new SbeEditor(requireWindow(mainInterface.getSBEEditorWindow(), kind));
    }

    private static <W> W requireWindow(W window, Kind kind) {
        if (window == null) {
            throw new IllegalStateException(kind + " editor window is not wired yet");
        }
        return window;
    }

    private record SboEditor(SBOEditorWindow w) implements Editor {
        public boolean open(String path) { return w.openFile(path); }
        public boolean has() { return w.hasDocument(); }
        public boolean dirty() { return w.isDirty(); }
        public Path path() { return w.currentPath(); }
        public Map<String, Object> describe() { return AssetExportBuilder.describe(w.snapshotDocument()); }
        public void apply(JsonNode patch) { w.applyDocument(AssetExportBuilder.patchSbo(w.snapshotDocument(), patch)); }
        public String validate() { return w.validateForWrite(); }
        public boolean write(String path) { return w.writeDocumentTo(path); }
    }

    private record SbeEditor(SBEEditorWindow w) implements Editor {
        public boolean open(String path) { return w.openFile(path); }
        public boolean has() { return w.hasDocument(); }
        public boolean dirty() { return w.isDirty(); }
        public Path path() { return w.currentPath(); }
        public Map<String, Object> describe() { return AssetExportBuilder.describe(w.snapshotDocument()); }
        public void apply(JsonNode patch) { w.applyDocument(AssetExportBuilder.patchSbe(w.snapshotDocument(), patch)); }
        public String validate() { return w.validateForWrite(); }
        public boolean write(String path) { return w.writeDocumentTo(path); }
    }
}
