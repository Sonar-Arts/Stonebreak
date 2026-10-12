package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiPreview.graph.GraphEditorWindow;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The UI Editor workspace (#293): a full Open Mason workspace beside Modeling, with its own
 * dockspace and panels (Palette, UI Assets, Hierarchy, Designer, Details, Style Sheets, Script,
 * Diagnostics, History, Sprites, Timeline). This class wires them together, dispatches the {@code ui} shortcuts,
 * bridges the behavior graph window, owns the open/save/export flows (asking the shell for file
 * dialogs) and supplies the session state the project file records.
 */
public final class UiEditorWorkspace implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(UiEditorWorkspace.class);

    /** Native file dialogs, supplied by the shell. Paths come back as strings, null on cancel. */
    public interface FileDialogs {
        void openUiDocument(Consumer<String> chosen);

        void saveOmui(String defaultName, Consumer<String> chosen);

        void saveSbui(String defaultName, Consumer<String> chosen);

        void openSbui(Consumer<String> chosen);
    }

    private final UiEditorContext ctx;
    private final UiDocumentService service;
    private final UiWorkspaceLayout layout = new UiWorkspaceLayout();
    private final PalettePanel palette;
    private final HierarchyPanel hierarchy;
    private final DesignerPanel designer;
    private final DetailsPanel details;
    private final StyleSheetPanel styles;
    private final ScriptPanel script;
    private final DiagnosticsPanel diagnostics;
    private final HistoryPanel history;
    private final SpritesPanel sprites;
    private final TimelinePanel timeline;
    private final AssetsPanel assets;
    private final UiEditorDialogs dialogs;
    private final GraphEditorWindow graphs = new GraphEditorWindow();
    private final UiWorkspaceHeader header = new UiWorkspaceHeader(this);
    private UiEditorDocument graphDoc;
    private long graphRevision = -1;
    /** The document state the graph window's copy started from (or last synced with): the merge base. */
    private OmuiArchive graphBase;
    private FileDialogs files;
    private boolean recoveryChecked;
    private boolean keybindsRegistered;
    private Runnable onActivateRequest = () -> { };

    public UiEditorWorkspace(Supplier<Path> projectRoot) {
        UiProjectContext project = new UiProjectContext(projectRoot);
        this.service = new UiDocumentService(project, new UiRecoveryService());
        this.ctx = new UiEditorContext(service);
        this.palette = new PalettePanel(ctx);
        this.hierarchy = new HierarchyPanel(ctx);
        this.designer = new DesignerPanel(ctx, this);
        this.details = new DetailsPanel(ctx);
        this.styles = new StyleSheetPanel(ctx);
        this.script = new ScriptPanel(ctx);
        this.diagnostics = new DiagnosticsPanel(ctx);
        this.history = new HistoryPanel(ctx);
        this.sprites = new SpritesPanel(ctx);
        this.timeline = new TimelinePanel(ctx);
        this.assets = new AssetsPanel(ctx, this);
        this.dialogs = new UiEditorDialogs(ctx, this);
        service.setWorkspaceStamp(d -> WorkspaceStamp.stamp(d, ctx.view(d)));
        service.setEditorStamps(d -> {
            UiBytes timeline = ctx.view(d).timeline.stamp(d.archive());
            return timeline == null ? java.util.Map.of() : java.util.Map.of(
                com.openmason.main.systems.uiEditor.timeline.TimelineViewState.ENTRY, timeline);
        });
        ctx.setPendingEdits(new UiEditorContext.PendingEdits() {
            @Override
            public boolean has(UiEditorDocument doc) {
                return script.hasUnapplied(doc);
            }

            @Override
            public void flush(UiEditorDocument doc) {
                script.flush(doc);
            }
        });
        graphs.setJumpHandler(path -> {
            UiEditorDocument d = ctx.doc();
            if (d != null && path != null) {
                d.select(List.of(path));
                ctx.frameSelectionRequest = true;
            }
        });
    }

    public UiEditorContext context() {
        return ctx;
    }

    /** Installed by the host: opens an OMT file in the Texture Editor (#294). */
    public void setTextureEditor(com.openmason.main.systems.uiEditor.service.TextureEditBridge.Opener opener) {
        ctx.setTextureEditor(opener);
    }

    /**
     * The Texture Editor saved {@code file}: re-wrap the SBT it is the source of (if any) and
     * refresh every open document, so every shared reference to the texture updates.
     */
    public void textureSaved(Path file) {
        List<Path> changed;
        try {
            changed = ctx.textures.saved(file);
        } catch (java.io.IOException e) {
            changed = List.of(file);
            if (ctx.doc() != null) {
                ctx.doc().setLastMessage("Texture saved, but its SBT could not be re-exported: " + e.getMessage());
            }
        }
        ctx.assetFilesChanged(changed);
    }

    public UiDocumentService service() {
        return service;
    }

    UiEditorDialogs dialogs() {
        return dialogs;
    }

    public void setFileDialogs(FileDialogs files) {
        this.files = files;
    }

    /** Called when an action needs the workspace in front (File > New UI Screen from Modeling). */
    public void setOnActivateRequest(Runnable r) {
        onActivateRequest = r == null ? () -> { } : r;
    }

    public void registerKeybinds(KeybindRegistry registry) {
        if (!keybindsRegistered) {
            UiKeybindActions.registerAll(registry, this);
            keybindsRegistered = true;
        }
    }

    // ── per frame ───────────────────────────────────────────────────────────

    /** Submits the workspace dockspace's default layout when it has none (inside the host window). */
    public void applyLayout(int dockspaceId, float width, float height) {
        layout.applyIfNeeded(dockspaceId, width, height);
    }

    public void resetLayout() {
        layout.requestReset();
    }

    /** The workspace's toolbar (File/Window menus, Save, Export, pop-out), above its dockspace. */
    public void renderHeader() {
        header.render();
    }

    /**
     * Wires the toolbar's pop-out control: whether the workspace is in its own window, whether it
     * may leave the main window now (one tab always stays), and how to move it between windows.
     */
    public void setDockControls(java.util.function.BooleanSupplier detached,
                                java.util.function.BooleanSupplier canDetach, Runnable toggleDetached) {
        header.setDockControls(detached, canDetach, toggleDetached);
    }

    // ── session settings (the .omp uiEditor node's settings map) ────────────

    /** The canvas settings to record in the project: overlays, snapping, renderer. */
    public java.util.Map<String, String> sessionSettings() {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        m.put("showBounds", Boolean.toString(ctx.showBounds));
        m.put("showFlex", Boolean.toString(ctx.showFlex));
        m.put("showSpacing", Boolean.toString(ctx.showSpacing));
        m.put("showRulers", Boolean.toString(ctx.showRulers));
        m.put("snapEdges", Boolean.toString(ctx.snapEdges));
        m.put("snapGrid", Boolean.toString(ctx.snapGrid));
        m.put("gridStep", Integer.toString(ctx.gridStep));
        m.put("renderer", ctx.gpuPath ? "gpu" : "raster");
        return m;
    }

    /** Applies recorded settings; absent or unreadable values leave the current ones. */
    public void restoreSettings(java.util.Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return;
        }
        ctx.showBounds = bool(settings, "showBounds", ctx.showBounds);
        ctx.showFlex = bool(settings, "showFlex", ctx.showFlex);
        ctx.showSpacing = bool(settings, "showSpacing", ctx.showSpacing);
        ctx.showRulers = bool(settings, "showRulers", ctx.showRulers);
        ctx.snapEdges = bool(settings, "snapEdges", ctx.snapEdges);
        ctx.snapGrid = bool(settings, "snapGrid", ctx.snapGrid);
        String step = settings.get("gridStep");
        if (step != null) {
            try {
                ctx.gridStep = Math.max(1, Math.min(256, Integer.parseInt(step.trim())));
            } catch (NumberFormatException ignored) {
                // keep the current step
            }
        }
        String renderer = settings.get("renderer");
        if ("gpu".equals(renderer) || "raster".equals(renderer)) {
            ctx.gpuPath = "gpu".equals(renderer);
        }
    }

    private static boolean bool(java.util.Map<String, String> m, String key, boolean current) {
        String v = m.get(key);
        return "true".equals(v) || (!"false".equals(v) && current);
    }

    /**
     * Frame tick; panels draw only while the workspace is in front, but crash recovery keeps
     * snapshotting dirty documents either way.
     */
    public void render(float dt, boolean active) {
        service.tick(dt);
        if (!active) {
            designer.canvas.uninstallTap();
            timeline.releaseAll();
            return;
        }
        if (!recoveryChecked) {
            recoveryChecked = true;
            List<UiRecoveryService.Slot> orphans = new ArrayList<>();
            for (UiRecoveryService.Slot s : service.recovery().slots()) {
                if (s.file() == null || !Files.exists(s.file())) {
                    orphans.add(s);
                }
            }
            dialogs.openRecovery(orphans);
        }
        ctx.beginFrame();
        palette.render();
        assets.render();
        hierarchy.render();
        designer.render();
        details.render();
        styles.render();
        script.render();
        diagnostics.render();
        history.render();
        sprites.render();
        timeline.render();
        renderGraphs();
        dialogs.render();
        if (ctx.saveRequest) {
            ctx.saveRequest = false;
            saveActive();
        }
        shortcuts();
        if (ctx.focusWindow != null) {
            ImGui.setWindowFocus(ctx.focusWindow);
            ctx.focusWindow = null;
        }
    }

    private void shortcuts() {
        boolean uiFocused = ctx.panelFocused || designer.focused;
        if (!uiFocused || ctx.keysClaimed() || designer.canvas.isDragging() || ImGui.getIO().getWantTextInput() || designer.canvas.previewOwnsKeyboard
                || ImGui.isPopupOpen("", imgui.flag.ImGuiPopupFlags.AnyPopupId)) {
            return;
        }
        KeybindRegistry r = KeybindRegistry.getInstance();
        // Keys pressed while the designer shows a running Preview belong to the previewed document:
        // only view and file shortcuts may act on the editor there, never edits of the source.
        boolean previewing = designer.focused && ctx.runtime() != null
            && ctx.runtime().mode() == DesignerRuntime.Mode.PREVIEW;
        for (KeybindAction action : r.getActionsByContext("ui")) {
            if (previewing && !allowedInPreview(action)) {
                continue;
            }
            if (r.getKeybind(action.getId()).isPressed(false)) {
                action.execute();
                return;
            }
        }
        if (designer.focused && ctx.runtime() != null && ctx.runtime().mode() == DesignerRuntime.Mode.DESIGN) {
            int step = ImGui.getIO().getKeyShift() ? 10 : 1;
            if (ImGui.isKeyPressed(ImGuiKey.LeftArrow)) {
                ctx.actions.nudge(-step, 0);
            } else if (ImGui.isKeyPressed(ImGuiKey.RightArrow)) {
                ctx.actions.nudge(step, 0);
            } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
                ctx.actions.nudge(0, -step);
            } else if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
                ctx.actions.nudge(0, step);
            }
            if (!ImGui.isKeyDown(ImGuiKey.LeftArrow) && !ImGui.isKeyDown(ImGuiKey.RightArrow)
                    && !ImGui.isKeyDown(ImGuiKey.UpArrow) && !ImGui.isKeyDown(ImGuiKey.DownArrow)
                    && ImGui.isKeyReleased(ImGuiKey.LeftArrow) | ImGui.isKeyReleased(ImGuiKey.RightArrow)
                    | ImGui.isKeyReleased(ImGuiKey.UpArrow) | ImGui.isKeyReleased(ImGuiKey.DownArrow)) {
                ctx.actions.endInteraction();
            }
        }
    }

    /** Shortcuts that never change the source: the only ones a focused Preview lets through. */
    static boolean allowedInPreview(KeybindAction action) {
        String c = action.getCategory();
        return UiKeybindActions.VIEW.equals(c) || UiKeybindActions.FILE.equals(c);
    }

    // ── graphs ──────────────────────────────────────────────────────────────

    void openGraphs() {
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            return;
        }
        if (graphDoc != doc) {
            graphs.close();
            graphDoc = doc;
        }
        graphs.open(doc.archive(), source(), new GraphEditorWindow.Host() {
            @Override
            public Path documentPath() {
                return doc.file();
            }

            @Override
            public String save(OmuiArchive edited, Path target) {
                if (!service.documents().contains(doc)) {
                    return "The document was closed";
                }
                ctx.flushPendingEdits(doc); // typed Lua joins the document before the merge sees it
                boolean ok = doc.execute(DocumentCommands.replaceGraphs(edited, graphBase));
                doc.endInteraction();
                graphRevision = doc.revision();
                if (ok) {
                    graphBase = edited; // later saves merge against what the window has now written
                }
                return ok ? null : doc.lastMessage();
            }
        });
        graphRevision = doc.revision();
        graphBase = doc.archive();
    }

    /**
     * Applies the Graphs window's unsaved edits to their document, the window's own Save without
     * the file part, so a project save cannot leave graph work behind.
     *
     * @return null when there was nothing to apply or it applied, else the reason it did not
     */
    private String flushGraphs() {
        if (graphDoc == null || !graphs.isDirty() || !service.documents().contains(graphDoc)) {
            return null;
        }
        return graphs.applyToDocument();
    }

    private UiDocumentSource source() {
        UiDocumentInstance ui = ctx.instance();
        return ui == null ? UiDocumentSource.EMPTY : ui.context().source();
    }

    private void renderGraphs() {
        if (graphDoc != null && !service.documents().contains(graphDoc)) {
            graphs.close();
            graphDoc = null;
        }
        if (graphDoc != null && graphDoc.revision() != graphRevision) {
            graphRevision = graphDoc.revision();
            if (graphs.documentReloaded(graphDoc.archive(), source(), false)) {
                graphBase = graphDoc.archive(); // the window took the new state: it is the new base
            }
        }
        DesignerRuntime rt = graphDoc == null ? null : ctx.runtime(graphDoc);
        UiScriptRuntime scripts = rt == null ? null : rt.scripts();
        graphs.bind(scripts == null ? null : scripts.graphDebugger(), scripts == null ? 0 : scripts.time());
        graphs.render();
    }

    // ── file flows ──────────────────────────────────────────────────────────

    /** Opens a result: activates the workspace, offers recovery, reports failures. */
    void handleOpen(UiDocumentService.OpenResult r) {
        if (r.document() == null) {
            UiEditorDocument d = ctx.doc();
            if (d != null) {
                d.setLastMessage(r.error());
            }
            logger.warn("{}", r.error());
            return;
        }
        if (r.recovery() != null) {
            designer.offerRecovery(r.document(), r.recovery());
        }
        revealDesigner();
    }

    void revealDesigner() {
        onActivateRequest.run();
        ctx.focusWindow = DesignerPanel.TITLE;
    }

    /** Brings the UI Editor workspace and its Designer to the front (automation shows its work, #324). */
    public void reveal() {
        revealDesigner();
    }

    /** Shows the author the Restore/Discard banner for a newer crash-recovery copy (automation opens, #324). */
    public void offerRecovery(UiEditorDocument doc, UiRecoveryService.Slot slot) {
        designer.offerRecovery(doc, slot);
    }

    public void openDocument() {
        if (files == null) {
            return;
        }
        files.openUiDocument(path -> {
            if (path != null) {
                handleOpen(service.open(Path.of(path)));
            }
        });
    }

    /** Opens a UI file directly (project browser, OMP restore). */
    public void openFile(Path path) {
        handleOpen(service.open(path));
    }

    public void newDocument(com.openmason.main.systems.uiEditor.service.UiDocumentTemplates template) {
        revealDesigner();
        dialogs.openNewDocument(template);
    }

    void importSbui() {
        if (files == null) {
            return;
        }
        files.openSbui(path -> {
            if (path != null) {
                handleOpen(service.importIntoProject(Path.of(path)));
                ctx.project.entries(true);
            }
        });
    }

    public void importSbuiFromMenu() {
        revealDesigner();
        importSbui();
    }

    /** Saves {@code doc}: in place, at its convention path, or through a Save dialog. */
    boolean save(UiEditorDocument doc) {
        ctx.flushPendingEdits(doc);
        String error = service.save(doc);
        if (error == null) {
            ctx.project.entries(true);
            return true;
        }
        if (files != null && (doc.file() == null)) {
            saveAs(doc);
            return false;
        }
        doc.setLastMessage(error);
        return false;
    }

    void saveAs(UiEditorDocument doc) {
        if (files == null) {
            return;
        }
        String id = doc.archive().manifest().documentId();
        files.saveOmui(id.substring(id.lastIndexOf('/') + 1) + ".omui", path -> {
            if (path != null) {
                String err = service.saveAs(doc, Path.of(path));
                if (err != null) {
                    doc.setLastMessage(err);
                }
                ctx.project.entries(true);
            }
        });
    }

    public void saveActive() {
        UiEditorDocument d = ctx.doc();
        if (d != null) {
            save(d);
        }
    }

    public void saveActiveAs() {
        UiEditorDocument d = ctx.doc();
        if (d != null) {
            script.applyPending();
            saveAs(d);
        }
    }

    /**
     * Saves every dirty document that already has a file or a convention path (project save).
     *
     * @return one line per document that was not saved (the caller shows them; empty = all saved)
     */
    public List<String> saveAllInPlace() {
        List<String> failed = new ArrayList<>();
        String graphError = flushGraphs();
        if (graphError != null) {
            failed.add("graphs of " + (graphDoc != null ? graphDoc.title() : "a document") + ": " + graphError);
        }
        for (UiEditorDocument d : service.documents()) {
            ctx.flushPendingEdits(d);
        }
        // Clean documents whose view changed (zoom, pan, frame size, selection, Timeline) keep it too:
        // the project save is "save my UI editor state", not only the edited sources.
        for (UiEditorDocument d : service.documents()) {
            if (!d.isDirty() && d.file() != null && d.origin() != UiEditorDocument.Origin.SBUI_COPY
                    && service.editorStampsChanged(d)) {
                String err = service.save(d);
                if (err != null) {
                    logger.warn("UI document {}: view state not saved: {}", d.title(), err);
                }
            }
        }
        for (UiEditorDocument d : service.dirtyDocuments()) {
            if (service.defaultTarget(d) == null) {
                failed.add(d.title() + ": no location (use Save As)");
                continue;
            }
            String err = service.save(d);
            if (err != null) {
                logger.warn("UI document {} not saved: {}", d.title(), err);
                d.setLastMessage(err);
                failed.add(d.title() + ": " + err);
            }
        }
        return failed;
    }

    public void exportActive() {
        UiEditorDocument d = ctx.doc();
        if (d != null) {
            revealDesigner();
            dialogs.openExport(d);
        }
    }

    void chooseExportTarget(UiEditorDocument doc, Consumer<String> chosen) {
        if (files != null) {
            files.saveSbui(service.defaultExportTarget(doc).getFileName().toString(), path -> {
                if (path != null) {
                    chosen.accept(path);
                }
            });
        }
    }

    public void closeActive() {
        UiEditorDocument d = ctx.doc();
        if (d != null) {
            ctx.flushPendingEdits(d); // typed Lua becomes a dirty edit, so the close prompt covers it
            dialogs.close(List.of(d), null);
        }
    }

    /** Closes every document (asking about dirty ones), then runs {@code then}; Cancel skips it. */
    public void closeAll(Runnable then) {
        for (UiEditorDocument d : service.documents()) {
            ctx.flushPendingEdits(d);
        }
        dialogs.close(service.documents(), then);
    }

    /** Drops every document without asking (the shell already handled unsaved changes). */
    public void discardAll() {
        graphs.close();
        graphDoc = null;
        service.closeAll();
    }

    public boolean hasUnsavedChanges() {
        return service.hasUnsavedChanges() || service.documents().stream().anyMatch(ctx::hasPendingEdits)
            || graphs.isDirty();
    }

    void zoomActual() {
        DocumentViewState v = ctx.view();
        if (v != null) {
            float cx = ImGui.getMainViewport().getCenterX();
            float cy = ImGui.getMainViewport().getCenterY();
            v.transform.zoomAt(1f, cx, cy);
        }
    }

    void togglePreview() {
        DesignerRuntime rt = ctx.runtime();
        if (rt != null) {
            rt.setMode(rt.mode() == DesignerRuntime.Mode.PREVIEW ? DesignerRuntime.Mode.DESIGN
                : DesignerRuntime.Mode.PREVIEW);
        }
    }

    int errorCount() {
        return diagnostics.errorCount();
    }

    // ── project session ─────────────────────────────────────────────────────

    /** Saved documents' paths (project-relative when inside it) and the active one, for the .omp. */
    public List<String> sessionDocuments() {
        List<String> out = new ArrayList<>();
        for (UiEditorDocument d : service.documents()) {
            if (d.file() != null) {
                String rel = ctx.project.relative(d.file());
                out.add(rel != null ? rel : d.file().toAbsolutePath().toString());
            }
        }
        return out;
    }

    public String sessionActive() {
        UiEditorDocument d = ctx.doc();
        if (d == null || d.file() == null) {
            return null;
        }
        String rel = ctx.project.relative(d.file());
        return rel != null ? rel : d.file().toAbsolutePath().toString();
    }

    /** Re-opens recorded documents; missing files are skipped with a warning (nothing else changes). */
    public void restoreSession(List<String> documents, String active) {
        Path root = ctx.project.root();
        UiEditorDocument activeDoc = null;
        for (String p : documents) {
            Path file = root != null && !Path.of(p).isAbsolute() ? root.resolve(p) : Path.of(p);
            if (!Files.isRegularFile(file)) {
                logger.warn("Project lists a UI document that no longer exists: {}", p);
                continue;
            }
            UiDocumentService.OpenResult r = service.open(file);
            if (r.document() != null && r.recovery() != null) {
                designer.offerRecovery(r.document(), r.recovery());
            }
            if (r.document() != null && p.equals(active)) {
                activeDoc = r.document();
            }
        }
        if (activeDoc != null) {
            service.activate(activeDoc);
        }
    }

    /**
     * Shutdown: documents that are still dirty and have nowhere to be saved keep a fresh
     * recovery slot (offered again next launch); every other slot is cleared.
     */
    @Override
    public void close() {
        for (UiEditorDocument d : service.documents()) {
            if (d.isDirty()) {
                service.recovery().write(d);
            } else {
                service.recovery().clear(d);
            }
        }
        designer.canvas.uninstallTap();
        graphs.close();
        palette.close();
        details.close();
        sprites.close();
        ctx.close();
    }
}
