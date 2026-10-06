package com.openmason.main.systems.uiPreview;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.access.AccessibilityTree;
import com.openmason.engine.ui.runtime.input.PreviewInput;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiApiStubs;
import com.openmason.engine.ui.script.UiScriptChecker;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.script.UiScriptConsole;
import com.openmason.engine.ui.script.UiScriptOptions;
import com.openmason.engine.ui.script.UiScriptDiagnostic;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.main.platform.ToolInputTap;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiPreview.graph.GraphEditorWindow;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiResources;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Previews a real {@code .omui}/{@code .sbui} document inside Open Mason (#287) through
 * {@link GameUiDocuments}, the host the game window uses, so styles, layout and pseudo-state
 * rules render identically in both; only the target differs (raster upload or GPU framebuffer).
 * Input (#288) goes through {@link PreviewInput} into the same router the game window uses:
 * pointer buttons and wheel over the image, and, while this window has keyboard focus, keys and
 * text from {@link ToolInputTap}. Input never comes from outside the displayed canvas, and while
 * the document uses the keyboard Open Mason's own shortcuts and ImGui navigation stand down.
 * "Reload" re-reads the file and keeps each surviving element's instance state, the live path
 * component and document edits take, and hot-swaps the Lua code-behind (#292).
 *
 * <p>Code-behind runs on the same runtime as the game ({@code GameUiDocuments.scripts}) against
 * the fixture host, so actions answer from the fixture and never reach game services. Sounds,
 * navigation and close requests are listed instead of performed. The "Scripts" section shows
 * the running modules, memory and call cost, diagnostics with {@code chunk:line}, the script
 * console, a static check of every module and a button that writes LuaLS stubs next to the file.
 *
 * <p>Enabled with {@code -Dopenmason.uidoc.preview=<file>} (or {@code =true} to start empty);
 * {@code -Dopenmason.uigraph.open=true} also opens the behavior graph editor (#291) on load.
 * The UI Editor (#293) will host this view as its canvas.
 */
public final class UiDocumentPreviewPanel implements AutoCloseable {

    public static final String PROPERTY = "openmason.uidoc.preview";
    public static final boolean ENABLED = System.getProperty(PROPERTY) != null;

    private static final Logger logger = LoggerFactory.getLogger(UiDocumentPreviewPanel.class);
    private static final int BACKDROP = 0xFF203040;

    private final ImBoolean visible = new ImBoolean(true);
    private final ImInt zoom = new ImInt(1);
    private final ImInt pathChoice = new ImInt(1);
    private final float[] uiScale = {1f};
    private final ImString file = new ImString(1024);
    private Typeface typeface;
    private MasonryPreview preview;
    private UiDocumentView view;
    private String status = "";
    private boolean failed;
    private PreviewInput input;
    private FixtureHost fixtures;
    private UiScriptRuntime scripts;
    private com.openmason.engine.ui.script.UiNativeHealth.Status nativeStatus;
    private final List<String> requests = new ArrayList<>();
    /** The behavior graph editor (#291) and the element it asked the preview to highlight. */
    private final GraphEditorWindow graphWindow = new GraphEditorWindow();
    private final GraphEditorWindow.Host graphHost = new GraphEditorWindow.Host() {
        @Override
        public Path documentPath() {
            String f = file.get().trim();
            return f.isEmpty() ? null : Path.of(f);
        }

        @Override
        public String save(OmuiArchive doc, Path target) {
            return saveGraphDocument(doc, target);
        }
    };
    private String highlightPath;
    private double highlightUntil;
    private boolean swallowRelease;
    /** Dev hooks for screenshot runs: {@code -Dopenmason.uidoc.autoclick=key@s,...} and
     *  {@code -Dopenmason.uidoc.autoscreenshot=<s>:<file.png>[:quit]} (raster path). */
    private final com.openmason.engine.ui.runtime.input.UiAutoClick autoClick =
        com.openmason.engine.ui.runtime.input.UiAutoClick.parse(System.getProperty("openmason.uidoc.autoclick"));
    private final String autoShot = System.getProperty("openmason.uidoc.autoscreenshot");
    private double autoElapsed;
    private boolean autoShotDone;
    private int[] lastSize = {0, 0};
    private List<UiScriptDiagnostic> checked = List.of();
    private boolean documentHasKeyboard;
    private boolean wasFocused;
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private boolean lastOver;
    private final List<int[]> pendingKeys = new ArrayList<>();
    private final List<Integer> pendingChars = new ArrayList<>();
    private final ToolInputTap.Listener tap = new ToolInputTap.Listener() {
        @Override
        public void key(int key, int action, int mods) {
            pendingKeys.add(new int[]{key, action, mods});
        }

        @Override
        public void character(int codePoint) {
            pendingChars.add(codePoint);
        }
    };

    public UiDocumentPreviewPanel() {
        String initial = System.getProperty(PROPERTY, "");
        if (!initial.isBlank() && !"true".equals(initial)) {
            file.set(initial);
        }
        graphWindow.setJumpHandler(this::highlightElement);
    }

    public void render() {
        if (!visible.get() || failed) {
            return;
        }
        if (preview == null && !open()) {
            return;
        }
        ImGui.setNextWindowSize(900, 640, ImGuiCond.FirstUseEver);
        int flags = documentHasKeyboard ? ImGuiWindowFlags.NoNavInputs : ImGuiWindowFlags.None;
        boolean focusedWindow = false;
        if (ImGui.begin("UI Document Preview", visible, flags)) {
            controls();
            if (view != null) {
                int z = Math.max(1, zoom.get());
                int w = (int) (Math.max(64, ImGui.getContentRegionAvailX()) / z);
                int h = (int) (Math.max(64, ImGui.getContentRegionAvailY()) / z);
                preview.draw(w, h, z, this::paint);
                route();
                drawHighlight();
                focusedWindow = ImGui.isWindowFocused() && !ImGui.getIO().getWantTextInput();
                routeKeyboard(focusedWindow);
            }
        }
        ImGui.end();
        autoElapsed += ImGui.getIO().getDeltaTime();
        maybeAutoScreenshot();
        graphWindow.bind(scripts == null ? null : scripts.graphDebugger(), scripts == null ? 0 : scripts.time());
        graphWindow.render();
        pendingKeys.clear();
        pendingChars.clear();
        documentHasKeyboard = focusedWindow && input != null && input.wantsKeyboard();
        if (documentHasKeyboard) {
            ImGui.setNextFrameWantCaptureKeyboard(true); // editor shortcuts stand down
        }
    }

    private void controls() {
        ImGui.setNextItemWidth(420);
        ImGui.inputText("File", file);
        ImGui.sameLine();
        if (ImGui.button("Load")) {
            load();
        }
        ImGui.sameLine();
        if (ImGui.button("Reload") && view != null) {
            reload();
        }
        ImGui.sameLine();
        if (ImGui.button("Graphs...") && view != null) {
            graphWindow.open(view.instance().document(), view.instance().context().source(), graphHost);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open the behavior graph editor on this document (Ctrl+click an element here to select its nodes)");
        }
        ImGui.radioButton("GPU framebuffer", pathChoice, 0);
        ImGui.sameLine();
        ImGui.radioButton("Raster upload", pathChoice, 1);
        preview.setPath(pathChoice.get() == 0 ? MasonryPreview.Path.GPU : MasonryPreview.Path.RASTER);
        ImGui.sameLine();
        ImGui.setNextItemWidth(100);
        ImGui.sliderInt("Zoom", zoom.getData(), 1, 4);
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        ImGui.sliderFloat("UI scale", uiScale, 0.5f, 3f);
        if (nativeStatus != null && !nativeStatus.ok()) {
            for (String p : nativeStatus.problems()) {
                ImGui.textColored(0xFF6060FF, p); // no fallback: say so on every frame (#292)
            }
        }
        if (!status.isEmpty()) {
            ImGui.textWrapped(status);
        }
        scriptsSection();
    }

    private void maybeAutoScreenshot() {
        if (autoShot == null || autoShotDone) {
            return;
        }
        String[] parts = autoShot.split(":");
        if (autoElapsed < Double.parseDouble(parts[0])) {
            return;
        }
        autoShotDone = true;
        try {
            Path out = Path.of(parts.length > 1 ? parts[1] : "uidoc-preview.png");
            boolean ok = preview.saveRasterPng(out, lastSize[0], lastSize[1]);
            logger.info("[autoscreenshot] preview {} {} ({}x{}); scripts: {} diagnostics: {}; status: {}",
                ok ? "wrote" : "could not write", out, lastSize[0], lastSize[1],
                scripts == null ? "none" : scripts.modules(), scripts == null ? List.of() : scripts.diagnostics(),
                status);
        } catch (Exception e) {
            logger.error("[autoscreenshot] preview failed", e);
        }
        if (parts.length > 2 && "quit".equalsIgnoreCase(parts[2])) {
            System.exit(0);
        }
    }

    // ── code-behind (#292) ──────────────────────────────────────────────────

    private void scriptsSection() {
        if (scripts == null || !ImGui.collapsingHeader("Scripts")) {
            return;
        }
        if (!scripts.isScripted()) {
            ImGui.textDisabled("No code-behind: this document runs without a Lua state.");
        } else {
            ImGui.text(String.format(Locale.ROOT, "Modules: %s | Lua heap %d KiB (peak %d) | %d calls, %.2f ms total,"
                    + " last frame %.1f us", scripts.modules(), scripts.memoryUsed() / 1024, scripts.memoryPeak() / 1024,
                scripts.calls(), scripts.callNanos() / 1e6, scripts.lastUpdateNanos() / 1e3));
        }
        if (ImGui.button("Check scripts")) {
            checked = check(view.instance().document());
        }
        ImGui.sameLine();
        if (ImGui.button("Write LuaLS stubs")) {
            writeStubs();
        }
        ImGui.sameLine();
        if (ImGui.button("Clear console")) {
            scripts.console().clear();
            requests.clear();
        }
        for (UiScriptDiagnostic d : checked) {
            diagnosticLine("check", d);
        }
        for (UiScriptDiagnostic d : scripts.diagnostics()) {
            diagnosticLine("run", d);
        }
        for (String r : requests) {
            ImGui.textDisabled("request: " + r);
        }
        if (ImGui.beginChild("##scriptConsole", 0, 140, true)) {
            for (UiScriptConsole.Entry e : scripts.console().entries()) {
                String line = String.format(Locale.ROOT, "%7.2f %-5s %s: %s", e.time(), e.level(), e.source(),
                    firstLine(e.message()));
                if (e.level() == UiScriptConsole.Level.INFO) {
                    ImGui.textUnformatted(line);
                } else {
                    ImGui.textColored(e.level() == UiScriptConsole.Level.ERROR ? 0xFF6060FF : 0xFF40C0FF, line);
                }
                if (ImGui.isItemHovered() && e.message().contains("\n")) {
                    ImGui.setTooltip(e.message());
                }
            }
            if (ImGui.getScrollY() >= ImGui.getScrollMaxY()) {
                ImGui.setScrollHereY(1f);
            }
        }
        ImGui.endChild();
    }

    private static void diagnosticLine(String kind, UiScriptDiagnostic d) {
        String text = kind + " " + d.severity() + " " + d.code() + " " + d.location() + ": " + d.headline();
        int color = switch (d.severity()) {
            case ERROR -> 0xFF6060FF;
            case WARNING -> 0xFF40C0FF;
            case INFO -> 0xFFC0C0C0;
        };
        ImGui.textColored(color, text);
        if (ImGui.isItemHovered() && d.message().contains("\n")) {
            ImGui.setTooltip(d.message());
        }
    }

    /** Static check of the document's own scripts (shared modules are checked in their own files). */
    private static List<UiScriptDiagnostic> check(OmuiArchive doc) {
        List<UiScriptDiagnostic> out = new ArrayList<>();
        for (Map.Entry<String, String> e : doc.scripts().entrySet()) {
            out.addAll(UiScriptChecker.check(e.getValue(), e.getKey() + ".lua"));
        }
        return out;
    }

    private void writeStubs() {
        try {
            Path dir = Path.of(file.get().trim()).toAbsolutePath().getParent();
            UiApiStubs.write(dir);
            status = "Wrote " + dir.resolve(UiApiStubs.STUB_FILE) + " and " + UiApiStubs.CONFIG_FILE
                + ": open the folder in a LuaLS editor for completion";
        } catch (Exception e) {
            status = "Cannot write LuaLS stubs: " + e.getMessage();
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** The preview lists what a script asks of the host instead of doing it. */
    private com.openmason.engine.ui.script.UiScriptServices previewServices() {
        return new com.openmason.engine.ui.script.UiScriptServices() {
            @Override
            public void playSound(String id, com.openmason.engine.format.omui.UiValue.Obj options) {
                request("ui.sound(" + id + ")");
            }

            @Override
            public boolean navigate(String target, com.openmason.engine.format.omui.UiValue.Obj args) {
                request("ui.navigate(" + target + ")");
                return true;
            }

            @Override
            public void requestClose() {
                request("ui.close()");
            }
        };
    }

    private void request(String what) {
        requests.add(what);
        if (requests.size() > 20) {
            requests.removeFirst();
        }
    }

    private void paint(MasonryUI ui, int[] size) {
        ui.canvas().clear(BACKDROP);
        if (fixtures != null) {
            fixtures.host().drain(); // fixture responses and posted data land once per preview frame
        }
        double dt = ImGui.getIO().getDeltaTime();
        view.frame(dt); // scripts: awaited results, watches, animations, update(dt)
        if (autoClick != null) {
            autoClick.tick(view.instance(), view.input(), dt);
        }
        view.render(ui, size[0], size[1], uiScale[0], 1f);
        lastSize = size.clone();
    }

    private void route() {
        if (input == null) {
            return;
        }
        input.setMapping(preview.mapping());
        boolean over = ImGui.isItemHovered();
        float mx = ImGui.getMousePosX();
        float my = ImGui.getMousePosY();
        int mods = mods();
        input.router().tick(ImGui.getIO().getDeltaTime());
        if (mx != lastMouseX || my != lastMouseY || over != lastOver) { // real moves only
            input.pointerMove(mx, my, over);
            lastMouseX = mx;
            lastMouseY = my;
            lastOver = over;
        }
        for (int b = 0; b < 3; b++) {
            if (ImGui.isMouseClicked(b)) {
                if (b == 0 && over && ImGui.getIO().getKeyCtrl() && graphWindow.isOpen()) {
                    pickElement(mx, my); // the click picks for the graph editor; the document never sees it
                    swallowRelease = true;
                } else {
                    input.pointerButton(mx, my, over, b, true, mods);
                }
            }
            if (ImGui.isMouseReleased(b)) {
                if (b == 0 && swallowRelease) {
                    swallowRelease = false;
                    continue;
                }
                input.pointerButton(mx, my, over, b, false, mods);
                UiElement clicked = input.router().lastClick();
                if (b == 0 && clicked != null) {
                    status = "clicked " + clicked.key();
                }
            }
        }
        float wheel = ImGui.getIO().getMouseWheel();
        float wheelH = ImGui.getIO().getMouseWheelH();
        if (wheel != 0 || wheelH != 0) {
            input.wheel(mx, my, over, wheelH, wheel, mods);
        }
        if (over) {
            float cx = preview.mapping().canvasX(mx);
            float cy = preview.mapping().canvasY(my);
            UiElement under = view.instance().hitTest(cx, cy);
            if (under != null) {
                ImGui.setTooltip(under.type() + " [" + under.key() + "] " + AccessibilityTree.role(under));
            }
        }
    }

    /** Keys and text reach the document only while this window has keyboard focus. */
    private void routeKeyboard(boolean focusedWindow) {
        if (input == null) {
            return;
        }
        if (wasFocused && !focusedWindow) {
            input.router().windowFocusLost(); // like the game window losing focus: releases won't arrive
        }
        wasFocused = focusedWindow;
        if (!focusedWindow) {
            return;
        }
        for (int[] k : pendingKeys) {
            input.router().key(k[0], k[1], k[2]);
        }
        for (int cp : pendingChars) {
            input.router().text(cp);
        }
        UiElement f = input.router().focus().focused();
        if (f != null) {
            ImGui.text("Focus: " + f.key() + " (" + AccessibilityTree.role(f) + ") "
                + AccessibilityTree.name(f, AccessibilityTree.role(f)));
        }
    }

    private static int mods() {
        int m = 0;
        if (ImGui.getIO().getKeyShift()) {
            m |= MKeys.MOD_SHIFT;
        }
        if (ImGui.getIO().getKeyCtrl()) {
            m |= MKeys.MOD_CONTROL;
        }
        if (ImGui.getIO().getKeyAlt()) {
            m |= MKeys.MOD_ALT;
        }
        if (ImGui.getIO().getKeySuper()) {
            m |= MKeys.MOD_SUPER;
        }
        return m;
    }

    private boolean load() {
        try {
            if (view != null) {
                view.close();
                view = null;
                input = null;
                scripts = null;
            }
            checked = List.of();
            requests.clear();
            Typeface tf = typeface;
            Path path = Path.of(file.get().trim());
            view = GameUiDocuments.open(path, () -> tf);
            // Bindings run against fixtures, never live game services (#289): a sidecar
            // <file>.fixture.json wins over the archive's own editor/fixtures.json.
            fixtures = fixtures(path, view.instance().document());
            List<UiDiagnostic> gate = GameUiDocuments.activationGate(view, fixtures.host());
            // Code-behind (#292) binds the view with its converters and runs against the fixtures.
            // Debug build: graphs compile with trace calls for the graph editor's highlighting (#291).
            scripts = GameUiDocuments.scripts(view, fixtures.host(), null, previewServices(),
                UiScriptOptions.DEFAULTS.withGraphDebug(true));
            input = new PreviewInput(view.input());
            status = summary("Loaded", view.instance());
            for (UiDiagnostic d : gate) {
                status += "\n" + d;
            }
            graphWindow.documentReloaded(view.instance().document(), view.instance().context().source(), true);
            if (Boolean.getBoolean("openmason.uigraph.open") && !graphWindow.isOpen()) {
                // Dev hook for live runs: open the graph editor on the loaded document.
                graphWindow.open(view.instance().document(), view.instance().context().source(), graphHost);
            }
            return true;
        } catch (Exception e) {
            // Never show a document whose code-behind could not start as if it worked.
            if (view != null) {
                view.close();
                view = null;
                input = null;
                scripts = null;
            }
            status = "Cannot load: " + e.getMessage();
            logger.warn("UI document preview: cannot load {}", file.get(), e);
            return false;
        }
    }

    private static FixtureHost fixtures(Path document, OmuiArchive doc) throws IOException {
        Path sidecar = document.resolveSibling(document.getFileName() + ".fixture.json");
        if (Files.isRegularFile(sidecar)) {
            return FixtureHost.parse(Files.readAllBytes(sidecar), sidecar.getFileName().toString(), doc.manifest());
        }
        return FixtureHost.forArchive(doc);
    }

    private boolean reload() {
        try {
            UiDocumentInstance.ReloadReport r = GameUiDocuments.reload(view, Path.of(file.get().trim()));
            status = summary("Reloaded (kept " + r.kept().size() + ", added " + r.added().size() + ", dropped "
                + r.dropped().size() + ")", view.instance());
            graphWindow.documentReloaded(view.instance().document(), view.instance().context().source(), false);
            return true;
        } catch (Exception e) {
            status = "Cannot reload: " + e.getMessage();
            return false;
        }
    }

    // ── behavior graph editor (#291) ────────────────────────────────────────

    /** The editor's Save: write the document, then hot-reload (or load, for a new path) the preview. */
    private String saveGraphDocument(OmuiArchive doc, Path target) {
        try {
            OmuiWriter.save(doc, target);
        } catch (IOException | RuntimeException e) {
            return "Cannot save " + target + ": " + e.getMessage();
        }
        boolean same = view != null && target.toAbsolutePath().normalize()
            .equals(Path.of(file.get().trim()).toAbsolutePath().normalize());
        file.set(target.toString());
        boolean ok = same ? reload() : load();
        return ok ? null : "Saved " + target + " but the preview could not load it: " + status;
    }

    /** Outlines {@code path} in the preview for a moment (the editor's "jump to element"). */
    private void highlightElement(String path) {
        highlightPath = path;
        highlightUntil = ImGui.getTime() + 1.5;
        if (view != null && view.instance().find(path) == null) {
            status = "Element " + path + " is not in the running preview";
        }
    }

    private void drawHighlight() {
        if (highlightPath == null) {
            return;
        }
        if (ImGui.getTime() > highlightUntil) {
            highlightPath = null;
            return;
        }
        UiElement el = view.instance().find(highlightPath);
        if (el == null) {
            return;
        }
        UiRect r = el.rect();
        PreviewMapping m = preview.mapping();
        float pulse = 0.55f + 0.45f * (float) Math.sin(ImGui.getTime() * 9);
        ImDrawList dl = ImGui.getWindowDrawList();
        float x1 = m.screenX(r.x());
        float y1 = m.screenY(r.y());
        float x2 = m.screenX(r.right());
        float y2 = m.screenY(r.bottom());
        dl.addRectFilled(x1, y1, x2, y2, ThemeColors.u32(ThemeColors.Tone.WARNING, 0.18f * pulse));
        dl.addRect(x1 - 2, y1 - 2, x2 + 2, y2 + 2, ThemeColors.u32(ThemeColors.Tone.WARNING, pulse), 2f, 0, 3f);
    }

    /** Ctrl+click on the preview: select the graph nodes that target the element under the pointer. */
    private void pickElement(float mx, float my) {
        float cx = preview.mapping().canvasX(mx);
        float cy = preview.mapping().canvasY(my);
        UiElement under = view.instance().hitTest(cx, cy);
        for (UiElement e = under; e != null; e = e.parent()) {
            if (graphWindow.selectTargeting(e.key(), false)) {
                return;
            }
        }
        if (under != null) {
            graphWindow.selectTargeting(under.key(), true);
        }
    }

    private static String summary(String head, UiDocumentInstance ui) {
        StringBuilder sb = new StringBuilder(head).append(": ").append(ui.elements().size()).append(" elements");
        for (UiRuntimeDiagnostic d : ui.diagnostics()) {
            sb.append("\n").append(d);
        }
        return sb.toString();
    }

    private boolean open() {
        try {
            nativeStatus = com.openmason.engine.ui.script.UiNativeHealth.check();
            typeface = GameUiResources.loadTypeface();
            preview = new MasonryPreview(typeface, MasonryPreview.Path.RASTER);
            ToolInputTap.add(tap);
            if (!file.get().isBlank()) {
                load();
            }
            return true;
        } catch (Exception e) {
            failed = true;
            logger.error("UI document preview unavailable", e);
            return false;
        }
    }

    @Override
    public void close() {
        graphWindow.close();
        ToolInputTap.remove(tap);
        if (view != null) {
            view.close();
            view = null;
            scripts = null;
        }
        if (preview != null) {
            preview.close();
            preview = null;
        }
        if (typeface != null) {
            typeface.close();
            typeface = null;
        }
    }
}
