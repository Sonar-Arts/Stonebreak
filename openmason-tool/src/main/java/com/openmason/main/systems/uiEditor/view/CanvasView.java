package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.input.PreviewInput;
import com.openmason.engine.ui.runtime.layout.HitTester;
import com.openmason.main.platform.ToolInputTap;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.canvas.Box;
import com.openmason.main.systems.uiEditor.canvas.CanvasTransform;
import com.openmason.main.systems.uiEditor.canvas.DropTargets;
import com.openmason.main.systems.uiEditor.canvas.ResizeHandle;
import com.openmason.main.systems.uiEditor.canvas.ResizeMath;
import com.openmason.main.systems.uiEditor.canvas.SnapEngine;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import com.openmason.main.systems.uiPreview.MasonryPreview;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseCursor;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The designer canvas: the engine-painted frame under a pan/zoom view, the editor overlays
 * drawn on top in screen space (selection, resize handles, spacing, flex flow, anchors, snap
 * guides, drop feedback), and the two input models:
 *
 * <ul>
 *   <li><b>Design</b>: the editor owns every event. Click selects (double-click drills into
 *       component internals), drag moves (absolute elements by offset with smart snapping, flow
 *       elements by reordering with a drop line), handles resize, empty drags marquee-select,
 *       middle/right drag pans, the wheel zooms about the pointer.</li>
 *   <li><b>Preview</b>: pointer, wheel and (while focused) keys go to the document's own input
 *       router, the same one the game uses; the editor only keeps pan (middle button) and zoom
 *       (Ctrl+wheel).</li>
 * </ul>
 */
final class CanvasView {

    private static final float HANDLE = 7f;
    private static final float DRAG_THRESHOLD = 4f;
    private static final float RULER = 18f;
    /** The vertical ruler is wider so its labels read horizontally. */
    private static final float VRULER = 34f;
    private static final String PALETTE_PAYLOAD = PalettePanel.PAYLOAD;

    private enum Drag { NONE, PAN, MARQUEE, MOVE_ABS, MOVE_FLOW, RESIZE }

    private final UiEditorContext ctx;
    private Drag drag = Drag.NONE;
    private int dragButton;
    private boolean dragStarted;
    private float pressX;
    private float pressY;
    private String pressKey;
    private final Map<String, Box> startBoxes = new LinkedHashMap<>();
    private final Map<String, float[]> startOffsets = new LinkedHashMap<>();
    private ResizeHandle handle;
    private Box resizeStart;
    private List<SnapEngine.Guide> guides = List.of();
    private DropTargets.Drop drop;
    private List<String> marqueeBase = List.of();
    private boolean editMade;
    private long dragStartRevision;
    private float lastPointerFx = Float.NaN;
    private float lastPointerFy = Float.NaN;
    private String hoverCanvasKey;
    private float lastViewW;
    private float lastViewH;
    // preview input
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
    private boolean tapInstalled;
    private boolean previewHadFocus;
    /** True while the previewed document holds the keyboard: editor shortcuts stand down. */
    boolean previewOwnsKeyboard;
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private boolean lastOver;

