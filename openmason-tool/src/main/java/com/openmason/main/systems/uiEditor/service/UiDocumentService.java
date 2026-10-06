package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.edit.SbuiProjectImport;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.assets.export.UiExportService;
import com.openmason.main.systems.uiEditor.command.UiHistory;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Open UI documents and their files: create, open ({@code .omui}, or an editable copy of an
 * {@code .sbui}), import into the project, save (atomic), export, close and crash recovery.
 * The view layer asks the questions (dirty prompts, file dialogs); this layer never shows UI.
 */
public final class UiDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(UiDocumentService.class);

    /** What an open produced: the document, and a newer recovery slot to offer when one exists. */
    public record OpenResult(UiEditorDocument document, UiRecoveryService.Slot recovery, String error) {
        static OpenResult failed(String error) {
            return new OpenResult(null, null, error);
        }
    }

    /** An export: where it went and what the plan said. */
    public record ExportResult(Path target, List<UiDiagnostic> diagnostics, String error) {
    }

    private final UiProjectContext project;
    private final UiRecoveryService recovery;
    private final List<UiEditorDocument> documents = new ArrayList<>();
    private final Map<UiEditorDocument, Long> recoveryWritten = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private UiEditorDocument active;
    private Function<UiEditorDocument, UiBytes> workspaceStamp = d -> null;
    /** Further editor-only entries recorded at save (the Timeline's view, #295); entry → bytes, null bytes = remove. */
    private Function<UiEditorDocument, Map<String, UiBytes>> editorStamps = d -> Map.of();

    public UiDocumentService(UiProjectContext project, UiRecoveryService recovery) {
        this.project = project;
        this.recovery = recovery;
    }

    public UiProjectContext project() {
        return project;
    }

    public UiRecoveryService recovery() {
        return recovery;
    }

    /** Supplies {@code editor/workspace.json} (selection, zoom, resolution) to stamp on save. */
    public void setWorkspaceStamp(Function<UiEditorDocument, UiBytes> stamp) {
        workspaceStamp = stamp == null ? d -> null : stamp;
    }

    /** Editor-only entries (under {@code editor/}) to record with every save, besides the workspace. */
    public void setEditorStamps(Function<UiEditorDocument, Map<String, UiBytes>> stamps) {
        editorStamps = stamps == null ? d -> Map.of() : stamps;
    }

    // ── open documents ──────────────────────────────────────────────────────

    public List<UiEditorDocument> documents() {
        return List.copyOf(documents);
    }

    public UiEditorDocument active() {
        return active;
    }

    public void activate(UiEditorDocument doc) {
        if (doc != active && (doc == null || documents.contains(doc))) {
            active = doc;
            fire();
        }
    }

    public boolean hasUnsavedChanges() {
        return documents.stream().anyMatch(UiEditorDocument::isDirty);
    }

    public List<UiEditorDocument> dirtyDocuments() {
        return documents.stream().filter(UiEditorDocument::isDirty).toList();
    }

    /** Adds an already built document and makes it active. */
    public UiEditorDocument adopt(UiEditorDocument doc) {
        doc.setProject(project.folder());
        documents.add(doc);
        active = doc;
        fire();
        return doc;
    }

    /** Closes {@code doc} without asking; a discarded dirty document also drops its recovery slot. */
    public void close(UiEditorDocument doc) {
        int i = documents.indexOf(doc);
        if (i < 0) {
            return;
        }
        documents.remove(i);
        recovery.clear(doc);
        recoveryWritten.remove(doc);
        if (active == doc) {
            active = documents.isEmpty() ? null : documents.get(Math.min(i, documents.size() - 1));
        }
        fire();
    }

    /** Closes everything (project change). */
    public void closeAll() {
        for (UiEditorDocument d : documents()) {
            close(d);
        }
    }

    // ── create / open ───────────────────────────────────────────────────────

    public UiEditorDocument create(UiDocumentTemplates template, String documentId, String displayName) {
        OmuiArchive a = UiHistory.withRequiredFeatures(template.create(documentId, displayName));
        return adopt(new UiEditorDocument(a, null, UiEditorDocument.Origin.NEW, null));
    }

    /** Opens {@code file}; an already open file is just activated. */
    public OpenResult open(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (UiEditorDocument d : documents) {
            if (abs.equals(d.file() == null ? null : d.file().toAbsolutePath().normalize())
                    || abs.equals(d.importedFrom() == null ? null : d.importedFrom().toAbsolutePath().normalize())) {
                activate(d);
                return new OpenResult(d, null, null);
            }
        }
        String name = abs.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".sbui")) {
                SbuiArchive sbui = SbuiReader.read(abs, SbuiReader.Options.EDITOR).archive();
                OmuiArchive copy = SbuiImporter.importEditable(sbui).document();
                return new OpenResult(adopt(new UiEditorDocument(copy, null, UiEditorDocument.Origin.SBUI_COPY, abs)),
                    null, null);
            }
            OmuiReader.Result r = OmuiReader.read(abs);
            UiEditorDocument doc = adopt(new UiEditorDocument(r.archive(), abs, UiEditorDocument.Origin.FILE, null));
            for (UiDiagnostic d : r.diagnostics()) {
                if (d.severity() != UiDiagnostic.Severity.INFO) {
                    doc.setLastMessage("Opened with problems: " + d.message());
                    break;
                }
            }
            return new OpenResult(doc, recovery.newerThan(abs), null);
        } catch (IOException | RuntimeException e) {
            logger.warn("Cannot open UI document {}", abs, e);
            return OpenResult.failed("Cannot open " + abs.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Replaces {@code doc} with the recovered state of {@code slot} as one undoable step, so the
     * author can still undo back to what was on disk.
     */
    public boolean restore(UiEditorDocument doc, UiRecoveryService.Slot slot) {
        try {
            OmuiArchive recovered = recovery.read(slot);
            boolean ok = doc.execute(com.openmason.main.systems.uiEditor.command.UiCommand.of("Restore recovered changes",
                ctx -> ctx.setDoc(recovered)));
            recovery.clear(slot);
            return ok;
        } catch (IOException e) {
            doc.setLastMessage("Cannot restore: " + e.getMessage());
            return false;
        }
    }

    /** Opens an untitled recovery slot (or one whose file is gone) as a recovered document. */
    public OpenResult openRecovered(UiRecoveryService.Slot slot) {
        try {
            OmuiArchive a = recovery.read(slot);
            Path file = slot.file() != null && Files.exists(slot.file()) ? slot.file() : null;
            UiEditorDocument doc = new UiEditorDocument(a, file, UiEditorDocument.Origin.RECOVERED, null);
            recovery.clear(slot);
            return new OpenResult(adopt(doc), null, null);
        } catch (IOException e) {
            return OpenResult.failed("Cannot read recovery: " + e.getMessage());
        }
    }

    /**
     * Imports an {@code .sbui} into the project: collected assets become shared project assets
     * (never overwriting different ones; see {@link SbuiProjectImport}), the document is saved at
     * its convention path and opened.
     */
    public OpenResult importIntoProject(Path sbuiFile) {
        ProjectAssetSource source = project.projectSource();
        if (source == null) {
            return OpenResult.failed("Import needs an open project");
        }
        try {
            SbuiArchive sbui = SbuiReader.read(sbuiFile, SbuiReader.Options.EDITOR).archive();
            SbuiProjectImport.Result r = SbuiProjectImport.importIntoProject(sbui, source);
            OmuiArchive doc = r.edit().apply(source.folder());
            Path target = uniqueTarget(project.conventionPath(doc.manifest().documentId()));
            try {
                Files.createDirectories(target.getParent());
                OmuiWriter.save(doc, target);
            } catch (IOException | RuntimeException e) {
                r.edit().undo(source.folder()); // no orphan assets when the document itself cannot be written
                throw e;
            }
            OpenResult opened = open(target);
            if (opened.document() != null && !r.remapped().isEmpty()) {
                opened.document().setLastMessage("Imported; renamed to avoid clobbering project assets: " + r.remapped());
            }
            return opened;
        } catch (IOException | RuntimeException e) {
            logger.warn("SBUI import failed for {}", sbuiFile, e);
            return OpenResult.failed("Import failed: " + e.getMessage());
        }
    }

    // ── save / export ───────────────────────────────────────────────────────

    /** Where an untitled document saves without a dialog (its convention path), or null. */
    public Path defaultTarget(UiEditorDocument doc) {
        return doc.file() != null ? doc.file() : project.conventionPath(doc.archive().manifest().documentId());
    }

    /**
     * Saves {@code doc} to its file, or to its convention path in the project when untitled.
     *
     * @return null on success, else the reason (the document stays dirty)
     */
    public String save(UiEditorDocument doc) {
        Path target = defaultTarget(doc);
        if (target == null) {
            return "Choose where to save (no project is open)";
        }
        if (doc.file() == null && Files.exists(target)) {
            return "A document already exists at " + project.relative(target) + ": use Save As";
        }
        return saveAs(doc, target);
    }

    /** Atomic write to {@code target}; the document adopts it as its file. */
    public String saveAs(UiEditorDocument doc, Path target) {
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(OmuiFormat.FILE_EXTENSION)) {
            target = target.resolveSibling(target.getFileName() + OmuiFormat.FILE_EXTENSION);
        }
        OmuiArchive out = doc.archive();
        UiBytes ws = workspaceStamp.apply(doc);
        if (ws != null) {
            out = out.withEditorEntry(OmuiFormat.EDITOR_DIR + "workspace.json", ws);
        }
        for (Map.Entry<String, UiBytes> e : editorStamps.apply(doc).entrySet()) {
            if (e.getValue() != null) {
                out = out.withEditorEntry(e.getKey(), e.getValue());
            }
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            OmuiWriter.save(out, target);
        } catch (UiFormatException e) {
            String first = e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message();
            return "Not saved: " + first;
        } catch (IOException e) {
            return "Not saved: " + e.getMessage();
        }
        recovery.clear(doc);
        recoveryWritten.remove(doc);
        doc.savedTo(target, out);
        doc.setLastMessage("Saved " + (project.relative(target) != null ? project.relative(target) : target));
        fire();
        return null;
    }

    /** Exports {@code doc} as an SBUI (with its report beside it). */
    public ExportResult export(UiEditorDocument doc, Path target, ExportMode mode) {
        try {
            UiExportService.Result r = UiExportService.export(doc.archive(), project.sources(),
                ExportPlanner.Request.of(mode), null);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            UiExportService.save(r, target);
            return new ExportResult(target, r.diagnostics(), null);
        } catch (UiFormatException e) {
            return new ExportResult(target, e.diagnostics(), "Export blocked: "
                + (e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message()));
        } catch (IOException | RuntimeException e) {
            return new ExportResult(target, List.of(), "Export failed: " + e.getMessage());
        }
    }

    /** Default export location: {@code <project>/Exports/UI/<stem>.sbui}, else beside the document. */
    public Path defaultExportTarget(UiEditorDocument doc) {
        String id = doc.archive().manifest().documentId();
        String stem = id.substring(id.lastIndexOf('/') + 1).replace(':', '_');
        Path root = project.root();
        if (root != null) {
            return root.resolve("Exports").resolve("UI").resolve(stem + ".sbui");
        }
        return doc.file() != null ? doc.file().resolveSibling(stem + ".sbui") : Path.of(stem + ".sbui");
    }

    /** Recovery tick: call once per frame. */
    public void tick(double dt) {
        recovery.tick(dt, documents, recoveryWritten);
    }

    // ── listeners ───────────────────────────────────────────────────────────

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    private static Path uniqueTarget(Path p) {
        if (!Files.exists(p)) {
            return p;
        }
        String name = p.getFileName().toString();
        String stem = name.endsWith(".omui") ? name.substring(0, name.length() - 5) : name;
        for (int i = 2; ; i++) {
            Path c = p.resolveSibling(stem + "_" + i + ".omui");
            if (!Files.exists(c)) {
                return c;
            }
        }
    }
}
