package com.openmason.main.systems.uiEditor.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.ops.UiInspector;
import com.openmason.main.systems.uiEditor.ops.UiOpBatch;
import com.openmason.main.systems.uiEditor.ops.UiOpException;
import com.openmason.main.systems.uiEditor.service.UiDiagnosticsService;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.view.DesignerRuntime;
import com.openmason.main.systems.uiEditor.view.DocumentViewState;
import com.openmason.main.systems.uiEditor.view.UiEditorContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The UI editor as automation sees it (#324): open documents, read-only inspection, op batches
 * through the command layer, undo/redo and file operations on targets the caller already
 * approved. Preview and capture live in {@link UiPreviewAutomation}.
 *
 * <p>Headless (no GL, no ImGui): the MCP service calls it on the UI thread, tests call it
 * directly. It never touches the editor's panels, layout or settings, only documents.
 */
public final class UiAutomation {

    private final UiEditorContext ctx;
    private final Runnable reveal;
    private final java.util.function.BiConsumer<UiEditorDocument, UiRecoveryService.Slot> offerRecovery;
    private final UiPreviewAutomation preview;

    /** @param reveal brings the UI Editor workspace to the front (a no-op headless) */
    public UiAutomation(UiEditorContext ctx, Runnable reveal) {
        this(ctx, reveal, null);
    }

    /**
     * @param offerRecovery shows the author the Restore/Discard banner for a newer crash-recovery
     *                      copy of a document automation opened (null headless)
     */
    public UiAutomation(UiEditorContext ctx, Runnable reveal,
                        java.util.function.BiConsumer<UiEditorDocument, UiRecoveryService.Slot> offerRecovery) {
        this.ctx = ctx;
        this.reveal = reveal == null ? () -> { } : reveal;
        this.offerRecovery = offerRecovery == null ? (d, s) -> { } : offerRecovery;
        this.preview = new UiPreviewAutomation(this);
    }

    public UiEditorContext context() {
        return ctx;
    }

    public UiDocumentService service() {
        return ctx.service;
    }

    public UiPreviewAutomation preview() {
        return preview;
    }

    void reveal() {
        reveal.run();
    }

    // ── documents ───────────────────────────────────────────────────────────

    /** One row per open document. */
    public List<Map<String, Object>> documents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UiEditorDocument d : ctx.service.documents()) {
            out.add(info(d));
        }
        return out;
    }

    public Map<String, Object> info(UiEditorDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", d.archive().manifest().documentId());
        m.put("title", d.title());
        m.put("kind", d.archive().manifest().kind().wire());
        m.put("file", d.file() == null ? null : display(d.file()));
        m.put("origin", d.origin().name().toLowerCase(Locale.ROOT));
        m.put("active", d == ctx.service.active());
        m.put("dirty", d.isDirty());
        m.put("undo", d.history().undoLabel());
        m.put("redo", d.history().redoLabel());
        m.put("selection", d.selection());
        Path target = ctx.service.defaultTarget(d);
        if (d.file() == null && target != null) {
            m.put("saveTarget", display(target));
        }
        if (d.lastMessage() != null) {
            m.put("message", d.lastMessage());
        }
        if (pendingRecovery(d) != null) {
            m.put("recovery", "a newer crash-recovery copy exists; the author must Restore or Discard it in"
                + " the Designer before this document can be saved");
        }
        m.values().removeIf(java.util.Objects::isNull);
        return m;
    }

    /** Refuses a save that would bury a newer crash-recovery copy the author has not decided on. */
    public void requireSavable(UiEditorDocument d) {
        if (pendingRecovery(d) != null) {
            throw new IllegalStateException("'" + d.title() + "' has a newer crash-recovery copy: saving now would"
                + " bury it. The author must Restore or Discard it in the Designer first");
        }
    }

    /** A crash-recovery slot newer than {@code d}'s file, or null. */
    private UiRecoveryService.Slot pendingRecovery(UiEditorDocument d) {
        return d.file() == null ? null : ctx.service.recovery().newerThan(d.file());
    }

    /**
     * The document {@code ref} names (document id, file name, project-relative path or title);
     * null or blank = the active one.
     */
    public UiEditorDocument document(String ref) {
        List<UiEditorDocument> docs = ctx.service.documents();
        if (ref == null || ref.isBlank()) {
            UiEditorDocument a = ctx.service.active();
            if (a == null) {
                throw new IllegalArgumentException("No UI document is open (ui_new creates one, ui_open opens a file)");
            }
            return a;
        }
        List<UiEditorDocument> hits = new ArrayList<>();
        for (UiEditorDocument d : docs) {
            if (ref.equals(d.archive().manifest().documentId()) || d.file() != null
                && (ref.equals(d.file().getFileName().toString()) || ref.equals(ctx.project.relative(d.file()))
                || ref.equals(d.file().toString()))) {
                hits.add(d);
            }
        }
        if (hits.isEmpty()) {
            for (UiEditorDocument d : docs) {
                if (ref.equals(d.title())) {
                    hits.add(d);
                }
            }
        }
        if (hits.size() == 1) {
            return hits.getFirst();
        }
        List<String> known = docs.stream().map(d -> d.archive().manifest().documentId()).toList();
        throw new IllegalArgumentException((hits.isEmpty() ? "No open UI document '" : "Ambiguous document '") + ref
            + "'. Open: " + known + " (ui_documents lists them)");
    }

    public UiEditorDocument create(String template, String documentId, String displayName) {
        UiDocumentTemplates t = template(template);
        if (documentId == null || !OmuiFormat.LOGICAL_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("id '" + documentId + "' is not a document id; ids look like"
                + " namespace:path, lowercase (stonebreak:ui/screens/main_menu)");
        }
        for (UiEditorDocument open : ctx.service.documents()) {
            if (documentId.equals(open.archive().manifest().documentId())) {
                throw new IllegalArgumentException("A document with id " + documentId + " is already open ("
                    + open.title() + "); pick another id or ui_close it");
            }
        }
        String name = displayName == null || displayName.isBlank()
            ? documentId.substring(documentId.lastIndexOf('/') + 1) : displayName.trim();
        UiEditorDocument d = ctx.service.create(t, documentId, name);
        reveal();
        return d;
    }

    private static UiDocumentTemplates template(String name) {
        if (name == null || name.isBlank()) {
            return UiDocumentTemplates.BLANK_SCREEN;
        }
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (UiDocumentTemplates t : UiDocumentTemplates.values()) {
            if (t.name().equals(key)) {
                return t;
            }
        }
        List<String> valid = new ArrayList<>();
        for (UiDocumentTemplates t : UiDocumentTemplates.values()) {
            valid.add(t.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("Unknown template '" + name + "'; templates: " + valid);
    }

    /** Opens an {@code .omui}, or an {@code .sbui} as an editable copy (never written back). */
    public UiEditorDocument open(Path file) {
        UiDocumentService.OpenResult r = ctx.service.open(file);
        if (r.document() == null) {
            throw new IllegalArgumentException(r.error());
        }
        reveal();
        if (r.recovery() != null) {
            offerRecovery.accept(r.document(), r.recovery());
        }
        return r.document();
    }

    /** Closes a document; a dirty one only with {@code discard}. */
    public void close(UiEditorDocument d, boolean discard) {
        if (d.isDirty() && !discard) {
            throw new IllegalStateException("'" + d.title() + "' has unsaved changes: ui_save it first, or pass"
                + " discard:true to drop them");
        }
        ctx.service.close(d);
    }

    public void activate(UiEditorDocument d) {
        ctx.service.activate(d);
        reveal();
    }

    // ── inspection ──────────────────────────────────────────────────────────

    public ObjectNode tree(UiEditorDocument d, boolean internals) {
        return UiInspector.tree(d.archive(), internals ? laidOut(d, false) : null, internals);
    }

    public ObjectNode element(UiEditorDocument d, String key, boolean computed) {
        return UiInspector.element(d.archive(), laidOut(d, false), key, computed);
    }

    public ObjectNode styleSheets(UiEditorDocument d, String key) {
        return UiInspector.styleSheets(d.archive(), key == null ? null : laidOut(d, true), key);
    }

    /** Every problem of the document, as the Diagnostics panel lists them. */
    public List<Map<String, Object>> diagnostics(UiEditorDocument d) {
        UiDocumentInstance ui = laidOut(d, false);
        List<Map<String, Object>> out = new ArrayList<>();
        DesignerRuntime rt = ctx.runtime(d);
        if (rt != null && rt.error() != null) {
            out.add(row("error", "runtime", null, "The document cannot run: " + rt.error(), null));
        }
        for (UiDiagnosticsService.Finding f : new UiDiagnosticsService(ctx.project).findings(d.archive(), ui)) {
            out.add(row(f.severity().name().toLowerCase(Locale.ROOT), f.source().name().toLowerCase(Locale.ROOT),
                f.element(), f.message(), f.line() > 0 ? f.chunk() + ":" + f.line() : f.detail()));
        }
        if (rt != null) {
            rt.activationFindings().forEach(a -> out.add(row(a.severity().name().toLowerCase(Locale.ROOT), "preview",
                null, a.message(), a.entry())));
        }
        return out;
    }

    private static Map<String, Object> row(String severity, String source, String element, String message,
                                           String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("source", source);
        if (element != null) {
            m.put("element", element);
        }
        m.put("message", message);
        if (detail != null && !detail.isBlank()) {
            m.put("detail", detail);
        }
        return m;
    }

    /**
     * The document's runtime instance laid out at its frame size, or null when it cannot run
     * (no font, or a build error that {@link #diagnostics} reports).
     *
     * @param require throw instead of returning null
     */
    UiDocumentInstance laidOut(UiEditorDocument d, boolean require) {
        DesignerRuntime rt = ctx.runtime(d);
        if (rt != null) {
            rt.sync();
        }
        UiDocumentInstance ui = rt == null ? null : rt.instance();
        if (ui == null) {
            if (require) {
                throw new IllegalStateException("The document has no runtime to inspect"
                    + (rt != null && rt.error() != null ? ": " + rt.error() : " (the game font failed to load)"));
            }
            return null;
        }
        DocumentViewState v = ctx.view(d);
        ui.setMetrics(new UiMetrics(v.frameWidth, v.frameHeight, v.uiScale, v.pixelRatio));
        ui.update();
        return ui;
    }

    // ── edits ───────────────────────────────────────────────────────────────

    /**
     * Runs a validated batch as ONE undo step of {@code d}. On failure nothing changed and the
     * exception names the failing op.
     */
    public Map<String, Object> apply(UiEditorDocument d, UiOpBatch batch) {
        d.endInteraction(); // never merge into a step the author is still dragging or typing
        long before = d.revision();
        boolean ok = d.execute(batch.command(components()));
        d.endInteraction();
        if (!ok) {
            UiOpBatch.Failure f = batch.failure();
            if (f != null) {
                throw new UiOpException(f.opIndex(), "op " + f.opIndex() + " (" + f.op() + ") failed: " + f.message()
                    + ". The document is unchanged.", null);
            }
            throw new UiOpException(-1, d.lastMessage() + ". The document is unchanged.", null);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("ops", batch.size());
        m.put("changed", d.revision() != before);
        if (d.revision() != before) {
            m.put("step", d.history().undoLabel());
        }
        if (!batch.aliases().isEmpty()) {
            m.put("bound", new java.util.TreeMap<>(batch.aliases()));
        }
        m.put("selection", d.selection());
        m.put("dirty", d.isDirty());
        return m;
    }

    /** Project components: dependency rows for new instances, sources for checking internal keys. */
    private UiOpBatch.Components components() {
        return new UiOpBatch.Components() {
            @Override
            public List<com.openmason.engine.format.omui.UiDependency> rows(String id) throws UiCommandException {
                try {
                    return ctx.project.componentRows(id);
                } catch (IOException e) {
                    throw new UiCommandException("Cannot use component " + id + ": " + e.getMessage(), e);
                }
            }

            @Override
            public com.openmason.engine.format.omui.OmuiArchive archive(String id) {
                return ctx.project.componentArchive(id);
            }
        };
    }

    public Map<String, Object> undo(UiEditorDocument d, boolean redo) {
        String label = redo ? d.history().redoLabel() : d.history().undoLabel();
        if (label == null) {
            throw new IllegalStateException("Nothing to " + (redo ? "redo" : "undo") + " in '" + d.title() + "'");
        }
        boolean ok = redo ? d.redo() : d.undo();
        if (!ok) {
            throw new IllegalStateException(d.lastMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(redo ? "redone" : "undone", label);
        m.put("dirty", d.isDirty());
        m.put("undo", d.history().undoLabel());
        m.put("redo", d.history().redoLabel());
        m.values().removeIf(java.util.Objects::isNull);
        return m;
    }

    // ── files (targets already approved by the caller's sandbox) ─────────────

    /** Where {@code d} saves without a path: its file, else its convention path, else null. */
    public Path defaultSaveTarget(UiEditorDocument d) {
        return ctx.service.defaultTarget(d);
    }

    /** Writes {@code d} to {@code target} (it becomes the document's file); throws with the reason. */
    public void saveTo(UiEditorDocument d, Path target) {
        requireSavable(d);
        String err = ctx.service.saveAs(d, target);
        if (err != null) {
            throw new IllegalStateException(err);
        }
        ctx.project.entries(true); // a saved component is placeable at once
    }

    public Path defaultExportTarget(UiEditorDocument d) {
        return ctx.service.defaultExportTarget(d).toAbsolutePath();
    }

    public UiDocumentService.ExportResult export(UiEditorDocument d, Path target, ExportMode mode) {
        UiDocumentService.ExportResult r = ctx.service.export(d, target, mode);
        if (r.error() != null) {
            StringBuilder sb = new StringBuilder(r.error());
            r.diagnostics().stream().filter(x -> x.isError()).limit(8)
                .forEach(x -> sb.append("\n- ").append(x.message()));
            throw new IllegalStateException(sb.toString());
        }
        return r;
    }

    /** Where an import of {@code sbui} would save its document (a new file), or null without a project. */
    public Path importTarget(Path sbui) throws IOException {
        return ctx.service.importTarget(sbui);
    }

    /** Imports into the project, saving the document at the (approved) {@code target}. */
    public UiEditorDocument importSbui(Path sbui, Path target) {
        UiDocumentService.OpenResult r = ctx.service.importIntoProject(sbui, target);
        ctx.project.entries(true);
        if (r.document() == null) {
            throw new IllegalArgumentException(r.error());
        }
        reveal();
        return r.document();
    }

    String display(Path p) {
        String rel = ctx.project.relative(p);
        return rel != null ? rel : p.toAbsolutePath().toString();
    }
}