    CanvasView(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    boolean isDragging() {
        return drag != Drag.NONE && dragStarted;
    }

    /** Pointer position in logical px of the active document, or NaN when off canvas. */
    float pointerLogicalX() {
        DocumentViewState v = ctx.view();
        return v == null ? Float.NaN : lastPointerFx / v.scale();
    }

    float pointerLogicalY() {
        DocumentViewState v = ctx.view();
        return v == null ? Float.NaN : lastPointerFy / v.scale();
    }

    // ── frame ───────────────────────────────────────────────────────────────

    void draw(float w, float h, boolean windowFocused) {
        UiEditorDocument doc = ctx.doc();
        DesignerRuntime rt = ctx.runtime();
        DocumentViewState v = ctx.view();
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        CanvasTransform t = v.transform;
        float originX = x0 + (ctx.showRulers ? VRULER : 0);
        float originY = y0 + (ctx.showRulers ? RULER : 0);
        float viewW = w - (ctx.showRulers ? VRULER : 0);
        float viewH = h - (ctx.showRulers ? RULER : 0);
        t.setOrigin(originX, originY);
        boolean resized = Math.abs(viewW - lastViewW) > 1 || Math.abs(viewH - lastViewH) > 1;
        lastViewW = viewW;
        lastViewH = viewH;
        if ((v.fitPending || v.autoFit && resized) && viewW > 50 && viewH > 50) {
            t.fit(0, 0, v.frameWidth, v.frameHeight, viewW, viewH, 36, 1f);
            v.fitPending = false;
        }
        if (ctx.frameSelectionRequest) {
            ctx.frameSelectionRequest = false;
            frameSelection(doc, v, viewW, viewH);
        }

        // backdrop
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, backdrop());
        dl.pushClipRect(originX, originY, x0 + w, y0 + h, true);
        drawBackdropGrid(dl, t, originX, originY, x0 + w, y0 + h, v);

        ImGui.setCursorScreenPos(x0, y0);
        ImGui.invisibleButton("##uiCanvas", w, h, ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight
            | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();
        boolean preview = rt != null && rt.mode() == DesignerRuntime.Mode.PREVIEW;

        // the engine frame
        float fx0 = t.toScreenX(0);
        float fy0 = t.toScreenY(0);
        float fx1 = t.toScreenX(v.frameWidth);
        float fy1 = t.toScreenY(v.frameHeight);
        for (int i = 4; i >= 1; i--) {
            dl.addRectFilled(fx0 - i * 2, fy0 - i * 2 + 3, fx1 + i * 2, fy1 + i * 2 + 3, Glyphs.rgba(0, 0, 0, 0.05f), 4f);
        }
        MasonryPreview.Frame frame = null;
        if (rt != null) {
            rt.preview().setPath(ctx.gpuPath ? MasonryPreview.Path.GPU : MasonryPreview.Path.RASTER);
            frame = rt.paint(v.frameWidth, v.frameHeight, v.uiScale, v.pixelRatio, ImGui.getIO().getDeltaTime(), false);
        }
        if (frame != null) {
            setFilter(frame.texture(), t.zoom() >= 1.99f);
            dl.addImage(frame.texture(), fx0, fy0, fx1, fy1, 0, 0, frame.u1(), frame.v1());
        } else {
            dl.addRectFilled(fx0, fy0, fx1, fy1, ThemeColors.u32(ImGuiCol.FrameBg, 1f));
            String msg = rt == null ? "The game font could not be loaded" : rt.error() != null
                ? "Cannot show this document: " + rt.error() : "Preparing...";
            dl.addText(fx0 + 12, fy0 + 12, ThemeColors.u32(ThemeColors.Tone.ERROR, 1f), msg);
        }
        int frameBorder = preview ? ThemeColors.u32(ThemeColors.Tone.WARNING, 0.95f) : EditorWidgets.border(0.9f);
        dl.addRect(fx0 - 1, fy0 - 1, fx1 + 1, fy1 + 1, frameBorder, 0f, 0, preview ? 3f : 1f);
        String header = doc.title() + "   " + v.frameWidth + " x " + v.frameHeight + "   UI " + trim(v.uiScale) + "x"
            + (v.pixelRatio != 1f ? "   DPI " + trim(v.pixelRatio) + "x" : "") + (preview ? "   PREVIEW" : "");
        dl.addText(fx0, fy0 - ImGui.getTextLineHeight() - 6, preview ? ThemeColors.u32(ThemeColors.Tone.WARNING, 1f)
            : EditorWidgets.dim(1f), header);

        UiDocumentInstance ui = rt == null ? null : rt.instance();
        float mx = ImGui.getMousePosX();
        float my = ImGui.getMousePosY();
        lastPointerFx = hovered ? t.toFrameX(mx) : Float.NaN;
        lastPointerFy = hovered ? t.toFrameY(my) : Float.NaN;

        if (preview) {
            previewInput(rt, t, hovered, windowFocused, mx, my);
        } else {
            uninstallTap();
            previewOwnsKeyboard = false;
            if (ui != null) {
                designInput(doc, ui, v, t, hovered, mx, my);
                drawOverlays(dl, doc, ui, v, t);
            }
        }
        panAndZoom(t, hovered, preview, mx, my);
        paletteDrop(doc, ui, t, hovered, preview, dl);
        dl.popClipRect();
        if (ctx.showRulers) {
            drawRulers(dl, x0, y0, w, h, t, v, hovered ? mx : Float.NaN, hovered ? my : Float.NaN);
        }
    }

    private static String trim(float f) {
        return f == Math.rint(f) ? String.valueOf((int) f) : String.format(Locale.ROOT, "%.2f", f).replaceAll("0+$", "");
    }

    private static int backdrop() {
        return ThemeColors.isLightTheme() ? Glyphs.rgba(0.80f, 0.81f, 0.83f, 1f) : Glyphs.rgba(0.085f, 0.09f, 0.10f, 1f);
    }

    /** Linear filtering when shrunk, nearest when magnified: zoomed-in pixels stay exact. */
    private static void setFilter(int texture, boolean nearest) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int f = nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    private void drawBackdropGrid(ImDrawList dl, CanvasTransform t, float x0, float y0, float x1, float y1,
                                  DocumentViewState v) {
        float step = 100f * v.scale();
        while (step * t.zoom() < 40) {
            step *= 2;
        }
        while (step * t.zoom() > 160) {
            step /= 2;
        }
        int col = ThemeColors.isLightTheme() ? Glyphs.rgba(0, 0, 0, 0.06f) : Glyphs.rgba(1, 1, 1, 0.035f);
        float fx = (float) Math.floor(t.toFrameX(x0) / step) * step;
        for (float x = fx; t.toScreenX(x) < x1; x += step) {
            float sx = t.toScreenX(x);
            dl.addLine(sx, y0, sx, y1, col, 1f);
        }
        float fy = (float) Math.floor(t.toFrameY(y0) / step) * step;
        for (float y = fy; t.toScreenY(y) < y1; y += step) {
            float sy = t.toScreenY(y);
            dl.addLine(x0, sy, x1, sy, col, 1f);
        }
    }

    private void drawRulers(ImDrawList dl, float x0, float y0, float w, float h, CanvasTransform t, DocumentViewState v,
                            float mx, float my) {
        int bg = ThemeColors.u32(ImGuiCol.WindowBg, 1f);
        int ink = EditorWidgets.dim(0.9f);
        int line = EditorWidgets.border(0.9f);
        dl.addRectFilled(x0, y0, x0 + w, y0 + RULER, bg);
        dl.addRectFilled(x0, y0, x0 + VRULER, y0 + h, bg);
        dl.addLine(x0, y0 + RULER, x0 + w, y0 + RULER, line, 1f);
        dl.addLine(x0 + VRULER, y0, x0 + VRULER, y0 + h, line, 1f);
        float scale = v.scale();
        float stepLogical = 10;
        while (stepLogical * scale * t.zoom() < 50) {
            stepLogical *= stepLogical >= 100 && stepLogical % 250 != 0 ? 2.5f : 2;
        }
        float minor = stepLogical / 5f;
        dl.pushClipRect(x0 + VRULER, y0, x0 + w, y0 + RULER, true);
        float start = (float) Math.floor(t.toFrameX(x0 + VRULER) / scale / minor) * minor;
        for (float lx = start; t.toScreenX(lx * scale) < x0 + w; lx += minor) {
            float sx = t.toScreenX(lx * scale);
            boolean major = Math.abs(lx / stepLogical - Math.rint(lx / stepLogical)) < 1e-3;
            dl.addLine(sx, y0 + (major ? 4 : RULER - 5), sx, y0 + RULER, major ? ink : line, 1f);
            if (major) {
                dl.addText(sx + 3, y0 + 1, ink, String.valueOf(Math.round(lx)));
            }
        }
        if (!Float.isNaN(mx)) {
            dl.addLine(mx, y0, mx, y0 + RULER, EditorWidgets.accent(1f), 1f);
        }
        dl.popClipRect();
        dl.pushClipRect(x0, y0 + RULER, x0 + VRULER, y0 + h, true);
        start = (float) Math.floor(t.toFrameY(y0 + RULER) / scale / minor) * minor;
        for (float ly = start; t.toScreenY(ly * scale) < y0 + h; ly += minor) {
            float sy = t.toScreenY(ly * scale);
            boolean major = Math.abs(ly / stepLogical - Math.rint(ly / stepLogical)) < 1e-3;
            dl.addLine(x0 + (major ? VRULER - 10 : VRULER - 5), sy, x0 + VRULER, sy, major ? ink : line, 1f);
            if (major) {
                dl.addText(x0 + 3, sy + 1, ink, String.valueOf(Math.round(ly)));
            }
        }
        if (!Float.isNaN(my)) {
            dl.addLine(x0, my, x0 + VRULER, my, EditorWidgets.accent(1f), 1f);
        }
        dl.popClipRect();
        dl.addRectFilled(x0, y0, x0 + VRULER, y0 + RULER, bg);
    }

    // ── design input ────────────────────────────────────────────────────────

    private void designInput(UiEditorDocument doc, UiDocumentInstance ui, DocumentViewState v, CanvasTransform t,
                             boolean hovered, float mx, float my) {
        float fx = t.toFrameX(mx);
        float fy = t.toFrameY(my);
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean shift = ImGui.getIO().getKeyShift();
        boolean alt = ImGui.getIO().getKeyAlt();
        String hit = hovered ? pick(ui, doc, v, fx, fy, false) : null;
        hoverCanvasKey = drag == Drag.NONE ? hit : null;
        if (hoverCanvasKey != null) {
            ctx.hover(hoverCanvasKey);
        }

        // cursor feedback
        if (hovered && drag == Drag.NONE) {
            ResizeHandle h = handleUnder(doc, ui, v, t, mx, my);
            if (h != null) {
                ImGui.setMouseCursor(cursorFor(h));
            } else if (hit != null && doc.isSelected(hit) && !hit.equals(doc.archive().document().root().id())) {
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll);
            }
        }

        if (hovered && ImGui.isMouseDoubleClicked(0) && drag == Drag.NONE) {
            String deep = pick(ui, doc, v, fx, fy, true);
            if (deep != null) {
                doc.select(List.of(deep));
            }
            return;
        }
        if (hovered && ImGui.isMouseClicked(0) && !ImGui.isKeyDown(ImGuiKey.Space)) {
            pressX = mx;
            pressY = my;
            dragButton = 0;
            dragStarted = false;
            editMade = false;
            ResizeHandle h = handleUnder(doc, ui, v, t, mx, my);
            if (h != null) {
                handle = h;
                pressKey = doc.primary();
                resizeStart = Box.of(ui.find(pressKey).rect());
                startOffsets.clear();
                startOffsets.put(pressKey, ctx.actions.offsets(pressKey));
                drag = Drag.RESIZE;
                return;
            }
            pressKey = hit;
            String rootId = doc.archive().document().root().id();
            if (hit == null || hit.equals(rootId) && !doc.isSelected(rootId)) {
                if (!(ctrl || shift)) {
                    doc.select(hit == null ? List.of() : List.of(hit));
                }
                marqueeBase = ctrl || shift ? doc.selection() : List.of();
                drag = Drag.MARQUEE;
                return;
            }
            if (ctrl || shift) {
                doc.toggle(hit);
                drag = Drag.NONE;
                return;
            }
            if (!doc.isSelected(hit)) {
                doc.select(List.of(hit));
            }
            drag = hit.equals(rootId) ? Drag.MARQUEE : Drag.MOVE_ABS; // decided properly once the drag starts
            marqueeBase = List.of();
        }

        if (drag != Drag.NONE && drag != Drag.PAN && dragButton == 0) {
            if (ImGui.isMouseDown(0)) {
                float dx = mx - pressX;
                float dy = my - pressY;
                if (!dragStarted && Math.hypot(dx, dy) >= DRAG_THRESHOLD) {
                    dragStarted = true;
                    beginDrag(doc, ui);
                }
                if (dragStarted) {
                    updateDrag(doc, ui, v, t, dx / t.zoom(), dy / t.zoom(), mx, my, alt, shift);
                }
                if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                    cancelDrag(doc);
                }
            } else {
                endDrag(doc, ui, t, mx, my);
            }
        }

