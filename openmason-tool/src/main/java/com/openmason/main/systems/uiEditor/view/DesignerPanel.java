package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.canvas.AlignDistribute;
import com.openmason.main.systems.uiEditor.canvas.CanvasTransform;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The central designer: one tab per open document (dirty dot, close with prompt), the toolbar
 * (Design/Preview, frame size, UI scale, pixel ratio, zoom, overlays, snapping, align and
 * distribute, graphs), the recovery banner, the canvas and a status bar (pointer position in
 * logical pixels, selection size, last message, problem count).
 */
final class DesignerPanel {

    static final String TITLE = "Designer###uiDesigner";

    private final UiEditorContext ctx;
    private final UiEditorWorkspace workspace;
    final CanvasView canvas;
    private UiEditorDocument shownTab;
    private UiEditorDocument forceTab;
    private final Map<UiEditorDocument, ImBoolean> tabOpen = new HashMap<>();
    private final Map<UiEditorDocument, UiRecoveryService.Slot> recovery = new HashMap<>();
    private final ImInt customW = new ImInt(1920);
    private final ImInt customH = new ImInt(1080);
    boolean focused;

    DesignerPanel(UiEditorContext ctx, UiEditorWorkspace workspace) {
        this.ctx = ctx;
        this.workspace = workspace;
        this.canvas = new CanvasView(ctx);
    }

    /** Offers a newer recovery slot for {@code doc} in a banner above its canvas. */
    void offerRecovery(UiEditorDocument doc, UiRecoveryService.Slot slot) {
        recovery.put(doc, slot);
    }

