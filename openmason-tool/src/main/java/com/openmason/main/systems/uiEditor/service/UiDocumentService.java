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

    /**
     * An export: where it went, what the plan said, and what the real game host would say about it
     * ({@code hostCheck}, null when the export itself failed).
     */
    public record ExportResult(Path target, List<UiDiagnostic> diagnostics, String error,
                               UiGameDeploy.HostCheck hostCheck) {
        public ExportResult(Path target, List<UiDiagnostic> diagnostics, String error) {
            this(target, diagnostics, error, null);
        }
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

    /**
     * Closes {@code doc} dropping its unsaved changes from the editor but keeping them in a crash
     * recovery slot (automation's {@code discard}: an agent must never destroy the author's work
     * for good). @return whether a recovery copy was kept
     */
    public boolean closeKeepingRecovery(UiEditorDocument doc) {
        if (!documents.contains(doc)) {
            return false;
        }
        boolean kept = doc.isDirty() && recovery.release(doc);
        documents.remove(doc);
        recoveryWritten.remove(doc);
        if (active == doc) {
            active = documents.isEmpty() ? null : documents.getLast();
        }
        fire();
        return kept;
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
        Path abs = UiRecoveryService.normalize(file);
        for (UiEditorDocument d : documents) {
            if (abs.equals(d.file() == null ? null : UiRecoveryService.normalize(d.file()))
                    || abs.equals(d.importedFrom() == null ? null : UiRecoveryService.normalize(d.importedFrom()))) {
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
            UiEditorDocument doc = new UiEditorDocument(r.archive(), abs, UiEditorDocument.Origin.FILE, null);
            doc.setDiskStamp(diskStamp(abs));
            adopt(doc);
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
            if (ok) {
                recovery.clear(slot); // a refused restore keeps the slot: it is still the only copy
            }
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
            if (file != null) {
                doc.setDiskStamp(diskStamp(file));
            }
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
        return importIntoProject(sbuiFile, null);
    }

    /**
     * Where {@link #importIntoProject} would save {@code sbuiFile}'s document: a fresh path at its
     * convention location (never an existing file). Null without a project.
     */
    public Path importTarget(Path sbuiFile) throws IOException {
        if (project.projectSource() == null) {
            return null;
        }
        SbuiArchive sbui = SbuiReader.read(sbuiFile, SbuiReader.Options.EDITOR).archive();
        return uniqueTarget(project.conventionPath(sbui.source().manifest().documentId()));
    }

    /** As {@link #importIntoProject(Path)}, saving the document at {@code target} (null = its convention path). */
    public OpenResult importIntoProject(Path sbuiFile, Path target) {
        ProjectAssetSource source = project.projectSource();
        if (source == null) {
            return OpenResult.failed("Import needs an open project");
        }
        try {
            SbuiArchive sbui = SbuiReader.read(sbuiFile, SbuiReader.Options.EDITOR).archive();
            SbuiProjectImport.Result r = SbuiProjectImport.importIntoProject(sbui, source);
            OmuiArchive doc = r.edit().apply(source.folder());
            if (target == null) {
                target = uniqueTarget(project.conventionPath(doc.manifest().documentId()));
            }
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
        return saveAs(doc, target, false);
    }

    /**
     * True when the editor's view of {@code doc} (the workspace stamp: zoom, pan, frame, selection,
     * hidden/locked; plus the other editor stamps such as the Timeline's) differs from what its
     * archive last recorded, so a project save should write it even though the source is clean.
     */
    public boolean editorStampsChanged(UiEditorDocument doc) {
        Map<String, UiBytes> recorded = doc.archive().editor();
        UiBytes ws = workspaceStamp.apply(doc);
        if (ws != null && !ws.equals(recorded.get(OmuiFormat.EDITOR_DIR + "workspace.json"))) {
            return true;
        }
        for (Map.Entry<String, UiBytes> e : editorStamps.apply(doc).entrySet()) {
            if (!java.util.Objects.equals(e.getValue(), recorded.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code target} is {@code doc}'s own file and its bytes differ from what the editor last saw. */
    public boolean changedOnDisk(UiEditorDocument doc, Path target) {
        if (doc.file() == null || doc.diskStamp() == null || !Files.exists(target)
                || !UiRecoveryService.normalize(target).equals(UiRecoveryService.normalize(doc.file()))) {
            return false;
        }
        return !doc.diskStamp().equals(diskStamp(target));
    }

    /** Size and SHA-256 of a file's bytes, or null when unreadable. */
    static String diskStamp(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return bytes.length + ":" + java.util.HexFormat.of().formatHex(h);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static OmuiArchive withoutEditorEntry(OmuiArchive a, String entry) {
        if (!a.editor().containsKey(entry)) {
            return a;
        }
        Map<String, UiBytes> editor = new java.util.LinkedHashMap<>(a.editor());
        editor.remove(entry);
        return new OmuiArchive(a.manifest(), a.document(), a.styles(), a.graphs(), a.animations(), a.stateMachines(),
            a.scripts(), a.dependencies(), a.assets(), editor, a.extraEntries());
    }

    /** Atomic write to {@code target}; the document adopts it as its file. */
    public String saveAs(UiEditorDocument doc, Path target) {
        return saveAs(doc, target, true);
    }

    /**
     * As {@link #saveAs(UiEditorDocument, Path)}. Without {@code overwriteExternal}, saving over
     * the document's own file refuses when the file changed on disk since the editor read or
     * wrote it (a git pull, another editor): the save would silently bury that version.
     */
    public String saveAs(UiEditorDocument doc, Path target, boolean overwriteExternal) {
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(OmuiFormat.FILE_EXTENSION)) {
            target = target.resolveSibling(target.getFileName() + OmuiFormat.FILE_EXTENSION);
        }
        if (!overwriteExternal && changedOnDisk(doc, target)) {
            return "Not saved: " + target.getFileName() + " changed on disk since it was opened (reopen it to"
                + " see that version, or Save As over it to replace it)";
        }
        OmuiArchive out = doc.archive();
        UiBytes ws = workspaceStamp.apply(doc);
        if (ws != null) {
            out = out.withEditorEntry(OmuiFormat.EDITOR_DIR + "workspace.json", ws);
        }
        for (Map.Entry<String, UiBytes> e : editorStamps.apply(doc).entrySet()) {
            out = e.getValue() != null ? out.withEditorEntry(e.getKey(), e.getValue()) : withoutEditorEntry(out, e.getKey());
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
        doc.setDiskStamp(diskStamp(target));
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
            return new ExportResult(target, r.diagnostics(), null, hostCheck(r.sbui()));
        } catch (UiFormatException e) {
            return new ExportResult(target, e.diagnostics(), "Export blocked: "
                + (e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message()));
        } catch (IOException | RuntimeException e) {
            return new ExportResult(target, List.of(), "Export failed: " + e.getMessage());
        }
    }

    /**
     * What the game's host would say about {@code sbui} (C15): its declared contracts, data roots,
     * actions and providers, with shared rows resolving through the game and then this project.
     */
    public UiGameDeploy.HostCheck hostCheck(SbuiArchive sbui) {
        try {
            return UiGameDeploy.check(sbui, project.sources());
        } catch (RuntimeException e) {
            logger.warn("Game host check failed", e);
            com.openmason.engine.format.omui.UiDiagnostics d = new com.openmason.engine.format.omui.UiDiagnostics();
            d.warning(UiDiagnostic.Code.INVALID_VALUE, "", "", "The game host check could not run: " + e.getMessage());
            return new UiGameDeploy.HostCheck(d.list(), List.of());
        }
    }

    /**
     * Plans shipping {@code doc} into the game's resources (C14); nothing is written yet.
     *
     * @param gameResources {@code stonebreak-game/src/main/resources}
     */
    public UiGameDeploy.Plan planDeploy(UiEditorDocument doc, ExportMode mode, Path gameResources)
            throws UiFormatException, IOException {
        // project first, then the game's packaged root: project copies ship, the game's own stay
        return UiGameDeploy.plan(doc.archive(), project.sources(), mode, gameResources);
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