        if (hovered && ImGui.isMouseReleased(1) && !dragStartedOnRight) {
            ImGui.openPopup("##uiCanvasMenu");
        }
        if (ImGui.beginPopup("##uiCanvasMenu")) {
            ContextMenus.elementMenu(ctx);
            ImGui.endPopup();
        }
    }

    private boolean dragStartedOnRight;

    /** Topmost eligible element; design clicks resolve to document nodes unless drilling in. */
    private String pick(UiDocumentInstance ui, UiEditorDocument doc, DocumentViewState v, float fx, float fy, boolean deep) {
        UiElement el = HitTester.pickDesign(ui.paintOrder(), fx, fy,
            e -> !v.locked.contains(e.key()) && !v.hidden.contains(e.key()));
        if (el == null) {
            return null;
        }
        if (deep) {
            return el.key();
        }
        String key = el.key();
        // stay inside the instance the selection already drilled into
        String primary = doc.primary();
        if (primary != null && primary.indexOf('/') >= 0 && key.startsWith(primary.substring(0, primary.indexOf('/') + 1))) {
            return key;
        }
        while (key.indexOf('/') >= 0) {
            key = key.substring(0, key.lastIndexOf('/'));
        }
        return key;
    }

    private ResizeHandle handleUnder(UiEditorDocument doc, UiDocumentInstance ui, DocumentViewState v, CanvasTransform t,
                                     float mx, float my) {
        if (doc.selection().size() != 1) {
            return null;
        }
        String key = doc.primary();
        if (key.indexOf('/') >= 0 || key.equals(doc.archive().document().root().id()) || v.locked.contains(key)) {
            return null;
        }
        UiElement el = ui.find(key);
        if (el == null) {
            return null;
        }
        UiRect r = el.rect();
        return ResizeHandle.hit(t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.bottom()),
            mx, my, HANDLE);
    }

    private static int cursorFor(ResizeHandle h) {
        return switch (h) {
            case N, S -> ImGuiMouseCursor.ResizeNS;
            case E, W -> ImGuiMouseCursor.ResizeEW;
            case NE, SW -> ImGuiMouseCursor.ResizeNESW;
            default -> ImGuiMouseCursor.ResizeNWSE;
        };
    }

    private void beginDrag(UiEditorDocument doc, UiDocumentInstance ui) {
        dragStartRevision = doc.revision();
        if (drag == Drag.MOVE_ABS) {
            List<String> ids = NodeCommands.documentNodes(doc.selection());
            ids.remove(doc.archive().document().root().id());
            startBoxes.clear();
            startOffsets.clear();
            boolean allAbsolute = !ids.isEmpty();
            for (String id : ids) {
                if (!ctx.actions.isAbsolute(id)) {
                    allAbsolute = false;
                }
            }
            if (allAbsolute) {
                for (String id : ids) {
                    UiElement el = ui.find(id);
                    if (el != null && !ctx.view().locked.contains(id)) {
                        startBoxes.put(id, Box.of(el.rect()));
                        startOffsets.put(id, ctx.actions.offsets(id));
                    }
                }
            } else if (ids.isEmpty()) {
                drag = Drag.NONE; // component internals are moved by their component, not on this canvas
            } else {
                drag = Drag.MOVE_FLOW;
                startBoxes.clear();
                for (String id : ids) {
                    UiElement el = ui.find(id);
                    if (el != null) {
                        startBoxes.put(id, Box.of(el.rect()));
                    }
                }
            }
        }
    }

    private void updateDrag(UiEditorDocument doc, UiDocumentInstance ui, DocumentViewState v, CanvasTransform t,
                            float dx, float dy, float mx, float my, boolean noSnap, boolean keepAspect) {
        SnapEngine.Settings snap = noSnap ? SnapEngine.Settings.OFF : new SnapEngine.Settings(ctx.snapEdges,
            ctx.snapGrid, ctx.gridStep * v.scale(), 6f / t.zoom());
        switch (drag) {
            case MARQUEE -> {
                Box m = Box.spanning(t.toFrameX(pressX), t.toFrameY(pressY), t.toFrameX(mx), t.toFrameY(my));
                List<String> keys = new ArrayList<>(marqueeBase);
                String rootId = doc.archive().document().root().id();
                for (UiNode n : doc.archive().document().root().flatten()) {
                    UiElement el = ui.find(n.id());
                    if (el == null || n.id().equals(rootId) || v.locked.contains(n.id()) || v.hidden.contains(n.id())) {
                        continue;
                    }
                    Box b = Box.of(el.rect());
                    if (m.contains(b.l(), b.t()) && m.contains(b.r(), b.b()) && !keys.contains(n.id())) {
                        keys.add(n.id());
                    }
                }
                doc.select(keys);
            }
            case MOVE_ABS -> {
                if (startBoxes.isEmpty()) {
                    return;
                }
                Box union = null;
                for (Box b : startBoxes.values()) {
                    union = b.union(union);
                }
                SnapEngine.Result r = SnapEngine.move(union, dx, dy, snapTargets(ui, startBoxes.keySet()), snap);
                guides = r.guides();
                List<UiCommand> steps = new ArrayList<>();
                for (Map.Entry<String, float[]> e : startOffsets.entrySet()) {
                    steps.add(NodeCommands.setStyle(List.of(e.getKey()),
                        ResizeMath.moveStyle(e.getValue()[0], e.getValue()[1], r.dx(), r.dy(), v.scale()), "Move"));
                }
                editMade |= ctx.actions.run(UiCommand.of(startBoxes.size() == 1 ? "Move " + startBoxes.keySet().iterator()
                    .next() : "Move " + startBoxes.size() + " elements", "drag:move", c -> {
                    for (UiCommand s : steps) {
                        s.apply(c);
                    }
                }));
            }
            case MOVE_FLOW -> {
                Set<String> excluded = new HashSet<>(startBoxes.keySet());
                drop = DropTargets.resolve(ui, doc.archive().document().root(), t.toFrameX(mx), t.toFrameY(my), excluded);
            }
            case RESIZE -> {
                if (resizeStart == null) {
                    return;
                }
                if (keepAspect && handle.dx != 0 && handle.dy != 0 && resizeStart.height() > 0) {
                    float aspect = resizeStart.width() / resizeStart.height();
                    if (Math.abs(dx) > Math.abs(dy) * aspect) {
                        dy = handle.dy * Math.abs(dx) / aspect * Math.signum(handle.dx * dx);
                    } else {
                        dx = handle.dx * Math.abs(dy) * aspect * Math.signum(handle.dy * dy);
                    }
                }
                SnapEngine.Result r = SnapEngine.resize(resizeStart, handle, dx, dy, snapTargets(ui, Set.of(pressKey)), snap);
                guides = r.guides();
                Box end = ResizeMath.resized(resizeStart, handle, r.dx(), r.dy());
                float[] o = startOffsets.getOrDefault(pressKey, new float[]{0, 0});
                Map<String, UiValue> style = ResizeMath.resizeStyle(resizeStart, end, handle, v.scale(),
                    ctx.actions.isAbsolute(pressKey), o[0], o[1]);
                editMade |= ctx.actions.run(NodeCommands.setStyle(List.of(pressKey), style, "Resize " + pressKey));
                drawSizeLabel(end, t, v);
            }
            default -> {
            }
        }
    }

    private void drawSizeLabel(Box end, CanvasTransform t, DocumentViewState v) {
        String label = Math.round(end.width() / v.scale()) + " x " + Math.round(end.height() / v.scale());
        ImDrawList dl = ImGui.getWindowDrawList();
        float tw = ImGui.calcTextSize(label).x;
        float x = (t.toScreenX(end.l()) + t.toScreenX(end.r())) / 2f - tw / 2f - 6;
        float y = t.toScreenY(end.b()) + 8;
        dl.addRectFilled(x, y, x + tw + 12, y + ImGui.getTextLineHeight() + 4, EditorWidgets.accent(0.95f), 4f);
        dl.addText(x + 6, y + 2, EditorWidgets.onAccent(), label);
    }

    private List<Box> snapTargets(UiDocumentInstance ui, Set<String> moving) {
        List<Box> out = new ArrayList<>();
        String any = moving.iterator().next();
        UiElement el = ui.find(any);
        if (el == null || el.parent() == null) {
            return out;
        }
        out.add(Box.of(el.parent().rect()));
        for (UiElement sib : el.parent().children()) {
            if (!moving.contains(sib.key()) && !sib.rect().isEmpty()) {
                out.add(Box.of(sib.rect()));
            }
        }
        return out;
    }

    private void endDrag(UiEditorDocument doc, UiDocumentInstance ui, CanvasTransform t, float mx, float my) {
        if (drag == Drag.MOVE_FLOW && dragStarted && drop != null && !startBoxes.isEmpty()) {
            ctx.actions.run(NodeCommands.move(new ArrayList<>(startBoxes.keySet()), drop.location()));
        }
        if (drag == Drag.MOVE_ABS && !dragStarted && pressKey != null && !ImGui.getIO().getKeyCtrl()
                && !ImGui.getIO().getKeyShift()) {
            doc.select(List.of(pressKey)); // a click on one of several selected narrows the selection
        }
        resetDrag(doc);
    }

    private void cancelDrag(UiEditorDocument doc) {
        // undo only what this drag recorded: a drag that snapped back to where it started recorded nothing
        if (editMade && doc.revision() != dragStartRevision) {
            doc.undo();
        }
        resetDrag(doc);
    }

    private void resetDrag(UiEditorDocument doc) {
        drag = Drag.NONE;
        dragStarted = false;
        guides = List.of();
        drop = null;
        handle = null;
        resizeStart = null;
        startBoxes.clear();
        startOffsets.clear();
        editMade = false;
        doc.endInteraction();
    }

    private void panAndZoom(CanvasTransform t, boolean hovered, boolean preview, float mx, float my) {
        boolean spacePan = !preview && ImGui.isKeyDown(ImGuiKey.Space) && ImGui.isMouseDown(0);
        if (hovered && (ImGui.isMouseClicked(2) || !preview && ImGui.isMouseClicked(1))) {
            dragStartedOnRight = false;
        }
        boolean panning = hovered || drag == Drag.PAN;
        if (panning && (ImGui.isMouseDragging(2, 1f) || !preview && ImGui.isMouseDragging(1, 3f) || spacePan)) {
            if (ImGui.isMouseDragging(1, 3f)) {
                dragStartedOnRight = true;
            }
            t.panBy(ImGui.getIO().getMouseDeltaX(), ImGui.getIO().getMouseDeltaY());
            ctx.view().autoFit = false;
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
        if (!ImGui.isMouseDown(1) && !ImGui.isMouseReleased(1)) {
            dragStartedOnRight = false;
        }
        float wheel = ImGui.getIO().getMouseWheel();
        if (hovered && wheel != 0 && (!preview || ImGui.getIO().getKeyCtrl())) {
            t.zoomAt(t.zoom() * (float) Math.pow(1.15, wheel), mx, my);
            ctx.view().autoFit = false;
        }
    }

    // ── palette drag and drop ───────────────────────────────────────────────

    private void paletteDrop(UiEditorDocument doc, UiDocumentInstance ui, CanvasTransform t, boolean hovered,
                             boolean preview, ImDrawList dl) {
        if (preview || ui == null || !ImGui.beginDragDropTarget()) {
            return;
        }
        Object payload = ImGui.acceptDragDropPayload(PALETTE_PAYLOAD,
            ImGuiDragDropFlags.AcceptBeforeDelivery | ImGuiDragDropFlags.AcceptNoDrawDefaultRect);
        if (payload instanceof String item) {
            DropTargets.Drop d = DropTargets.resolve(ui, doc.archive().document().root(),
                t.toFrameX(ImGui.getMousePosX()), t.toFrameY(ImGui.getMousePosY()), Set.of());
            if (d != null) {
                drawDrop(dl, ui, d, t);
                if (ImGui.isMouseReleased(0)) {
                    ctx.actions.run(PalettePanel.createCommand(ctx, item, d.location()));
                }
            }
        }
        ImGui.endDragDropTarget();
    }

    // ── preview input ───────────────────────────────────────────────────────

    private void previewInput(DesignerRuntime rt, CanvasTransform t, boolean hovered, boolean windowFocused,
                              float mx, float my) {
        installTap();
        PreviewInput input = rt.input();
        if (input == null) {
            pendingKeys.clear();
            pendingChars.clear();
            return;
        }
        input.setMapping(new PreviewMapping(t.toScreenX(0), t.toScreenY(0), t.zoom()));
        int mods = mods();
        if (mx != lastMouseX || my != lastMouseY || hovered != lastOver) {
            input.pointerMove(mx, my, hovered);
            lastMouseX = mx;
            lastMouseY = my;
            lastOver = hovered;
        }
        for (int b = 0; b < 2; b++) { // middle stays the editor's pan button
            if (ImGui.isMouseClicked(b)) {
                input.pointerButton(mx, my, hovered, b, true, mods);
            }
            if (ImGui.isMouseReleased(b)) {
                input.pointerButton(mx, my, hovered, b, false, mods);
            }
        }
        float wheel = ImGui.getIO().getMouseWheel();
        float wheelH = ImGui.getIO().getMouseWheelH();
        if ((wheel != 0 || wheelH != 0) && !ImGui.getIO().getKeyCtrl()) {
            input.wheel(mx, my, hovered, wheelH, wheel, mods);
        }
        boolean focused = windowFocused && !ImGui.getIO().getWantTextInput();
        if (previewHadFocus && !focused) {
            input.router().windowFocusLost();
        }
        previewHadFocus = focused;
        if (focused) {
            for (int[] k : pendingKeys) {
                input.router().key(k[0], k[1], k[2]);
            }
            for (int cp : pendingChars) {
                input.router().text(cp);
            }
        }
        pendingKeys.clear();
        pendingChars.clear();
        previewOwnsKeyboard = focused && input.wantsKeyboard();
        if (previewOwnsKeyboard) {
            ImGui.setNextFrameWantCaptureKeyboard(true);
        }
    }

    private void installTap() {
        if (!tapInstalled) {
            ToolInputTap.add(tap);
            tapInstalled = true;
        }
    }

    void uninstallTap() {
        if (tapInstalled) {
            ToolInputTap.remove(tap);
            tapInstalled = false;
        }
        pendingKeys.clear();
        pendingChars.clear();
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

    // ── overlays ────────────────────────────────────────────────────────────

    private void drawOverlays(ImDrawList dl, UiEditorDocument doc, UiDocumentInstance ui, DocumentViewState v,
                              CanvasTransform t) {
        int accent = EditorWidgets.accent(1f);
        String hover = ctx.hoverKey;
        if (ctx.showBounds) {
            for (UiNode n : doc.archive().document().root().flatten()) {
                UiElement el = ui.find(n.id());
                if (el != null && !el.rect().isEmpty() && !v.hidden.contains(n.id())) {
                    UiRect r = el.rect();
                    Glyphs.dashed(dl, t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.y()),
                        Glyphs.withAlpha(accent, 0.18f), 1f, 3f, 3f);
                    Glyphs.dashed(dl, t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.x()), t.toScreenY(r.bottom()),
                        Glyphs.withAlpha(accent, 0.18f), 1f, 3f, 3f);
                }
            }
        }
        String primary = doc.primary();
        UiElement primaryEl = primary == null ? null : ui.find(primary);
        if (primaryEl != null && ctx.showSpacing) {
            drawSpacing(dl, primaryEl, v, t);
        }
        if (primaryEl != null && ctx.showFlex) {
            drawFlex(dl, doc, primaryEl, v, t);
        }
        if (primaryEl != null && ctx.actions.isAbsolute(primary)) {
            drawAnchors(dl, primaryEl, v, t);
        }
        if (hover != null && !doc.isSelected(hover)) {
            UiElement el = ui.find(hover);
            if (el != null && !el.rect().isEmpty()) {
                UiRect r = el.rect();
                dl.addRect(t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.bottom()),
                    Glyphs.withAlpha(accent, 0.75f), 0f, 0, 1f);
                tag(dl, el, t, false);
            }
        }
        Box group = null;
        for (String key : doc.selection()) {
            UiElement el = ui.find(key);
            if (el == null) {
                continue;
            }
            UiRect r = el.rect();
            float x0 = t.toScreenX(r.x());
            float y0 = t.toScreenY(r.y());
            float x1 = t.toScreenX(r.right());
            float y1 = t.toScreenY(r.bottom());
            boolean internal = key.indexOf('/') >= 0;
            int col = internal ? Glyphs.typeColor("Instance", 1f) : accent;
            dl.addRect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, Glyphs.withAlpha(col, 0.35f), 0f, 0, 4f);
            dl.addRect(x0, y0, x1, y1, col, 0f, 0, 1.5f);
            group = Box.of(r).union(group);
        }
        if (doc.selection().size() == 1 && primaryEl != null) {
            tag(dl, primaryEl, t, true);
            if (primary.indexOf('/') < 0 && !primary.equals(doc.archive().document().root().id())
                    && !v.locked.contains(primary)) {
                UiRect r = primaryEl.rect();
                float x0 = t.toScreenX(r.x());
                float y0 = t.toScreenY(r.y());
                float x1 = t.toScreenX(r.right());
                float y1 = t.toScreenY(r.bottom());
                for (ResizeHandle h : ResizeHandle.values()) {
                    if ((h.dx == 0 && x1 - x0 < 3 * HANDLE) || (h.dy == 0 && y1 - y0 < 3 * HANDLE)) {
                        continue;
                    }
                    float hx = h.x(x0, x1);
                    float hy = h.y(y0, y1);
                    float s = HANDLE / 2f + 0.5f;
                    dl.addRectFilled(hx - s, hy - s, hx + s, hy + s, Glyphs.rgba(1, 1, 1, 1));
                    dl.addRect(hx - s, hy - s, hx + s, hy + s, accent, 0f, 0, 1.4f);
                }
            }
        } else if (group != null && doc.selection().size() > 1) {
            Glyphs.dashedRect(dl, t.toScreenX(group.l()) - 4, t.toScreenY(group.t()) - 4, t.toScreenX(group.r()) + 4,
                t.toScreenY(group.b()) + 4, Glyphs.withAlpha(accent, 0.8f), 1f);
        }
        // live drag feedback
        int guide = Glyphs.rgba(0.95f, 0.25f, 0.65f, 1f);
        for (SnapEngine.Guide g : guides) {
            if (g.vertical()) {
                float x = t.toScreenX(g.pos());
                dl.addLine(x, t.toScreenY(g.from()) - 6, x, t.toScreenY(g.to()) + 6, guide, 1f);
            } else {
                float y = t.toScreenY(g.pos());
                dl.addLine(t.toScreenX(g.from()) - 6, y, t.toScreenX(g.to()) + 6, y, guide, 1f);
            }
        }
        if (drag == Drag.MOVE_FLOW && dragStarted && drop != null && !startBoxes.isEmpty()) {
            drawDrop(dl, ui, drop, t);
            for (Box b : startBoxes.values()) {
                dl.addRectFilled(t.toScreenX(b.l()), t.toScreenY(b.t()), t.toScreenX(b.r()), t.toScreenY(b.b()),
                    Glyphs.withAlpha(accent, 0.12f));
            }
        }
        if (drag == Drag.MARQUEE && dragStarted) {
            float mx = ImGui.getMousePosX();
            float my = ImGui.getMousePosY();
            dl.addRectFilled(Math.min(pressX, mx), Math.min(pressY, my), Math.max(pressX, mx), Math.max(pressY, my),
                Glyphs.withAlpha(accent, 0.10f));
            dl.addRect(Math.min(pressX, mx), Math.min(pressY, my), Math.max(pressX, mx), Math.max(pressY, my),
                Glyphs.withAlpha(accent, 0.8f), 0f, 0, 1f);
        }
    }

    private void drawDrop(ImDrawList dl, UiDocumentInstance ui, DropTargets.Drop d, CanvasTransform t) {
        int accent = EditorWidgets.accent(1f);
        UiElement container = ui.find(d.containerKey());
        if (container != null) {
            UiRect r = container.rect();
            dl.addRectFilled(t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.bottom()),
                Glyphs.withAlpha(accent, 0.07f));
            dl.addRect(t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.bottom()),
                Glyphs.withAlpha(accent, 0.6f), 0f, 0, 1f);
        }
        float x0 = t.toScreenX(d.x0());
        float y0 = t.toScreenY(d.y0());
        float x1 = t.toScreenX(d.x1());
        float y1 = t.toScreenY(d.y1());
        dl.addLine(x0, y0, x1, y1, accent, 3f);
        dl.addCircleFilled(x0, y0, 4f, accent);
        dl.addCircleFilled(x1, y1, 4f, accent);
    }

    /** Name tag above an element: glyph, name or id, type. */
    private void tag(ImDrawList dl, UiElement el, CanvasTransform t, boolean selected) {
        UiRect r = el.rect();
        String kind = el.type();
        if (com.openmason.engine.format.omui.UiNode.INSTANCE_TYPE.equals(kind) && el.node().instance() != null) {
            String c = el.node().instance().component();
            kind = c.substring(c.lastIndexOf('/') + 1);
        }
        String label = (el.name() != null ? el.name() : el.id()) + "  " + kind;
        float h = ImGui.getTextLineHeight() + 4;
        float w = ImGui.calcTextSize(label).x + h + 8;
        float x = t.toScreenX(r.x());
        float y = t.toScreenY(r.y()) - h - 3;
        if (y < ImGui.getWindowPosY() + RULER + 4) {
            y = t.toScreenY(r.bottom()) + 3;
        }
        int bg = selected ? (el.key().indexOf('/') >= 0 ? Glyphs.typeColor("Instance", 0.95f) : EditorWidgets.accent(0.95f))
            : Glyphs.rgba(0.12f, 0.13f, 0.15f, 0.9f);
        int fg = selected ? EditorWidgets.onAccent() : Glyphs.rgba(0.95f, 0.95f, 0.96f, 1f);
        dl.addRectFilled(x, y, x + w, y + h, bg, 3f);
        Glyphs.widget(dl, el.type(), x + 3, y + 2, h - 4, fg);
        dl.addText(x + h + 2, y + 2, fg, label);
    }

    private void drawSpacing(ImDrawList dl, UiElement el, DocumentViewState v, CanvasTransform t) {
        float s = v.scale();
        UiRect r = el.rect();
        float[] m = edges(el, "margin", s);
        float[] p = edges(el, "padding", s);
        float[] b = edges(el, "border", s);
        int margin = Glyphs.rgba(0.96f, 0.62f, 0.26f, 0.22f);
        int padding = Glyphs.rgba(0.40f, 0.80f, 0.45f, 0.22f);
        ring(dl, t, r.x() - m[0], r.y() - m[1], r.right() + m[2], r.bottom() + m[3], r.x(), r.y(), r.right(), r.bottom(), margin);
        ring(dl, t, r.x() + b[0], r.y() + b[1], r.right() - b[2], r.bottom() - b[3], r.x() + b[0] + p[0], r.y() + b[1] + p[1],
            r.right() - b[2] - p[2], r.bottom() - b[3] - p[3], padding);
    }

    /** Left, top, right, bottom of {@code margin}/{@code padding}/{@code border} in frame px (numbers only). */
    private static float[] edges(UiElement el, String prefix, float scale) {
        String[] sides = {"left", "top", "right", "bottom"};
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            String prop = "border".equals(prefix) ? "border-" + sides[i] + "-width" : prefix + "-" + sides[i];
            out[i] = (float) el.computedStyle().number(prop, 0) * scale;
        }
        return out;
    }

    private static void ring(ImDrawList dl, CanvasTransform t, float ox0, float oy0, float ox1, float oy1, float ix0,
                             float iy0, float ix1, float iy1, int col) {
        float a = t.toScreenX(ox0);
        float b = t.toScreenY(oy0);
        float c = t.toScreenX(ox1);
        float d = t.toScreenY(oy1);
        float e = t.toScreenX(ix0);
        float f = t.toScreenY(iy0);
        float g = t.toScreenX(ix1);
        float h = t.toScreenY(iy1);
        if (f > b) {
            dl.addRectFilled(a, b, c, f, col);
        }
        if (d > h) {
            dl.addRectFilled(a, h, c, d, col);
        }
        if (e > a) {
            dl.addRectFilled(a, f, e, h, col);
        }
        if (c > g) {
            dl.addRectFilled(g, f, c, h, col);
        }
    }

    private void drawFlex(ImDrawList dl, UiEditorDocument doc, UiElement el, DocumentViewState v, CanvasTransform t) {
        UiNode node = ctx.node(el.key());
        boolean container = node != null ? NodeCommands.acceptsChildren(node.type()) : !el.children().isEmpty();
        if (!container || el.children().isEmpty()) {
            return;
        }
        String dir = el.computedStyle().keyword("flex-direction", "column");
        boolean row = dir.startsWith("row");
        int flex = Glyphs.rgba(0.62f, 0.45f, 0.95f, 1f);
        UiRect prev = null;
        List<UiElement> kids = new ArrayList<>(el.children());
        if (dir.endsWith("reverse")) {
            java.util.Collections.reverse(kids);
        }
        for (UiElement c : kids) {
            UiRect r = c.rect();
            if (r.isEmpty() || DropTargetsAccess.isAbsolute(c)) {
                continue;
            }
            Glyphs.dashedRect(dl, t.toScreenX(r.x()), t.toScreenY(r.y()), t.toScreenX(r.right()), t.toScreenY(r.bottom()),
                Glyphs.withAlpha(flex, 0.55f), 1f);
            if (prev != null) {
                if (row && r.x() > prev.right() + 0.5f) {
                    float top = Math.max(prev.y(), r.y());
                    float bot = Math.min(prev.bottom(), r.bottom());
                    if (bot <= top) {
                        top = Math.min(prev.y(), r.y());
                        bot = Math.max(prev.bottom(), r.bottom());
                    }
                    Glyphs.hatch(dl, t.toScreenX(prev.right()), t.toScreenY(top), t.toScreenX(r.x()), t.toScreenY(bot),
                        Glyphs.withAlpha(flex, 0.45f), 5f);
                } else if (!row && r.y() > prev.bottom() + 0.5f) {
                    float l = Math.min(prev.x(), r.x());
                    float rr = Math.max(prev.right(), r.right());
                    Glyphs.hatch(dl, t.toScreenX(l), t.toScreenY(prev.bottom()), t.toScreenX(rr), t.toScreenY(r.y()),
                        Glyphs.withAlpha(flex, 0.45f), 5f);
                }
            }
            prev = r;
        }
        // main-axis arrow along the container's leading edge
        UiRect r = el.rect();
        float sx0 = t.toScreenX(r.x());
        float sy0 = t.toScreenY(r.y());
        float sx1 = t.toScreenX(r.right());
        float sy1 = t.toScreenY(r.bottom());
        float a = 12;
        if (row) {
            float y = sy0 + 7;
            if (dir.endsWith("reverse")) {
                Glyphs.arrow(dl, sx1 - 6, y, Math.max(sx0 + 6, sx1 - 6 - Math.min(80, sx1 - sx0 - 12)), y, flex, 2f, a);
            } else {
                Glyphs.arrow(dl, sx0 + 6, y, Math.min(sx1 - 6, sx0 + 6 + Math.min(80, sx1 - sx0 - 12)), y, flex, 2f, a);
            }
        } else {
            float x = sx0 + 7;
            if (dir.endsWith("reverse")) {
                Glyphs.arrow(dl, x, sy1 - 6, x, Math.max(sy0 + 6, sy1 - 6 - Math.min(80, sy1 - sy0 - 12)), flex, 2f, a);
            } else {
                Glyphs.arrow(dl, x, sy0 + 6, x, Math.min(sy1 - 6, sy0 + 6 + Math.min(80, sy1 - sy0 - 12)), flex, 2f, a);
            }
        }
    }

    private void drawAnchors(ImDrawList dl, UiElement el, DocumentViewState v, CanvasTransform t) {
        if (el.parent() == null) {
            return;
        }
        int col = Glyphs.rgba(0.30f, 0.78f, 0.86f, 1f);
        UiRect r = el.rect();
        UiRect p = el.parent().rect();
        float s = v.scale();
        String[] sides = {"left", "top", "right", "bottom"};
        for (String side : sides) {
            UiValue val = el.computedStyle().get(side);
            if (val == null || val instanceof UiValue.Str str && "auto".equals(str.value())) {
                continue;
            }
            float ex;
            float ey;
            float px;
            float py;
            switch (side) {
                case "left" -> { ex = r.x(); ey = r.y() + r.height() / 2f; px = p.x(); py = ey; }
                case "right" -> { ex = r.right(); ey = r.y() + r.height() / 2f; px = p.right(); py = ey; }
                case "top" -> { ex = r.x() + r.width() / 2f; ey = r.y(); px = ex; py = p.y(); }
                default -> { ex = r.x() + r.width() / 2f; ey = r.bottom(); px = ex; py = p.bottom(); }
            }
            float sx0 = t.toScreenX(px);
            float sy0 = t.toScreenY(py);
            float sx1 = t.toScreenX(ex);
            float sy1 = t.toScreenY(ey);
            Glyphs.dashed(dl, sx0, sy0, sx1, sy1, col, 1.2f, 5f, 3f);
            dl.addCircleFilled(sx0, sy0, 3f, col);
            float dist = Math.abs(side.equals("left") || side.equals("right") ? ex - px : ey - py) / s;
            String label = Math.round(dist) + "";
            float tx = (sx0 + sx1) / 2f - ImGui.calcTextSize(label).x / 2f;
            float ty = (sy0 + sy1) / 2f - ImGui.getTextLineHeight() - 2;
            dl.addRectFilled(tx - 3, ty, tx + ImGui.calcTextSize(label).x + 3, ty + ImGui.getTextLineHeight(),
                Glyphs.rgba(0.10f, 0.11f, 0.13f, 0.85f), 3f);
            dl.addText(tx, ty, col, label);
        }
    }

    private void frameSelection(UiEditorDocument doc, DocumentViewState v, float viewW, float viewH) {
        UiDocumentInstance ui = ctx.instance();
        Box b = null;
        if (ui != null) {
            for (String key : doc.selection()) {
                UiElement el = ui.find(key);
                if (el != null && !el.rect().isEmpty()) {
                    b = Box.of(el.rect()).union(b);
                }
            }
        }
        if (b == null) {
            v.transform.fit(0, 0, v.frameWidth, v.frameHeight, viewW, viewH, 36, 1f);
        } else {
            v.transform.fit(b.l(), b.t(), b.width(), b.height(), viewW, viewH, 80, 4f);
            v.autoFit = false;
        }
    }

    /** Package bridge: the absolute test {@link DropTargets} uses, shared with the flex overlay. */
    private static final class DropTargetsAccess {
        static boolean isAbsolute(UiElement e) {
            return "absolute".equals(e.computedStyle().keyword("position", "relative"));
        }
    }
}