    void render() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        boolean open = ImGui.begin(TITLE, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        focused = open && ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
        if (!open) {
            canvas.uninstallTap();
            ImGui.end();
            return;
        }
        tabs();
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            welcome();
            ImGui.end();
            return;
        }
        ImGui.setCursorPosX(ImGui.getCursorPosX() + 6);
        toolbar(doc);
        banner(doc);
        float statusH = ImGui.getTextLineHeight() + 8;
        float w = ImGui.getContentRegionAvailX();
        float h = Math.max(60, ImGui.getContentRegionAvailY() - statusH);
        DesignerRuntime rt = ctx.runtime();
        if (rt != null) {
            rt.applyDesignerVisibility(ctx.view().hidden);
        }
        canvas.draw(w, h, focused);
        status(doc, w, statusH);
        ImGui.end();
    }

    // ── tabs ────────────────────────────────────────────────────────────────

    private void tabs() {
        List<UiEditorDocument> docs = ctx.service.documents();
        if (docs.isEmpty()) {
            return;
        }
        UiEditorDocument active = ctx.service.active();
        if (active != shownTab) {
            forceTab = active;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 10, 5);
        if (ImGui.beginTabBar("##uiDocTabs", ImGuiTabBarFlags.Reorderable | ImGuiTabBarFlags.FittingPolicyScroll
                | ImGuiTabBarFlags.AutoSelectNewTabs)) {
            List<UiEditorDocument> toClose = new ArrayList<>();
            for (UiEditorDocument d : docs) {
                ImBoolean keep = tabOpen.computeIfAbsent(d, k -> new ImBoolean(true));
                keep.set(true);
                int flags = (d.isDirty() ? ImGuiTabItemFlags.UnsavedDocument : 0)
                    | (d == forceTab ? ImGuiTabItemFlags.SetSelected : 0);
                String label = d.title() + "###uidoc" + System.identityHashCode(d);
                boolean selected = ImGui.beginTabItem(label, keep, flags);
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip((d.file() != null ? String.valueOf(ctx.project.relative(d.file()) != null
                        ? ctx.project.relative(d.file()) : d.file()) : "Not saved yet")
                        + (d.origin() == UiEditorDocument.Origin.SBUI_COPY ? "\nEditable copy of " + d.importedFrom()
                        .getFileName() : "") + "\n" + d.archive().manifest().documentId());
                }
                if (ImGui.beginPopupContextItem("##tabctx" + System.identityHashCode(d))) {
                    if (ImGui.menuItem("Close")) {
                        toClose.add(d);
                    }
                    if (ImGui.menuItem("Close Others")) {
                        docs.stream().filter(o -> o != d).forEach(toClose::add);
                    }
                    if (d.file() != null && ImGui.menuItem("Copy Path")) {
                        ImGui.setClipboardText(d.file().toString());
                    }
                    ImGui.endPopup();
                }
                if (selected) {
                    if (forceTab == null && d != ctx.service.active()) {
                        ctx.service.activate(d);
                    }
                    ImGui.endTabItem();
                }
                if (!keep.get()) {
                    toClose.add(d);
                }
            }
            ImGui.endTabBar();
            if (!toClose.isEmpty()) {
                workspace.dialogs().close(toClose, null);
            }
        }
        ImGui.popStyleVar();
        forceTab = null;
        shownTab = ctx.service.active();
    }

    // ── toolbar ─────────────────────────────────────────────────────────────

    private void toolbar(UiEditorDocument doc) {
        DocumentViewState v = ctx.view();
        DesignerRuntime rt = ctx.runtime();
        float h = ImGui.getFrameHeight();
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6, 4);
        boolean preview = rt != null && rt.mode() == DesignerRuntime.Mode.PREVIEW;
        int pick = EditorWidgets.segmentedText("##mode", preview ? 1 : 0, new String[]{"Design", "Preview"}, h,
            new EditorWidgets.Painter[]{Glyphs::pencil, Glyphs::play});
        if (pick >= 0 && rt != null) {
            rt.setMode(pick == 1 ? DesignerRuntime.Mode.PREVIEW : DesignerRuntime.Mode.DESIGN);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Preview runs the code-behind and graphs against the document's fixtures, with real input."
                + "\nNothing it does changes the document.");
        }
        separator();

        // frame size
        ResolutionPreset preset = ResolutionPreset.of(v.frameWidth, v.frameHeight);
        flow(170);
        ImGui.setNextItemWidth(170);
        if (ImGui.beginCombo("##res", preset == ResolutionPreset.CUSTOM ? v.frameWidth + " x " + v.frameHeight
                : preset.label)) {
            for (ResolutionPreset p : ResolutionPreset.values()) {
                String label = p.label + (p.note.isEmpty() ? "" : "   " + p.note);
                if (ImGui.selectable(label, p == preset)) {
                    if (p != ResolutionPreset.CUSTOM) {
                        v.frameWidth = p.width;
                        v.frameHeight = p.height;
                        v.fitPending = true;
                    } else {
                        customW.set(v.frameWidth);
                        customH.set(v.frameHeight);
                    }
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Frame size in device pixels: the window size the game would lay out into");
        }
        if (preset == ResolutionPreset.CUSTOM) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(64);
            customW.set(v.frameWidth);
            if (ImGui.inputInt("##cw", customW, 0)) {
                v.frameWidth = Math.max(16, Math.min(16384, customW.get()));
            }
            ImGui.sameLine();
            ImGui.setNextItemWidth(64);
            customH.set(v.frameHeight);
            if (ImGui.inputInt("##ch", customH, 0)) {
                v.frameHeight = Math.max(16, Math.min(16384, customH.get()));
            }
        }
        ImGui.sameLine();
        if (EditorWidgets.iconButton("##swap", h, (dl, x, y, s, c) -> {
            Glyphs.arrow(dl, x + s * 0.1f, y + s * 0.3f, x + s * 0.9f, y + s * 0.3f, c, 1.4f, s * 0.8f);
            Glyphs.arrow(dl, x + s * 0.9f, y + s * 0.7f, x + s * 0.1f, y + s * 0.7f, c, 1.4f, s * 0.8f);
        }, "Swap width and height (portrait)", false, true)) {
            int t = v.frameWidth;
            v.frameWidth = v.frameHeight;
            v.frameHeight = t;
            v.fitPending = true;
        }
        flow(90);
        scaleCombo("##uiscale", "UI ", v.uiScale, ResolutionPreset.UI_SCALES, f -> v.uiScale = f,
            "UI scale: the game's UI size setting (logical px x scale)");
        flow(90);
        scaleCombo("##dpi", "DPI ", v.pixelRatio, ResolutionPreset.PIXEL_RATIOS, f -> v.pixelRatio = f,
            "Device pixel ratio: high-density displays draw more pixels per logical pixel");
        separator();

        // zoom
        CanvasTransform t = v.transform;
        flow(80);
        ImGui.setNextItemWidth(80);
        if (ImGui.beginCombo("##zoom", Math.round(t.zoom() * 100) + "%")) {
            if (ImGui.selectable("Fit to Frame")) {
                v.fitPending = true;
            }
            if (ImGui.selectable("Frame Selection")) {
                ctx.frameSelectionRequest = true;
            }
            ImGui.separator();
            for (float z : CanvasTransform.STOPS) {
                if (ImGui.selectable(Math.round(z * 100) + "%", Math.abs(z - t.zoom()) < 1e-3)) {
                    zoomCentered(t, z);
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Editor zoom (view only; the authored UI scale is separate). Wheel zooms at the pointer.");
        }
        separator();

        // overlays
        flow(h * 4 + 18);
        if (EditorWidgets.iconButton("##ovBounds", h, (dl, x, y, s, c) -> Glyphs.dashedRect(dl, x, y, x + s, y + s, c, 1f),
            "Show element bounds", ctx.showBounds, true)) {
            ctx.showBounds = !ctx.showBounds;
        }
        ImGui.sameLine();
        if (EditorWidgets.iconButton("##ovSpacing", h, (dl, x, y, s, c) -> {
            dl.addRect(x, y, x + s, y + s, c, 1f, 0, 1f);
            dl.addRect(x + s * 0.25f, y + s * 0.25f, x + s * 0.75f, y + s * 0.75f, c, 1f, 0, 1f);
        }, "Show margin and padding of the selection", ctx.showSpacing, true)) {
            ctx.showSpacing = !ctx.showSpacing;
        }
        ImGui.sameLine();
        if (EditorWidgets.iconButton("##ovFlex", h, (dl, x, y, s, c) -> Glyphs.direction(dl, "row", x, y, s, c),
            "Show flex flow and gaps of the selected container", ctx.showFlex, true)) {
            ctx.showFlex = !ctx.showFlex;
        }
        ImGui.sameLine();
        if (EditorWidgets.iconButton("##ovRulers", h, (dl, x, y, s, c) -> {
            dl.addLine(x, y + s * 0.2f, x + s, y + s * 0.2f, c, 1.5f);
            dl.addLine(x + s * 0.2f, y, x + s * 0.2f, y + s, c, 1.5f);
            for (int i = 1; i < 4; i++) {
                dl.addLine(x + s * 0.2f + i * s * 0.2f, y + s * 0.2f, x + s * 0.2f + i * s * 0.2f, y + s * 0.35f, c, 1f);
            }
        }, "Rulers", ctx.showRulers, true)) {
            ctx.showRulers = !ctx.showRulers;
        }
        separator();

        // snapping
        flow(h * 2 + 70);
        if (EditorWidgets.iconButton("##snapEdges", h, (dl, x, y, s, c) -> {
            dl.addRect(x + s * 0.1f, y + s * 0.3f, x + s * 0.5f, y + s * 0.7f, c, 1f, 0, 1.2f);
            dl.addRect(x + s * 0.5f, y + s * 0.1f, x + s * 0.9f, y + s * 0.5f, c, 1f, 0, 1.2f);
            dl.addLine(x + s * 0.5f, y, x + s * 0.5f, y + s, Glyphs.rgba(0.95f, 0.25f, 0.65f, 1f), 1.2f);
        }, "Snap to parent and sibling edges (hold Alt to bypass)", ctx.snapEdges, true)) {
            ctx.snapEdges = !ctx.snapEdges;
        }
        ImGui.sameLine();
        if (EditorWidgets.iconButton("##snapGrid", h, (dl, x, y, s, c) -> {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    dl.addCircleFilled(x + s * (0.2f + i * 0.3f), y + s * (0.2f + j * 0.3f), s * 0.06f, c);
                }
            }
        }, "Snap to the grid", ctx.snapGrid, true)) {
            ctx.snapGrid = !ctx.snapGrid;
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(60);
        if (ImGui.beginCombo("##gridStep", ctx.gridStep + " px")) {
            for (int g : new int[]{1, 2, 4, 8, 10, 16, 20, 32, 50}) {
                if (ImGui.selectable(g + " px", g == ctx.gridStep)) {
                    ctx.gridStep = g;
                }
            }
            ImGui.endCombo();
        }
        separator();

        // align and distribute
        boolean canAlign = ctx.actions.canAlign();
        flow(h * 8 + 7 * 2);
        for (AlignDistribute.Op op : AlignDistribute.Op.values()) {
            if (EditorWidgets.iconButton("##al" + op, h, (dl, x, y, s, c) -> Glyphs.alignOp(dl, op.name(), x, y, s, c),
                alignTip(op), false, canAlign)) {
                ctx.actions.align(op);
            }
            ImGui.sameLine(0, 2);
        }
        ImGui.newLine();
        separatorLine();
        ImGui.popStyleVar();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + 6);
        // second row: state forcing, graphs, renderer
        statesAndTools(doc, rt);
    }

    private void statesAndTools(UiEditorDocument doc, DesignerRuntime rt) {
        String key = doc.primary();
        float h = ImGui.getFrameHeight();
        if (rt != null && rt.mode() == DesignerRuntime.Mode.DESIGN) {
            ImGui.alignTextToFramePadding();
            ImGui.textDisabled("State");
            ImGui.sameLine();
            if (key == null) {
                ImGui.textDisabled("(select an element to force :hover, :active ...)");
            } else {
                for (String st : List.of("hover", "active", "focus", "checked", "disabled")) {
                    boolean on = rt.forcedStates(key).contains(st);
                    if (on) {
                        ThemeColors.pushToggleOn();
                    }
                    if (ImGui.button(":" + st + "##fs" + st)) {
                        rt.forceState(key, st, !on);
                    }
                    if (on) {
                        ImGui.popStyleColor(3);
                    }
                    ImGui.sameLine(0, 3);
                }
                if (rt.hasForcedStates()) {
                    if (ImGui.smallButton("Clear##fsClear")) {
                        rt.clearForcedStates();
                    }
                    ImGui.sameLine();
                }
            }
        } else if (rt != null) {
            ThemeColors.push(ImGuiCol.Text, ThemeColors.Tone.WARNING);
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted("Previewing: input goes to the document. Fixtures answer host actions.");
            ImGui.popStyleColor();
            ImGui.sameLine();
            if (ImGui.smallButton("Restart")) {
                rt.setMode(DesignerRuntime.Mode.DESIGN);
                rt.setMode(DesignerRuntime.Mode.PREVIEW);
            }
        }
        float right = 2 * 110 + 30;
        ImGui.sameLine(Math.max(ImGui.getCursorPosX() + 8, ImGui.getWindowContentRegionMaxX() - right));
        if (ImGui.button("Graphs...", 90, 0)) {
            workspace.openGraphs();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Behavior graphs of this document (compile to Lua on the same runtime)");
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(110);
        if (ImGui.beginCombo("##renderer", ctx.gpuPath ? "GPU" : "CPU raster")) {
            if (ImGui.selectable("GPU framebuffer", ctx.gpuPath)) {
                ctx.gpuPath = true;
            }
            if (ImGui.selectable("CPU raster", !ctx.gpuPath)) {
                ctx.gpuPath = false;
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Both paint with the game's Masonry code; the raster path avoids GPU flicker in pop-out windows");
        }
        ImGui.dummy(0, 2);
    }

    private static String alignTip(AlignDistribute.Op op) {
        return switch (op) {
            case LEFT -> "Align left edges";
            case CENTER_X -> "Align horizontal centres";
            case RIGHT -> "Align right edges";
            case TOP -> "Align top edges";
            case CENTER_Y -> "Align vertical centres";
            case BOTTOM -> "Align bottom edges";
            case DISTRIBUTE_X -> "Distribute horizontally (3+)";
            case DISTRIBUTE_Y -> "Distribute vertically (3+)";
        } + "\nWorks on absolutely positioned elements; a single one aligns to its parent.";
    }

    private void scaleCombo(String id, String prefix, float current, float[] options,
                            java.util.function.Consumer<Float> set, String tip) {
        ImGui.setNextItemWidth(90);
        if (ImGui.beginCombo(id, prefix + trim(current) + "x")) {
            for (float f : options) {
                if (ImGui.selectable(trim(f) + "x", Math.abs(f - current) < 1e-3)) {
                    set.accept(f);
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tip);
        }
    }

    private void zoomCentered(CanvasTransform t, float z) {
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() / 2f;
        float cy = ImGui.getWindowPosY() + ImGui.getWindowHeight() / 2f;
        t.zoomAt(z, cx, cy);
    }

    private static String trim(float f) {
        return f == Math.rint(f) ? String.valueOf((int) f) : String.format(Locale.ROOT, "%.2f", f).replaceAll("0+$", "");
    }

    /** Wraps the toolbar when the next item does not fit. */
    private static void flow(float width) {
        ImGui.sameLine();
        if (ImGui.getContentRegionAvailX() < width) {
            ImGui.newLine();
            ImGui.setCursorPosX(ImGui.getCursorPosX() + 6);
        }
    }

    private static void separator() {
        ImGui.sameLine();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addLine(x, y + 3, x, y + ImGui.getFrameHeight() - 3, EditorWidgets.border(0.8f), 1f);
        ImGui.dummy(1, ImGui.getFrameHeight());
    }

    private static void separatorLine() {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getCursorScreenPosY();
        dl.addLine(x, y, x + ImGui.getWindowWidth(), y, EditorWidgets.border(0.6f), 1f);
        ImGui.dummy(0, 3);
    }

    // ── banner and status ───────────────────────────────────────────────────

    private void banner(UiEditorDocument doc) {
        UiRecoveryService.Slot slot = recovery.get(doc);
        if (slot == null) {
            return;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getFrameHeight() + 10;
        dl.addRectFilled(x, y, x + w, y + h, ThemeColors.surfaceU32(ThemeColors.Tone.WARNING, 0.22f, 1f));
        Glyphs.warning(dl, x + 8, y + 6, h - 12, ThemeColors.u32(ThemeColors.Tone.WARNING, 1f));
        ImGui.setCursorScreenPos(x + h, y + 5);
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted("Unsaved changes from " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(slot.savedAt())) + " were recovered after Open Mason closed unexpectedly.");
        ImGui.sameLine();
        if (ImGui.button("Restore")) {
            ctx.service.restore(doc, slot);
            recovery.remove(doc);
        }
        ImGui.sameLine();
        if (ImGui.button("Discard")) {
            ctx.service.recovery().clear(slot);
            recovery.remove(doc);
        }
        ImGui.setCursorScreenPos(x, y + h);
        ImGui.dummy(w, 0);
    }

    private void status(UiEditorDocument doc, float w, float h) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        dl.addRectFilled(x, y, x + w, y + h, ThemeColors.u32(ImGuiCol.MenuBarBg, 1f));
        dl.addLine(x, y, x + w, y, EditorWidgets.border(0.8f), 1f);
        float ty = y + 4;
        String msg = doc.lastMessage();
        boolean bad = msg != null && (msg.startsWith("Cannot") || msg.startsWith("No ") || msg.contains("cannot")
            || msg.startsWith("Not saved") || msg.contains("refused") || msg.contains("failed"));
        String left = msg != null ? msg : selectionSummary(doc);
        dl.addText(x + 8, ty, bad ? ThemeColors.u32(ThemeColors.Tone.ERROR, 1f) : EditorWidgets.dim(1f), left);
        StringBuilder right = new StringBuilder();
        float px = canvas.pointerLogicalX();
        if (!Float.isNaN(px)) {
            right.append(Math.round(px)).append(", ").append(Math.round(canvas.pointerLogicalY())).append("    ");
        }
        int errors = workspace.errorCount();
        if (errors > 0) {
            right.append(errors).append(errors == 1 ? " problem" : " problems").append("    ");
        }
        right.append(doc.isDirty() ? "unsaved" : "saved");
        float rw = ImGui.calcTextSize(right.toString()).x;
        dl.addText(x + w - rw - 10, ty, errors > 0 ? ThemeColors.u32(ThemeColors.Tone.WARNING, 1f) : EditorWidgets.dim(1f),
            right.toString());
        ImGui.dummy(w, h);
    }

    private String selectionSummary(UiEditorDocument doc) {
        List<String> sel = doc.selection();
        DesignerRuntime rt = ctx.runtime();
        if (rt != null && rt.mode() == DesignerRuntime.Mode.PREVIEW) {
            return "Preview: the document receives pointer, wheel and (while focused) keys. Middle drag pans, Ctrl+wheel zooms.";
        }
        if (sel.isEmpty()) {
            return "Click to select, drag to move, handles resize, wheel zooms, middle/right drag pans. "
                + "Double-click drills into components.";
        }
        if (sel.size() > 1) {
            return sel.size() + " elements selected";
        }
        UiElement el = ctx.element(sel.getFirst());
        DocumentViewState v = ctx.view();
        if (el == null) {
            return sel.getFirst();
        }
        float s = v.scale();
        return el.type() + " " + (el.name() != null ? "#" + el.name() : el.key()) + "   "
            + Math.round(el.rect().width() / s) + " x " + Math.round(el.rect().height() / s)
            + "  at " + Math.round(el.rect().x() / s) + ", " + Math.round(el.rect().y() / s);
    }

    // ── welcome ─────────────────────────────────────────────────────────────

    private void welcome() {
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getContentRegionAvailY();
        float cardW = Math.min(520, w - 40);
        float x0 = ImGui.getCursorScreenPosX() + (w - cardW) / 2f;
        float y0 = ImGui.getCursorScreenPosY() + Math.max(30, h * 0.18f);
        ImDrawList dl = ImGui.getWindowDrawList();
        Glyphs.widget(dl, "Button", x0 + cardW / 2f - 28, y0, 56, EditorWidgets.accent(0.9f));
        ImGui.setCursorScreenPos(x0, y0 + 70);
        String title = "UI Editor";
        ImGui.setCursorScreenPos(x0 + (cardW - ImGui.calcTextSize(title).x) / 2f, y0 + 70);
        ImGui.textUnformatted(title);
        String sub = "Build Stonebreak screens and components: flexbox layout, style sheets, bindings and Lua.";
        ImGui.setCursorScreenPos(x0 + Math.max(0, (cardW - ImGui.calcTextSize(sub).x) / 2f), y0 + 92);
        ImGui.textDisabled(sub);
        ImGui.setCursorScreenPos(x0, y0 + 126);
        float bw = (cardW - 16) / 3f;
        ThemeColors.pushAccentButton();
        if (ImGui.button("New Screen", bw, 32)) {
            workspace.dialogs().openNewDocument(UiDocumentTemplates.MENU_SCREEN);
        }
        ImGui.popStyleColor(4);
        ImGui.sameLine(0, 8);
        if (ImGui.button("New Component", bw, 32)) {
            workspace.dialogs().openNewDocument(UiDocumentTemplates.BUTTON_COMPONENT);
        }
        ImGui.sameLine(0, 8);
        if (ImGui.button("Open...", bw, 32)) {
            workspace.openDocument();
        }
        var entries = ctx.project.entries(false).stream()
            .filter(e -> e.kind() == com.openmason.main.systems.uiEditor.service.UiProjectContext.Entry.Kind.SCREEN
                || e.kind() == com.openmason.main.systems.uiEditor.service.UiProjectContext.Entry.Kind.COMPONENT)
            .limit(8).toList();
        if (!entries.isEmpty()) {
            ImGui.setCursorScreenPos(x0, y0 + 176);
            ImGui.textDisabled("In this project");
            for (var e : entries) {
                ImGui.setCursorScreenPos(x0, ImGui.getCursorScreenPosY());
                if (ImGui.selectable(e.label() + "   " + e.relative() + "##welcome" + e.path(), false, 0, cardW, 0)) {
                    workspace.handleOpen(ctx.service.open(e.path()));
                }
            }
        }
    }
}
