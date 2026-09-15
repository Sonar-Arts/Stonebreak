package com.openmason.main.systems.menus.scriptingWindow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.library.ScriptLibraryStore;
import com.openmason.main.systems.scripting.mcp.ScriptingService;
import com.openmason.main.systems.scripting.python.PythonScriptEngine;
import imgui.ImGui;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiViewportFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone Scripting window: write, save and run {@code om} Python scripts
 * or JSON op batches against the live model — the same engine and
 * single-undo-entry semantics as the {@code run_python_script} /
 * {@code run_model_ops} MCP tools, now with a human front-end and a shared
 * script library ({@code ~/.openmason/scripts/} + bundled examples).
 *
 * <p>Window mechanics copy {@link com.openmason.main.systems.menus.windows.TextureEditorWindow}:
 * WM-decorated pop-out (viewport flag overrides), centered first open, no
 * nested ImGui docking — a fixed library | editor / output layout with
 * draggable splitters is more predictable for this window.
 */
public class ScriptingWindow {

    private static final Logger logger = LoggerFactory.getLogger(ScriptingWindow.class);

    public static final String WINDOW_TITLE = "Scripting";
    private static final int EDITOR_CAPACITY = 256 * 1024;

    private final MainImGuiInterface mainInterface;
    private final ScriptLibraryStore store;
    private final ImBoolean visible = new ImBoolean(false);
    private final ImGuiWindowClass windowClass = new ImGuiWindowClass();
    private final ExecutorService runExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpenMason-Scripting-Run");
        t.setDaemon(true);
        return t;
    });

    private ScriptingService scripting; // lazy — needs mainInterface at run time
    private boolean iniFileSet = false;
    private boolean wasVisible = false;

    // Editor state
    private final ImString editor = new ImString(EDITOR_CAPACITY);
    private final ImString scriptName = new ImString(128);
    private String loadedScript = null;   // library name, or null = scratch buffer
    private boolean dirty = false;
    private String lastEditorText = "";
    private final ImInt timeoutSeconds = new ImInt(30);
    private Boolean pythonAvailable = null; // resolved on first render

    // Run state (worker thread writes, render thread reads)
    private volatile boolean running = false;
    private volatile RunOutcome outcome;

    // Pending modal actions
    private Runnable pendingAfterUnsaved;
    private String pendingDeleteScript;

    private float libraryWidth = 230f;
    private float outputHeight = 180f;

    private record RunOutcome(boolean ok, String headline, String body, boolean undoNote) {
    }

    public ScriptingWindow(MainImGuiInterface mainInterface) {
        this(mainInterface, new ScriptLibraryStore());
    }

    public ScriptingWindow(MainImGuiInterface mainInterface, ScriptLibraryStore store) {
        this.mainInterface = mainInterface;
        this.store = store;
        windowClass.setViewportFlagsOverrideClear(
                ImGuiViewportFlags.NoDecoration | ImGuiViewportFlags.NoTaskBarIcon);
        windowClass.setViewportFlagsOverrideSet(ImGuiViewportFlags.NoAutoMerge);
        scriptName.set("untitled.py");
    }

    // ------------------------------------------------------------- presenter

    public void show() {
        visible.set(true);
    }

    public void showWithScript(String name) {
        visible.set(true);
        requestLoad(name);
    }

    public void hide() {
        visible.set(false);
    }

    public boolean isVisible() {
        return visible.get();
    }

    public void shutdown() {
        runExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- render

    public void render() {
        if (!visible.get()) {
            wasVisible = false;
            return;
        }
        if (pythonAvailable == null) {
            pythonAvailable = PythonScriptEngine.ifAvailable() != null;
        }
        if (!wasVisible) {
            ImGui.setNextWindowFocus();
            wasVisible = true;
        }
        if (!iniFileSet) {
            ImGui.setNextWindowSize(1100, 750);
            imgui.ImGuiViewport vp = ImGui.getMainViewport();
            ImGui.setNextWindowPos(
                    vp.getPosX() + vp.getSizeX() / 2f - 550f,
                    vp.getPosY() + vp.getSizeY() / 2f - 375f);
            iniFileSet = true;
        }
        ImGui.setNextWindowSizeConstraints(760, 480, Float.MAX_VALUE, Float.MAX_VALUE);
        ImGui.setNextWindowClass(windowClass);
        int flags = ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoScrollbar
                | ImGuiWindowFlags.NoScrollWithMouse;
        if (ImGui.begin(WINDOW_TITLE + "##scriptingWindow", visible, flags)) {
            try {
                trackDirty();
                handleShortcuts();
                renderToolbar();
                ImGui.separator();
                float contentHeight = ImGui.getContentRegionAvailY();
                renderLibrary(contentHeight);
                ImGui.sameLine();
                verticalSplitter(contentHeight);
                ImGui.sameLine();
                renderEditorAndOutput(contentHeight);
                renderModals();
            } catch (RuntimeException e) {
                logger.error("Error rendering scripting window", e);
                ImGui.textColored(1f, 0.3f, 0.3f, 1f, "Render error — see log");
            }
        }
        ImGui.end();
    }

    // ---------------------------------------------------------------- pieces

    private void renderToolbar() {
        boolean json = isJson();
        if (ImGui.button("New")) {
            guardUnsaved(() -> {
                editor.set("");
                lastEditorText = "";
                loadedScript = null;
                scriptName.set("untitled.py");
                dirty = false;
                outcome = null;
            });
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(220);
        ImGui.inputText("##scriptName", scriptName);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Script file name (.py or .json) — saved to " + store.userDir());
        }
        ImGui.sameLine();
        if (ImGui.button(dirty ? "Save*" : "Save")) {
            saveCurrent();
        }
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        String langLabel = json ? "JSON ops" : "Python";
        ImGui.setNextItemWidth(110);
        if (ImGui.beginCombo("##language", langLabel)) {
            boolean pyOk = Boolean.TRUE.equals(pythonAvailable);
            if (ImGui.selectable("Python", !json, pyOk ? 0 : imgui.flag.ImGuiSelectableFlags.Disabled)) {
                renameExtension(".py");
            }
            if (!pyOk && ImGui.isItemHovered()) {
                ImGui.setTooltip("GraalPy is not available on this launch — JSON ops only");
            }
            if (ImGui.selectable("JSON ops", json)) {
                renameExtension(".json");
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        ImGui.sliderInt("##timeout", timeoutSeconds.getData(), 1, 300, "timeout %ds");
        ImGui.sameLine();
        boolean canRun = !running && (!isPython() || Boolean.TRUE.equals(pythonAvailable));
        if (!canRun) {
            ImGui.beginDisabled();
        }
        if (ImGui.button(running ? "Running..." : "Run (Ctrl+Enter)")) {
            run();
        }
        if (!canRun) {
            ImGui.endDisabled();
        }
        if (json) {
            ImGui.sameLine();
            if (ImGui.button("Validate")) {
                validate();
            }
        }
        if (isPython() && Boolean.FALSE.equals(pythonAvailable)) {
            ImGui.sameLine();
            ImGui.textColored(1f, 0.7f, 0.2f, 1f, "GraalPy unavailable — JSON only");
        }
    }

    private void renderLibrary(float height) {
        ImGui.beginChild("##scriptLibrary", libraryWidth, height, true);
        ImGui.textDisabled("Library");
        ImGui.separator();
        List<ScriptLibraryStore.ScriptEntry> entries = store.list();
        if (entries.isEmpty()) {
            ImGui.textWrapped("No scripts yet. Save one, or duplicate an example.");
        }
        boolean examplesHeader = false;
        for (ScriptLibraryStore.ScriptEntry entry : entries) {
            if (entry.example() && !examplesHeader) {
                ImGui.spacing();
                ImGui.textDisabled("Examples (read-only)");
                ImGui.separator();
                examplesHeader = true;
            }
            boolean selected = entry.name().equals(loadedScript);
            if (ImGui.selectable(entry.name(), selected)) {
                requestLoad(entry.name());
            }
            if (ImGui.beginPopupContextItem("##ctx_" + entry.name())) {
                if (!entry.example() && ImGui.menuItem("Delete")) {
                    pendingDeleteScript = entry.name();
                }
                if (ImGui.menuItem(entry.example() ? "Duplicate to My Scripts" : "Duplicate")) {
                    duplicate(entry.name());
                }
                ImGui.endPopup();
            }
        }
        ImGui.endChild();
    }

    private void verticalSplitter(float height) {
        ImGui.pushStyleColor(ImGuiCol.Button, 0.3f, 0.3f, 0.3f, 0.3f);
        ImGui.button("##vsplit", 6, height);
        ImGui.popStyleColor();
        if (ImGui.isItemActive()) {
            libraryWidth = Math.max(140, Math.min(420, libraryWidth + ImGui.getIO().getMouseDeltaX()));
        }
    }

    private void renderEditorAndOutput(float height) {
        ImGui.beginGroup();
        float editorHeight = Math.max(80, height - outputHeight - 10);
        ImGui.inputTextMultiline("##scriptEditor", editor, ImGui.getContentRegionAvailX(),
                editorHeight, ImGuiInputTextFlags.AllowTabInput);

        ImGui.pushStyleColor(ImGuiCol.Button, 0.3f, 0.3f, 0.3f, 0.3f);
        ImGui.button("##hsplit", ImGui.getContentRegionAvailX(), 6);
        ImGui.popStyleColor();
        if (ImGui.isItemActive()) {
            outputHeight = Math.max(60, Math.min(500, outputHeight - ImGui.getIO().getMouseDeltaY()));
        }

        ImGui.beginChild("##scriptOutput", ImGui.getContentRegionAvailX(),
                ImGui.getContentRegionAvailY(), true);
        RunOutcome result = outcome;
        if (running) {
            ImGui.text("Running...");
        } else if (result == null) {
            ImGui.textDisabled("Output appears here. Run executes against the live model — "
                    + "one undo entry, full rollback on failure.");
        } else {
            if (result.ok()) {
                ImGui.textColored(0.4f, 0.9f, 0.4f, 1f, result.headline());
            } else {
                ImGui.textColored(1f, 0.4f, 0.4f, 1f, result.headline());
            }
            if (result.undoNote()) {
                ImGui.textDisabled("1 undo entry created (Edit > Undo reverts the whole script)");
            }
            if (result.body() != null && !result.body().isBlank()) {
                ImGui.separator();
                ImGui.textWrapped(result.body());
                if (ImGui.button("Copy output")) {
                    ImGui.setClipboardText(result.headline() + "\n" + result.body());
                }
                ImGui.sameLine();
            } else if (ImGui.button("Copy output")) {
                ImGui.setClipboardText(result.headline());
            }
            if (ImGui.button("Clear")) {
                outcome = null;
            }
        }
        ImGui.endChild();
        ImGui.endGroup();
    }

    private void renderModals() {
        if (pendingAfterUnsaved != null && !ImGui.isPopupOpen("Unsaved script##scripting")) {
            ImGui.openPopup("Unsaved script##scripting");
        }
        if (ImGui.beginPopupModal("Unsaved script##scripting",
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            ImGui.text("The current script has unsaved changes.");
            if (ImGui.button("Save, then continue")) {
                if (saveCurrent()) {
                    Runnable action = pendingAfterUnsaved;
                    pendingAfterUnsaved = null;
                    ImGui.closeCurrentPopup();
                    action.run();
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Discard changes")) {
                Runnable action = pendingAfterUnsaved;
                pendingAfterUnsaved = null;
                dirty = false;
                ImGui.closeCurrentPopup();
                action.run();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                pendingAfterUnsaved = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        if (pendingDeleteScript != null && !ImGui.isPopupOpen("Delete script##scripting")) {
            ImGui.openPopup("Delete script##scripting");
        }
        if (ImGui.beginPopupModal("Delete script##scripting",
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            ImGui.text("Delete '" + pendingDeleteScript + "'? This cannot be undone.");
            if (ImGui.button("Delete")) {
                try {
                    store.delete(pendingDeleteScript);
                    if (pendingDeleteScript.equals(loadedScript)) {
                        loadedScript = null;
                    }
                } catch (IOException | RuntimeException e) {
                    outcome = new RunOutcome(false, "Delete failed", e.getMessage(), false);
                }
                pendingDeleteScript = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                pendingDeleteScript = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    // --------------------------------------------------------------- actions

    private void trackDirty() {
        String current = editor.get();
        if (!current.equals(lastEditorText)) {
            dirty = true;
            lastEditorText = current;
        }
    }

    private void handleShortcuts() {
        var io = ImGui.getIO();
        if (io.getKeyCtrl() && ImGui.isKeyPressed(imgui.flag.ImGuiKey.S, false)) {
            saveCurrent();
        }
        if (io.getKeyCtrl() && (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter, false)
                || ImGui.isKeyPressed(imgui.flag.ImGuiKey.KeypadEnter, false))) {
            if (!running) {
                run();
            }
        }
    }

    private void guardUnsaved(Runnable action) {
        if (dirty && !editor.get().isBlank()) {
            pendingAfterUnsaved = action;
        } else {
            action.run();
        }
    }

    private void requestLoad(String name) {
        guardUnsaved(() -> {
            try {
                String source = store.read(name);
                String safe = ScriptLibraryStore.requireScriptName(name);
                editor.set(source);
                lastEditorText = source;
                scriptName.set(safe);
                loadedScript = safe;
                dirty = false;
                outcome = null;
            } catch (IOException | RuntimeException e) {
                outcome = new RunOutcome(false, "Load failed", e.getMessage(), false);
            }
        });
    }

    private boolean saveCurrent() {
        try {
            String safe = ScriptLibraryStore.requireScriptName(scriptName.get());
            store.save(safe, editor.get(), true);
            scriptName.set(safe);
            loadedScript = safe;
            dirty = false;
            return true;
        } catch (IOException | RuntimeException e) {
            outcome = new RunOutcome(false, "Save failed", e.getMessage(), false);
            return false;
        }
    }

    private void duplicate(String name) {
        try {
            String source = store.read(name);
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String ext = name.substring(name.lastIndexOf('.'));
            String copy = base + "_copy" + ext;
            int n = 2;
            while (store.exists(copy)) {
                copy = base + "_copy" + n++ + ext;
            }
            store.save(copy, source, false);
        } catch (IOException | RuntimeException e) {
            outcome = new RunOutcome(false, "Duplicate failed", e.getMessage(), false);
        }
    }

    private void run() {
        String source = editor.get();
        if (source.isBlank()) {
            outcome = new RunOutcome(false, "Nothing to run", "The editor is empty.", false);
            return;
        }
        ScriptExecutor.Language language =
                isJson() ? ScriptExecutor.Language.JSON_OPS : ScriptExecutor.Language.PYTHON;
        long timeoutMs = timeoutSeconds.get() * 1000L;
        running = true;
        runExecutor.submit(() -> {
            try {
                ScriptExecutor.ScriptResult result =
                        requireScripting().runScript(language, source, timeoutMs, false);
                outcome = toOutcome(result);
            } catch (RuntimeException e) {
                outcome = new RunOutcome(false, "Run failed", String.valueOf(e.getMessage()), false);
            } finally {
                running = false;
            }
        });
    }

    private void validate() {
        try {
            ScriptExecutor.ScriptResult result = requireScripting().validateOps(editor.get());
            outcome = result.ok()
                    ? new RunOutcome(true, "Valid op batch", null, false)
                    : toOutcome(result);
        } catch (RuntimeException e) {
            outcome = new RunOutcome(false, "Validate failed", String.valueOf(e.getMessage()), false);
        }
    }

    private RunOutcome toOutcome(ScriptExecutor.ScriptResult result) {
        if (result.ok()) {
            StringBuilder body = new StringBuilder();
            if (result.stdout() != null && !result.stdout().isBlank()) {
                body.append(result.stdout().strip());
            }
            if (result.summary() != null) {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append("Model: ").append(result.summary());
            }
            if (result.files() != null && !result.files().isEmpty()) {
                body.append("\nFiles written: ").append(result.files());
            }
            return new RunOutcome(true, "OK", body.toString(), true);
        }
        var error = result.error();
        StringBuilder body = new StringBuilder();
        if (error != null) {
            if (error.line() != null) {
                body.append("line ").append(error.line()).append(": ");
            }
            if (error.opIndex() != null) {
                body.append("op ").append(error.opIndex()).append(": ");
            }
            body.append(error.error());
            if (error.hint() != null) {
                body.append("\nHint: ").append(error.hint());
            }
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            body.append("\n--- stdout ---\n").append(result.stdout().strip());
        }
        return new RunOutcome(false, "Script failed (rolled back)", body.toString(), false);
    }

    private ScriptingService requireScripting() {
        if (scripting == null) {
            scripting = new ScriptingService(mainInterface, new ObjectMapper());
        }
        return scripting;
    }

    private boolean isJson() {
        return scriptName.get().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private boolean isPython() {
        return !isJson();
    }

    private void renameExtension(String ext) {
        String name = scriptName.get();
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        scriptName.set(base + ext);
    }
}
