package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.script.GraphDebugger;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "UI Behavior Graph" window (#291): the visual editor for a document's behavior graphs.
 * It drives the headless {@link GraphEditor} (every edit undoable, canonical graph JSON stays the
 * source) and hosts the canvas, the side panels (Inspector, Data, Problems, Debug) and the Lua
 * view. It is opened by {@code UiDocumentPreviewPanel} on the document the preview runs, saves
 * through a {@link Host}, and shows the preview's {@link GraphDebugger} live.
 */
public final class GraphEditorWindow {

    /** What the window needs from the preview that opened it. */
    public interface Host {
        /** The file the preview loaded, or null. */
        Path documentPath();

        /** Writes the document to {@code target} and reloads the preview; returns an error message or null. */
        String save(OmuiArchive doc, Path target);
    }

    private static final String TITLE = "UI Behavior Graph";
    private static final String NEW_GRAPH = "New graph";
    private static final String CONVERT = "Convert to script";
    private static final String SAVE_AS = "Save as .omui";

    private final ImBoolean visible = new ImBoolean(false);
    private final ImBoolean showLua = new ImBoolean(false);
    private final GraphCanvasPanel canvas = new GraphCanvasPanel();
    private final GraphInspectorPanel inspector = new GraphInspectorPanel();
    private final GraphDataPanel data = new GraphDataPanel();
    private final GraphProblemsPanel problems = new GraphProblemsPanel();
    private final GraphDebugPanel debug = new GraphDebugPanel();
    private final GraphLuaView lua = new GraphLuaView();
    private final ImString newGraphId = new ImString(64);
    private final ImString moduleId = new ImString(64);
    private final ImString saveAsPath = new ImString(1024);

    private GraphEditorState st;
    private UiDocumentSource source = UiDocumentSource.EMPTY;
    private Host host;
    /** The graph the canvas shows; an undo or redo may return the editor to another one. */
    private String shownGraph;
    private float sideWidth = 340;
    private String modalMessage = "";
    private List<GraphDiagnostic> convertErrors = List.of();
    private boolean openNewGraph;
    private boolean openConvert;
    private boolean openSaveAs;
    private Consumer<String> jump = path -> { };

    public boolean isOpen() {
        return visible.get() && st != null;
    }

    /** Receives "jump to element" requests (the preview highlights the element). */
    public void setJumpHandler(Consumer<String> handler) {
        jump = handler == null ? path -> { } : handler;
        if (st != null) {
            st.jumpToElement = jump;
        }
    }

    /** Opens the editor on {@code doc}; keeps the current one when it is already editing that document. */
    public void open(OmuiArchive doc, UiDocumentSource src, Host h) {
        host = h;
        source = src == null ? UiDocumentSource.EMPTY : src;
        if (st == null) {
            String id = doc.graphs().isEmpty() ? "behaviors" : doc.graphs().keySet().iterator().next();
            st = new GraphEditorState(GraphEditor.open(doc, id, source));
            st.jumpToElement = jump;
            st.frameAll = true;
        }
        visible.set(true);
        ImGui.setWindowFocus(TITLE + "###uiGraphEditor");
    }

    public void close() {
        visible.set(false);
        st = null;
        shownGraph = null;
    }

    /** The preview's debugger and clock for this frame (null debugger = no live data). */
    public void bind(GraphDebugger debugger, double previewTime) {
        if (st != null) {
            st.debugger = debugger;
            st.time = previewTime;
        }
    }

    /**
     * The preview (re)loaded a document. Unsaved edits are kept unless {@code force}; otherwise the
     * editor restarts on the new document, keeping the camera and selection where it can.
     *
     * @return true when the editor now holds {@code doc} (it rebound, or already showed it); false
     *         when it kept unsaved edits made against an older state
     */
    public boolean documentReloaded(OmuiArchive doc, UiDocumentSource src, boolean force) {
        if (st == null) {
            return false;
        }
        source = src == null ? UiDocumentSource.EMPTY : src;
        boolean dirty = isDirty();
        if (dirty && !force) {
            st.status = "Preview reloaded; the graph has unsaved edits and was not replaced";
            return false;
        }
        if (!force && doc.equals(st.editor.document())) {
            return true;
        }
        st.rebind(GraphEditor.open(doc, st.graphId(), source), !force);
        shownGraph = st.graphId();
        return true;
    }

    /** Ctrl+click on a preview element: selects the nodes that target it. Returns whether any did. */
    public boolean selectTargeting(String elementPath, boolean report) {
        if (st == null || elementPath == null) {
            return false;
        }
        List<String[]> hits = st.editor.nodesTargeting(elementPath);
        if (hits.isEmpty()) {
            if (report) {
                st.status = "No node targets " + elementPath;
            }
            return false;
        }
        String body = hits.get(0)[0];
        st.jumpTo(body, hits.get(0)[1]);
        for (String[] h : hits) {
            if (h[0].equals(body)) {
                st.selection.add(h[1]);
            }
        }
        st.status = hits.size() + " node(s) target " + elementPath;
        return true;
    }

    public boolean isDirty() {
        return st != null && st.editor.dirty();
    }

    // ── frame ───────────────────────────────────────────────────────────────

    public void render() {
        if (!visible.get() || st == null) {
            return;
        }
        if (!st.graphId().equals(shownGraph)) {
            if (shownGraph != null) {
                showGraphChange();
            }
            shownGraph = st.graphId();
        }
        ImGui.setNextWindowSize(1180, 720, ImGuiCond.FirstUseEver);
        String title = TITLE + (isDirty() ? " *" : "") + "###uiGraphEditor";
        if (ImGui.begin(title, visible, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            boolean focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
            toolbar();
            ImGui.separator();
            main(focused);
            popups();
        }
        ImGui.end();
    }

    private void toolbar() {
        GraphEditor ed = st.editor;
        ImGui.setNextItemWidth(150);
        if (ImGui.beginCombo("Graph", st.graphId())) {
            for (String id : ed.document().graphs().keySet()) {
                if (ImGui.selectable(id, id.equals(st.graphId())) && !id.equals(st.graphId())) {
                    switchGraph(id);
                }
            }
            ImGui.separator();
            if (ImGui.selectable("New graph...")) {
                openNewGraph = true;
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(150);
        if (ImGui.beginCombo("Body", st.body.isEmpty() ? "Event Graph" : "fn " + st.body)) {
            if (ImGui.selectable("Event Graph", st.body.isEmpty())) {
                st.switchBody("");
            }
            for (var f : ed.graph().functions()) {
                if (ImGui.selectable("fn " + f.id(), f.id().equals(st.body))) {
                    st.switchBody(f.id());
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!ed.canUndo());
        if (ImGui.button("Undo")) {
            ed.undo();
        }
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(ed.canUndo() ? "Undo " + ed.undoLabel() + "  (Ctrl+Z)" : "Nothing to undo");
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!ed.canRedo());
        if (ImGui.button("Redo")) {
            ed.redo();
        }
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(ed.canRedo() ? "Redo " + ed.redoLabel() + "  (Ctrl+Y)" : "Nothing to redo");
        }
        ImGui.sameLine();
        if (ImGui.button(isDirty() ? "Save *" : "Save")) {
            save();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Write the document and hot-reload the preview");
        }
        ImGui.sameLine();
        ImGui.checkbox("Show Lua", showLua);
        ImGui.sameLine();
        if (ImGui.button("Convert to script...")) {
            openConvert = true;
        }
        ImGui.sameLine();
        if (ImGui.button("Frame all")) {
            canvas.frameAllNow(st);
        }
        ImGui.sameLine();
        if (ImGui.button("Arrange")) {
            st.editor.arrange(st.body);
            st.frameAll = true;
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Lay this body out by event and exec depth (undoable)");
        }
    }

    private void main(boolean focused) {
        float availW = ImGui.getContentRegionAvailX();
        float availH = ImGui.getContentRegionAvailY() - ImGui.getTextLineHeightWithSpacing() - 4;
        sideWidth = Math.clamp(sideWidth, 240f, Math.max(240f, availW - 260));
        float mainW = availW - sideWidth - 8;
        if (ImGui.beginChild("##graphMain", mainW, availH, false, ImGuiWindowFlags.NoScrollbar
            | ImGuiWindowFlags.NoScrollWithMouse)) {
            float canvasH = showLua.get() ? Math.max(120, ImGui.getContentRegionAvailY() * 0.62f) : 0;
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
            boolean child = ImGui.beginChild("##graphCanvasArea", 0, canvasH, false, ImGuiWindowFlags.NoScrollbar
                | ImGuiWindowFlags.NoScrollWithMouse);
            ImGui.popStyleVar();
            if (child) {
                canvas.draw(st, focused);
            }
            ImGui.endChild();
            if (showLua.get()) {
                lua.draw(st, 0);
            }
        }
        ImGui.endChild();
        ImGui.sameLine();
        ImGui.invisibleButton("##graphSplitter", 6, availH);
        if (ImGui.isItemActive()) {
            sideWidth -= ImGui.getIO().getMouseDeltaX();
        }
        if (ImGui.isItemHovered() || ImGui.isItemActive()) {
            ImGui.setMouseCursor(imgui.flag.ImGuiMouseCursor.ResizeEW);
        }
        ImGui.sameLine();
        if (ImGui.beginChild("##graphSide", sideWidth, availH, true)) {
            side();
        }
        ImGui.endChild();
        status();
    }

    private void side() {
        int problemCount = st.editor.diagnostics().size();
        if (!ImGui.beginTabBar("##graphSideTabs")) {
            return;
        }
        if (ImGui.beginTabItem("Inspector")) {
            inspector.draw(st);
            ImGui.endTabItem();
        }
        if (ImGui.beginTabItem("Data")) {
            data.draw(st);
            ImGui.endTabItem();
        }
        if (ImGui.beginTabItem("Problems (" + problemCount + ")###problems")) {
            problems.draw(st);
            ImGui.endTabItem();
        }
        if (ImGui.beginTabItem("Debug")) {
            debug.draw(st);
            ImGui.endTabItem();
        }
        ImGui.endTabBar();
    }

    private void status() {
        if (!st.status.isEmpty()) {
            ThemedWidgets.statusText(Tone.WARNING, st.status);
        } else {
            ImGui.textDisabled(st.selection.size() + " selected | zoom " + Math.round(st.view().zoom() * 100)
                + "% | Right-click: add node | Space/middle-drag: pan | Alt+click pin: unlink | F9: breakpoint");
        }
    }

    // ── actions ─────────────────────────────────────────────────────────────

    /** Same editor, same history: undo can step back across graphs. */
    private void switchGraph(String id) {
        st.editor.switchGraph(id);
        shownGraph = id;
        showGraphChange();
        st.status = "";
    }

    private void showGraphChange() {
        st.body = "";
        st.selection.clear();
        st.rebind(st.editor, false);
        st.frameAll = true;
    }

    private void createGraph() {
        String id = newGraphId.get().strip();
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            modalMessage = "'" + id + "' is not a part id (lowercase letters, digits, _ - and / only)";
            return;
        }
        if (st.editor.document().graphs().containsKey(id)) {
            modalMessage = "A graph named " + id + " exists";
            return;
        }
        switchGraph(id); // creating it is an undoable step
        newGraphId.set("");
        modalMessage = "";
        ImGui.closeCurrentPopup();
    }

    private void save() {
        Path target = host == null ? null : host.documentPath();
        if (host == null) {
            st.status = "No preview document to save to";
            return;
        }
        if (target == null || !target.getFileName().toString().endsWith(".omui")) {
            String base = target == null ? "document.omui" : target.getFileName().toString().replaceAll("\\.[^.]*$", "")
                + ".omui";
            saveAsPath.set(target == null ? base : target.resolveSibling(base).toString());
            openSaveAs = true;
            return;
        }
        write(target);
    }

    private void write(Path target) {
        String error = host.save(st.editor.document(), target);
        if (error == null) {
            st.editor.markSaved();
            st.status = "";
        } else {
            st.status = error;
        }
    }

    // ── modal popups ────────────────────────────────────────────────────────

    private void popups() {
        if (openNewGraph) {
            ImGui.openPopup(NEW_GRAPH);
            openNewGraph = false;
        }
        if (openConvert) {
            convertErrors = List.of();
            modalMessage = "";
            ImGui.openPopup(CONVERT);
            openConvert = false;
        }
        if (openSaveAs) {
            ImGui.openPopup(SAVE_AS);
            openSaveAs = false;
        }
        if (ImGui.beginPopupModal(NEW_GRAPH, null, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Graph id (lowercase part id, e.g. behaviors or menu/intro):");
            ImGui.setNextItemWidth(320);
            ImGui.inputText("##newGraph", newGraphId);
            if (!modalMessage.isEmpty()) {
                ThemedWidgets.statusTextWrapped(Tone.ERROR, modalMessage);
            }
            if (ImGui.button("Create")) {
                createGraph();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                modalMessage = "";
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        if (ImGui.beginPopupModal(CONVERT, null, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.textWrapped("Copies the generated Lua into a new editable module scripts/<id>.lua. The graph stays; the copy is yours from now on.");
            ImGui.setNextItemWidth(320);
            ImGui.inputText("Module id", moduleId);
            if (!modalMessage.isEmpty()) {
                ThemedWidgets.statusTextWrapped(Tone.ERROR, modalMessage);
            }
            for (GraphDiagnostic d : convertErrors) {
                ThemedWidgets.statusTextWrapped(Tone.ERROR, d.toString());
            }
            if (ImGui.button("Convert")) {
                convert();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        if (ImGui.beginPopupModal(SAVE_AS, null, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.textWrapped("This document is not an .omui source file. Graph edits are saved as an .omui file.");
            ImGui.setNextItemWidth(480);
            ImGui.inputText("Path", saveAsPath);
            boolean ok = saveAsPath.get().strip().endsWith(".omui");
            if (!ok) {
                ThemedWidgets.statusText(Tone.ERROR, "The path must end in .omui");
            }
            ImGui.beginDisabled(!ok);
            if (ImGui.button("Save")) {
                write(Path.of(saveAsPath.get().strip()));
                ImGui.closeCurrentPopup();
            }
            ImGui.endDisabled();
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void convert() {
        String id = moduleId.get().strip();
        try {
            List<GraphDiagnostic> errors = new ArrayList<>(st.editor.convertToScript(id));
            if (errors.isEmpty()) {
                st.status = "Converted to scripts/" + id + ".lua (save to keep it)";
                moduleId.set("");
                ImGui.closeCurrentPopup();
            } else {
                convertErrors = errors;
                modalMessage = "The graph does not compile; nothing was converted:";
            }
        } catch (IllegalArgumentException e) {
            convertErrors = List.of();
            modalMessage = e.getMessage();
        }
    }
}
