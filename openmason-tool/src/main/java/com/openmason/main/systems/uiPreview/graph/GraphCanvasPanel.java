package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.graph.edit.GraphLayout;
import com.openmason.engine.ui.script.GraphDebugger;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.CommentHit;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.CommentPart;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.NodeBox;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.PinHit;
import com.openmason.main.systems.uiPreview.graph.GraphScene.LinkView;
import com.openmason.main.systems.uiPreview.graph.GraphScene.NodeView;
import com.openmason.main.systems.uiPreview.graph.GraphScene.Pin;
import com.openmason.main.systems.uiPreview.graph.GraphScene.Role;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node canvas: draws the current body with {@link ImDrawList} and turns pointer and keyboard
 * input into {@link GraphEditor} edits. Geometry and hit testing live in {@link GraphCanvasView}
 * and {@link GraphScene} (unit-tested); this class owns the drag state machine and the paint.
 * Debug overlays (active glow, hit counts, breakpoints, paused marker, port values) read the
 * preview's {@link GraphDebugger}.
 */
final class GraphCanvasPanel {

    private static final double GLOW_SECONDS = 0.6;
    private static final double PIN_HIT_PX = 10;
    private static final double LINK_HIT_PX = 7;
    private static final String NODE_MENU = "##graphNodeMenu";
    private static final String COMMENT_MENU = "##graphCommentMenu";

    private enum Drag { NONE, NODES, BOX, PAN, LINK, COMMENT_MOVE, COMMENT_RESIZE }

    private final GraphPalettePopup palette = new GraphPalettePopup();
    private final Map<String, Double> widths = new HashMap<>();
    private Drag drag = Drag.NONE;
    private int dragButton;
    private int dragSeq;
    private String dragToken = "";
    private double accX;
    private double accY;
    private double boxX;
    private double boxY;
    private Set<String> boxBase = Set.of();
    private String linkNode;
    private PortSpec linkPort;
    private boolean linkOutput;
    private String commentId;
    private String menuNode;
    private String menuComment;
    private boolean spaceHeld;

    // frame locals
    private float ox;
    private float oy;
    private float cw;
    private float ch;
    private GraphScene scene;

    /** Draws the canvas into the remaining content region. */
    void draw(GraphEditorState st, boolean windowFocused) {
        ImVec2 avail = ImGui.getContentRegionAvail();
        cw = Math.max(64, avail.x);
        ch = Math.max(64, avail.y);
        ImVec2 origin = ImGui.getCursorScreenPos();
        ox = origin.x;
        oy = origin.y;
        ImGui.invisibleButton("##graphCanvas", cw, ch, ImGuiButtonFlags.MouseButtonLeft
            | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();
        GraphEditor ed = st.editor;
        GraphCanvasView view = st.view();
        scene = build(ed, st.body);
        applyFrameRequests(st, view);

        double mx = ImGui.getMousePosX() - ox;
        double my = ImGui.getMousePosY() - oy;
        Object before = ed.document();
        spaceHeld = windowFocused && ImGui.isKeyDown(ImGuiKey.Space);
        input(st, view, hovered, mx, my);
        if (windowFocused && !ImGui.getIO().getWantTextInput()) {
            keyboard(st, view, hovered, mx, my);
        }
        if (ed.document() != before) {
            scene = build(ed, st.body); // an edit this frame: draw the result, not the previous layout
        }
        paint(st, view, hovered, mx, my);

        String added = palette.draw(st);
        if (added != null) {
            st.selectOnly(added);
        }
        nodeMenu(st, view);
        commentMenu(st);
    }

    /** Frames the whole body now (toolbar button). */
    void frameAllNow(GraphEditorState st) {
        st.view().frameBoxes(build(st.editor, st.body).boxes(), cw, ch);
    }

    // ── scene ───────────────────────────────────────────────────────────────

    private GraphScene build(GraphEditor ed, String body) {
        if (widths.size() > 4000) {
            widths.clear();
        }
        return GraphScene.build(ed, body, s -> widths.computeIfAbsent(s, t -> (double) ImGui.calcTextSize(t).x));
    }

    private void applyFrameRequests(GraphEditorState st, GraphCanvasView view) {
        if (st.frameNode != null) {
            NodeView n = scene.node(st.frameNode);
            st.frameNode = null;
            if (n != null) {
                NodeBox b = n.box();
                view.frame(b.x() - 120, b.y() - 80, b.x() + b.width() + 120, b.y() + b.height() + 80, cw, ch, 20);
            }
            st.frameAll = false;
        } else if (st.frameAll) {
            st.frameAll = false;
            view.frameBoxes(scene.boxes(), cw, ch);
        }
    }

    // ── pointer ─────────────────────────────────────────────────────────────

    private void input(GraphEditorState st, GraphCanvasView view, boolean hovered, double mx, double my) {
        GraphEditor ed = st.editor;
        if (drag == Drag.NONE && hovered) {
            float wheel = ImGui.getIO().getMouseWheel();
            if (wheel != 0) {
                view.zoomAbout(mx, my, Math.pow(1.1, wheel));
            }
            if (ImGui.isMouseClicked(ImGuiMouseButton.Middle)) {
                begin(Drag.PAN, ImGuiMouseButton.Middle);
            } else if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                leftPress(st, view, mx, my);
            } else if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rightPress(st, view, mx, my);
            }
        }
        if (drag == Drag.NONE) {
            return;
        }
        if (ImGui.isMouseDown(dragButton)) {
            dragUpdate(st, view, mx, my);
        } else {
            dragEnd(st, view, mx, my);
        }
    }

    private void begin(Drag d, int button) {
        drag = d;
        dragButton = button;
        dragToken = "d" + (++dragSeq);
        accX = 0;
        accY = 0;
    }

    private void leftPress(GraphEditorState st, GraphCanvasView view, double mx, double my) {
        GraphEditor ed = st.editor;
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean alt = ImGui.getIO().getKeyAlt();
        if (spaceHeld) {
            begin(Drag.PAN, ImGuiMouseButton.Left);
            return;
        }
        PinHit pin = view.hitPin(scene.boxes(), mx, my, PIN_HIT_PX);
        if (pin != null) {
            Pin p = pin(pin);
            if (alt) {
                ed.disconnectPort(st.body, pin.node(), p.spec().name());
                return;
            }
            linkNode = pin.node();
            linkPort = p.spec();
            linkOutput = pin.output();
            begin(Drag.LINK, ImGuiMouseButton.Left);
            return;
        }
        NodeBox hit = view.hitNode(scene.boxes(), mx, my);
        if (hit != null) {
            st.selectedComment = null;
            if (ctrl && st.selection.contains(hit.id())) {
                st.selection.removeAll(withGroups(ed, st.body, hit.id()));
                return;
            }
            if (!st.selection.contains(hit.id())) {
                if (!ctrl) {
                    st.selection.clear();
                }
                st.selection.addAll(withGroups(ed, st.body, hit.id()));
                st.selectionDirty = true;
            }
            begin(Drag.NODES, ImGuiMouseButton.Left);
            return;
        }
        CommentHit ch = view.hitComment(commentsOf(ed, st.body), mx, my);
        if (ch != null) {
            st.selection.clear();
            st.selectedComment = ch.comment().id();
            commentId = ch.comment().id();
            begin(ch.part() == CommentPart.RESIZE ? Drag.COMMENT_RESIZE : Drag.COMMENT_MOVE, ImGuiMouseButton.Left);
            return;
        }
        if (alt) {
            int li = linkAt(view, mx, my);
            if (li >= 0) {
                ed.disconnect(st.body, scene.links().get(li).edge());
                return;
            }
        }
        boxBase = ctrl ? new HashSet<>(st.selection) : Set.of();
        if (!ctrl) {
            st.selection.clear();
            st.selectedComment = null;
        }
        boxX = mx;
        boxY = my;
        begin(Drag.BOX, ImGuiMouseButton.Left);
    }

    private void rightPress(GraphEditorState st, GraphCanvasView view, double mx, double my) {
        NodeBox hit = view.hitNode(scene.boxes(), mx, my);
        if (hit != null) {
            if (!st.selection.contains(hit.id())) {
                st.selection.clear();
                st.selection.addAll(withGroups(st.editor, st.body, hit.id()));
                st.selectionDirty = true;
            }
            st.selectedComment = null;
            menuNode = hit.id();
            ImGui.openPopup(NODE_MENU);
            return;
        }
        CommentHit ch = view.hitComment(commentsOf(st.editor, st.body), mx, my);
        if (ch != null) {
            st.selection.clear();
            st.selectedComment = ch.comment().id();
            menuComment = ch.comment().id();
            ImGui.openPopup(COMMENT_MENU);
            return;
        }
        palette.openAt(view.toGraphX(mx), view.toGraphY(my), null);
    }

    private void dragUpdate(GraphEditorState st, GraphCanvasView view, double mx, double my) {
        GraphEditor ed = st.editor;
        double dx = ImGui.getIO().getMouseDeltaX();
        double dy = ImGui.getIO().getMouseDeltaY();
        switch (drag) {
            case PAN -> view.pan(dx, dy);
            case NODES -> {
                accX += dx / view.zoom();
                accY += dy / view.zoom();
                long ix = Math.round(accX);
                long iy = Math.round(accY);
                if (ix != 0 || iy != 0) {
                    ed.moveNodes(st.body, st.selection, ix, iy, dragToken);
                    accX -= ix;
                    accY -= iy;
                }
            }
            case COMMENT_MOVE -> {
                accX += dx / view.zoom();
                accY += dy / view.zoom();
                long ix = Math.round(accX);
                long iy = Math.round(accY);
                if ((ix != 0 || iy != 0) && commentExists(ed, commentId)) {
                    ed.moveComment(commentId, ix, iy, dragToken);
                    accX -= ix;
                    accY -= iy;
                }
            }
            case COMMENT_RESIZE -> {
                GraphLayout.Comment c = comment(ed, commentId);
                if (c != null) {
                    ed.editComment(c.id(), c.x(), c.y(), view.toGraphX(mx) - c.x(), view.toGraphY(my) - c.y(),
                        c.text(), c.color(), dragToken);
                }
            }
            case BOX -> {
                Set<String> sel = new LinkedHashSet<>(boxBase);
                for (String id : view.boxSelect(scene.boxes(), boxX, boxY, mx, my)) {
                    sel.addAll(withGroups(ed, st.body, id));
                }
                st.selection.clear();
                st.selection.addAll(sel);
                st.selectionDirty = true;
            }
            default -> { }
        }
    }

    private void dragEnd(GraphEditorState st, GraphCanvasView view, double mx, double my) {
        GraphEditor ed = st.editor;
        Drag finished = drag;
        drag = Drag.NONE;
        if (finished == Drag.NODES || finished == Drag.COMMENT_MOVE || finished == Drag.COMMENT_RESIZE) {
            ed.endDrag();
        } else if (finished == Drag.LINK) {
            PinHit target = view.hitPin(scene.boxes(), mx, my, PIN_HIT_PX);
            if (target != null) {
                if (!target.node().equals(linkNode) || target.output() != linkOutput) {
                    String why = ed.connect(st.body, linkNode, linkPort.name(), target.node(), pin(target).spec().name());
                    st.status = why == null ? "" : why;
                }
            } else if (view.hitNode(scene.boxes(), mx, my) == null) {
                palette.openAt(view.toGraphX(mx), view.toGraphY(my), new GraphPalettePopup.LinkSource(linkNode,
                    linkPort, linkOutput));
            }
        }
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    private void keyboard(GraphEditorState st, GraphCanvasView view, boolean hovered, double mx, double my) {
        GraphEditor ed = st.editor;
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean shift = ImGui.getIO().getKeyShift();
        if (ImGui.isKeyPressed(ImGuiKey.Delete) || ImGui.isKeyPressed(ImGuiKey.Backspace)) {
            deleteSelection(st);
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z)) {
            if (shift) {
                ed.redo();
            } else {
                ed.undo();
            }
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y)) {
            ed.redo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.C) && !st.selection.isEmpty()) {
            ImGui.setClipboardText(ed.copy(st.body, st.selection));
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.V)) {
            double gx = hovered ? view.toGraphX(mx) : view.toGraphX(cw / 2);
            double gy = hovered ? view.toGraphY(my) : view.toGraphY(ch / 2);
            List<String> ids = ed.paste(st.body, ImGui.getClipboardText(), gx, gy);
            if (!ids.isEmpty()) {
                st.selection.clear();
                st.selection.addAll(ids);
                st.selectionDirty = true;
            }
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.D) && !st.selection.isEmpty()) {
            List<String> ids = ed.duplicate(st.body, st.selection);
            if (!ids.isEmpty()) {
                st.selection.clear();
                st.selection.addAll(ids);
            }
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.A)) {
            st.selection.clear();
            ed.nodes(st.body).forEach(n -> st.selection.add(n.id()));
            st.selectionDirty = true;
        } else if (!ctrl && ImGui.isKeyPressed(ImGuiKey.F9) && st.single() != null) {
            st.toggleBreakpoint(st.single());
        } else if (!ctrl && ImGui.isKeyPressed(ImGuiKey.F)) {
            frameSelection(st, view);
        }
        if (ImGui.isKeyPressed(ImGuiKey.F9) && st.selection.size() > 1) {
            st.selection.forEach(st::toggleBreakpoint);
        }
    }

    private void frameSelection(GraphEditorState st, GraphCanvasView view) {
        List<NodeBox> boxes = new ArrayList<>();
        for (String id : st.selection) {
            NodeView n = scene.node(id);
            if (n != null) {
                boxes.add(n.box());
            }
        }
        view.frameBoxes(boxes.isEmpty() ? scene.boxes() : boxes, cw, ch);
    }

    void deleteSelection(GraphEditorState st) {
        if (st.selectedComment != null) {
            st.editor.removeComment(st.selectedComment);
            st.selectedComment = null;
        }
        if (!st.selection.isEmpty()) {
            st.editor.removeNodes(st.body, new ArrayList<>(st.selection));
            st.selection.clear();
        }
    }

    // ── menus ───────────────────────────────────────────────────────────────

    private void nodeMenu(GraphEditorState st, GraphCanvasView view) {
        if (!ImGui.beginPopup(NODE_MENU)) {
            return;
        }
        GraphEditor ed = st.editor;
        if (ImGui.menuItem("Delete", "Del")) {
            deleteSelection(st);
        }
        if (ImGui.menuItem("Duplicate", "Ctrl+D")) {
            List<String> ids = ed.duplicate(st.body, st.selection);
            st.selection.clear();
            st.selection.addAll(ids);
        }
        if (ImGui.menuItem("Copy", "Ctrl+C")) {
            ImGui.setClipboardText(ed.copy(st.body, st.selection));
        }
        ImGui.separator();
        if (ImGui.menuItem("Group selection") && !st.selection.isEmpty()) {
            ed.group(st.body, st.selection, "Group");
        }
        if (menuNode != null && ImGui.menuItem("Ungroup")) {
            for (GraphLayout.Group g : ed.groupsOf(st.body, menuNode)) {
                ed.ungroup(g.id());
            }
        }
        if (menuNode != null && ed.node(st.body, menuNode) != null) {
            List<String> targets = ed.elementTargets(st.body, menuNode);
            if (!targets.isEmpty()) {
                ImGui.separator();
                for (String t : targets) {
                    if (ImGui.menuItem("Jump to element " + t)) {
                        st.jumpToElement.accept(t);
                    }
                }
            }
            ImGui.separator();
            if (ImGui.menuItem(st.hasBreakpoint(menuNode) ? "Remove breakpoint" : "Toggle breakpoint", "F9")) {
                st.toggleBreakpoint(menuNode);
            }
        }
        ImGui.endPopup();
    }

    private void commentMenu(GraphEditorState st) {
        if (!ImGui.beginPopup(COMMENT_MENU)) {
            return;
        }
        if (menuComment != null && ImGui.menuItem("Delete comment")) {
            st.editor.removeComment(menuComment);
            st.selectedComment = null;
        }
        ImGui.textDisabled("Edit the text in the Inspector");
        ImGui.endPopup();
    }

    // ── paint ───────────────────────────────────────────────────────────────

    private void paint(GraphEditorState st, GraphCanvasView view, boolean hovered, double mx, double my) {
        ImDrawList dl = ImGui.getWindowDrawList();
        GraphEditor ed = st.editor;
        dl.pushClipRect(ox, oy, ox + cw, oy + ch, true);
        dl.addRectFilled(ox, oy, ox + cw, oy + ch, ThemeColors.u32(ImGuiCol.FrameBg, 1f));
        grid(dl, view);

        Map<String, List<GraphDiagnostic>> problems = new HashMap<>();
        for (GraphDiagnostic d : ed.diagnostics()) {
            if (d.function().equals(st.body) && !d.node().isEmpty()) {
                problems.computeIfAbsent(d.node(), k -> new ArrayList<>()).add(d);
            }
        }
        GraphDebugger dbg = st.debugger;
        Set<String> paused = new HashSet<>();
        if (dbg != null) {
            for (GraphDebugger.Paused p : dbg.paused()) {
                if (p.graph().equals(st.graphId())) {
                    paused.add(p.node());
                }
            }
        }

        comments(dl, st, view, ed);
        groups(dl, st, view, ed);
        PinHit hoverPin = hovered && drag == Drag.NONE ? view.hitPin(scene.boxes(), mx, my, PIN_HIT_PX) : null;
        int hoverLink = hovered && drag == Drag.NONE && hoverPin == null && view.hitNode(scene.boxes(), mx, my) == null
            ? linkAt(view, mx, my) : -1;
        links(dl, st, view, hoverLink);
        NodeView tipNode = null;
        for (NodeView n : scene.nodes()) {
            node(dl, st, view, n, problems.getOrDefault(n.id(), List.of()), dbg, paused);
            if (hovered && drag == Drag.NONE && hoverPin == null && n.box().contains(view.toGraphX(mx), view.toGraphY(my))) {
                tipNode = n;
            }
        }
        if (drag == Drag.LINK) {
            linkPreview(dl, st, view, mx, my);
        } else if (drag == Drag.BOX) {
            dl.addRectFilled(ox + (float) Math.min(boxX, mx), oy + (float) Math.min(boxY, my),
                ox + (float) Math.max(boxX, mx), oy + (float) Math.max(boxY, my), ThemeColors.u32(ImGuiCol.CheckMark, 0.15f));
            dl.addRect(ox + (float) Math.min(boxX, mx), oy + (float) Math.min(boxY, my),
                ox + (float) Math.max(boxX, mx), oy + (float) Math.max(boxY, my), ThemeColors.u32(ImGuiCol.CheckMark, 0.8f));
        }
        dl.popClipRect();

        if (hoverPin != null) {
            pinTooltip(st, hoverPin);
        } else if (tipNode != null) {
            List<GraphDiagnostic> ds = problems.getOrDefault(tipNode.id(), List.of());
            if (!ds.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                ds.forEach(d -> sb.append(d.severity()).append(": ").append(d.message()).append('\n'));
                ImGui.setTooltip(sb.toString().stripTrailing());
            }
        }
    }

    private void grid(ImDrawList dl, GraphCanvasView view) {
        double step = 32 * view.zoom();
        if (step < 8) {
            step *= 5;
        }
        int minor = ThemeColors.u32(ImGuiCol.Border, 0.18f);
        int major = ThemeColors.u32(ImGuiCol.Border, 0.35f);
        double startX = ((view.panX() % step) + step) % step;
        double startY = ((view.panY() % step) + step) % step;
        for (double x = startX; x < cw; x += step) {
            long idx = Math.round((x - view.panX()) / step);
            dl.addLine(ox + (float) x, oy, ox + (float) x, oy + ch, idx % 5 == 0 ? major : minor);
        }
        for (double y = startY; y < ch; y += step) {
            long idx = Math.round((y - view.panY()) / step);
            dl.addLine(ox, oy + (float) y, ox + cw, oy + (float) y, idx % 5 == 0 ? major : minor);
        }
    }

    private void comments(ImDrawList dl, GraphEditorState st, GraphCanvasView view, GraphEditor ed) {
        double z = view.zoom();
        for (GraphLayout.Comment c : commentsOf(ed, st.body)) {
            float x1 = sx(view, c.x());
            float y1 = sy(view, c.y());
            float x2 = sx(view, c.x() + c.width());
            float y2 = sy(view, c.y() + c.height());
            int tint = GraphColors.parseHex(c.color(), 1f);
            int base = tint != 0 ? tint : ThemeColors.u32(ImGuiCol.Header, 1f);
            dl.addRectFilled(x1, y1, x2, y2, fade(base, 0.16f), 6 * (float) z);
            dl.addRectFilled(x1, y1, x2, y1 + (float) (GraphCanvasView.COMMENT_TITLE * z), fade(base, 0.38f), 6 * (float) z,
                ImDrawFlags.RoundCornersTop);
            boolean sel = c.id().equals(st.selectedComment);
            dl.addRect(x1, y1, x2, y2, sel ? ThemeColors.u32(ImGuiCol.CheckMark, 1f) : fade(base, 0.6f), 6 * (float) z, 0,
                sel ? 2.5f : 1.2f);
            if (z >= 0.35) {
                text(dl, z, x1 + 8, y1 + (float) ((GraphCanvasView.COMMENT_TITLE * z - ImGui.getFontSize() * z) / 2),
                    ThemeColors.u32(ImGuiCol.Text, 0.95f), c.text());
            }
            float g = (float) (GraphCanvasView.COMMENT_GRIP * z * 0.8);
            dl.addTriangleFilled(x2 - g, y2 - 2, x2 - 2, y2 - 2, x2 - 2, y2 - g, ThemeColors.u32(ImGuiCol.Text, 0.4f));
        }
    }

    private void groups(ImDrawList dl, GraphEditorState st, GraphCanvasView view, GraphEditor ed) {
        double z = view.zoom();
        for (GraphLayout.Group g : ed.layout().groups()) {
            if (!g.function().equals(st.body)) {
                continue;
            }
            double x1 = Double.MAX_VALUE;
            double y1 = Double.MAX_VALUE;
            double x2 = -Double.MAX_VALUE;
            double y2 = -Double.MAX_VALUE;
            for (String id : g.nodes()) {
                NodeView n = scene.node(id);
                if (n != null) {
                    x1 = Math.min(x1, n.box().x());
                    y1 = Math.min(y1, n.box().y());
                    x2 = Math.max(x2, n.box().x() + n.box().width());
                    y2 = Math.max(y2, n.box().y() + n.box().height());
                }
            }
            if (x1 > x2) {
                continue;
            }
            float pad = 10;
            int col = ThemeColors.u32(ImGuiCol.CheckMark, 0.55f);
            dl.addRect(sx(view, x1) - pad, sy(view, y1) - pad - 14, sx(view, x2) + pad, sy(view, y2) + pad, col,
                8 * (float) z, 0, 1.5f);
            if (z >= 0.4) {
                text(dl, z, sx(view, x1) - pad + 6, sy(view, y1) - pad - 12, col, g.title());
            }
        }
    }

    private void links(ImDrawList dl, GraphEditorState st, GraphCanvasView view, int hoverLink) {
        double z = Math.clamp(view.zoom(), 0.7, 1.5);
        List<LinkView> links = scene.links();
        for (int i = 0; i < links.size(); i++) {
            LinkView l = links.get(i);
            boolean touched = st.selection.contains(l.edge().fromNode()) || st.selection.contains(l.edge().toNode());
            boolean hot = i == hoverLink;
            int col = l.exec() ? ThemeColors.u32(ImGuiCol.Text, touched || hot ? 1f : 0.7f)
                : GraphColors.pin(l.fromPort().type(), touched || hot ? 1f : 0.8f);
            float thick = (float) ((l.exec() ? 3.0 : 2.0) * z) + (hot ? 1.5f : touched ? 0.8f : 0);
            GraphCanvasView.Bezier b = l.curve();
            dl.addBezierCubic(sx(view, b.x0()), sy(view, b.y0()), sx(view, b.x1()), sy(view, b.y1()),
                sx(view, b.x2()), sy(view, b.y2()), sx(view, b.x3()), sy(view, b.y3()), col, thick);
        }
    }

    private void linkPreview(ImDrawList dl, GraphEditorState st, GraphCanvasView view, double mx, double my) {
        NodeView from = scene.node(linkNode);
        if (from == null) {
            return;
        }
        Pin start = linkOutput ? pin(from.outputs(), linkPort.name()) : pin(from.inputs(), linkPort.name());
        double[] a = from.box().anchor(linkOutput, start.index());
        double ex = view.toGraphX(mx);
        double ey = view.toGraphY(my);
        PinHit target = view.hitPin(scene.boxes(), mx, my, PIN_HIT_PX);
        String why = null;
        if (target != null) {
            double[] t = scene.node(target.node()).box().anchor(target.output(), target.index());
            ex = t[0];
            ey = t[1];
            why = st.editor.canConnect(st.body, linkNode, linkPort.name(), target.node(), pin(target).spec().name());
        }
        GraphCanvasView.Bezier b = linkOutput ? GraphCanvasView.Bezier.between(a[0], a[1], ex, ey)
            : GraphCanvasView.Bezier.between(ex, ey, a[0], a[1]);
        int col = why != null ? ThemeColors.u32(Tone.ERROR, 1f)
            : linkPort.isExec() ? ThemeColors.u32(ImGuiCol.Text, 0.9f) : GraphColors.pin(linkPort.type(), 0.9f);
        dl.addBezierCubic(sx(view, b.x0()), sy(view, b.y0()), sx(view, b.x1()), sy(view, b.y1()), sx(view, b.x2()),
            sy(view, b.y2()), sx(view, b.x3()), sy(view, b.y3()), col, linkPort.isExec() ? 3.2f : 2.2f);
        if (why != null) {
            ImGui.setTooltip(why);
        }
    }

    private void node(ImDrawList dl, GraphEditorState st, GraphCanvasView view, NodeView n, List<GraphDiagnostic> problems,
                      GraphDebugger dbg, Set<String> paused) {
        double z = view.zoom();
        NodeBox b = n.box();
        float x1 = sx(view, b.x());
        float y1 = sy(view, b.y());
        float x2 = sx(view, b.x() + b.width());
        float y2 = sy(view, b.y() + b.height());
        if (x2 < ox - 10 || y2 < oy - 30 || x1 > ox + cw + 10 || y1 > oy + ch + 10) {
            return; // off screen
        }
        float r = 6 * (float) z;
        float hdr = (float) (GraphCanvasView.HEADER * z);
        boolean selected = st.selection.contains(n.id());
        boolean hasError = problems.stream().anyMatch(GraphDiagnostic::isError);
        String loc = st.loc(n.id());

        GraphDebugger.Hit hit = dbg == null ? null : dbg.hit(st.graphId(), loc);
        double age = hit == null ? Double.MAX_VALUE : st.time - hit.time();
        if (age >= 0 && age <= GLOW_SECONDS) {
            float a = (float) (1 - age / GLOW_SECONDS);
            dl.addRectFilled(x1 - 6, y1 - 6, x2 + 6, y2 + 6, ThemeColors.u32(Tone.SUCCESS, 0.28f * a), r + 5);
            dl.addRect(x1 - 3, y1 - 3, x2 + 3, y2 + 3, ThemeColors.u32(Tone.SUCCESS, 0.9f * a), r + 3, 0, 2.5f);
        }
        dl.addRectFilled(x1 + 2, y1 + 3, x2 + 2, y2 + 3, GraphColors.rgba(0f, 0f, 0f, 0.20f), r);
        dl.addRectFilled(x1, y1, x2, y2, ThemeColors.u32(ImGuiCol.PopupBg, 0.97f), r);
        int hdrCol = n.role() == Role.UNKNOWN ? GraphColors.neutral()
            : n.role() == Role.PURE ? fade(GraphColors.category(n.category(), false), 0.85f)
            : GraphColors.category(n.category(), n.role() == Role.EVENT);
        dl.addRectFilled(x1, y1, x2, y1 + hdr, hdrCol, r, ImDrawFlags.RoundCornersTop);
        dl.addRect(x1, y1, x2, y2, hasError ? ThemeColors.u32(Tone.ERROR, 1f) : ThemeColors.u32(ImGuiCol.Border, 0.9f), r, 0,
            hasError ? 2.5f : 1f);
        if (selected) {
            dl.addRect(x1 - 2, y1 - 2, x2 + 2, y2 + 2, ThemeColors.u32(ImGuiCol.CheckMark, 1f), r + 2, 0, 2.5f);
        }
        if (z >= 0.3) {
            text(dl, z, x1 + 10 * (float) z, y1 + (hdr - (float) (ImGui.getFontSize() * z)) / 2, GraphColors.headerText(),
                n.title());
        }
        pins(dl, view, n, z, x1, x2);
        badges(dl, st, n, z, x1, y1, x2, hdr, problems, hit, loc, paused.contains(loc));
    }

    private void pins(ImDrawList dl, GraphCanvasView view, NodeView n, double z, float x1, float x2) {
        float pr = (float) Math.max(3, GraphCanvasView.PIN_RADIUS * z * 0.85);
        boolean labels = z >= 0.45;
        int dim = ThemeColors.u32(ImGuiCol.Text, 0.9f);
        for (Pin p : n.inputs()) {
            double[] a = n.box().anchor(false, p.index());
            drawPin(dl, p, sx(view, a[0]), sy(view, a[1]), pr);
            if (labels) {
                text(dl, z, x1 + 12 * (float) z, sy(view, a[1]) - (float) (ImGui.getFontSize() * z / 2), dim, p.label());
            }
        }
        for (Pin p : n.outputs()) {
            double[] a = n.box().anchor(true, p.index());
            drawPin(dl, p, sx(view, a[0]), sy(view, a[1]), pr);
            if (labels) {
                float w = (float) (widths.computeIfAbsent(p.label(), t -> (double) ImGui.calcTextSize(t).x) * z);
                text(dl, z, x2 - 12 * (float) z - w, sy(view, a[1]) - (float) (ImGui.getFontSize() * z / 2), dim, p.label());
            }
        }
    }

    private static void drawPin(ImDrawList dl, Pin p, float cx, float cy, float r) {
        if (p.spec().isExec()) {
            int col = ThemeColors.u32(ImGuiCol.Text, 0.95f);
            if (p.connected()) {
                dl.addTriangleFilled(cx - r * 0.8f, cy - r, cx - r * 0.8f, cy + r, cx + r, cy, col);
            } else {
                dl.addTriangle(cx - r * 0.8f, cy - r, cx - r * 0.8f, cy + r, cx + r, cy, col, 1.6f);
            }
            return;
        }
        int col = GraphColors.pin(p.spec().type(), 1f);
        if (p.connected()) {
            dl.addCircleFilled(cx, cy, r, col);
        } else {
            dl.addCircle(cx, cy, r, col, 0, 1.8f);
        }
    }

    private void badges(ImDrawList dl, GraphEditorState st, NodeView n, double z, float x1, float y1, float x2, float hdr,
                        List<GraphDiagnostic> problems, GraphDebugger.Hit hit, String loc, boolean paused) {
        float cy = y1 + hdr / 2;
        float br = Math.max(4, 8 * (float) z);
        if (!problems.isEmpty()) {
            Tone tone = problems.stream().anyMatch(GraphDiagnostic::isError) ? Tone.ERROR : Tone.WARNING;
            dl.addCircleFilled(x2 - br - 4, cy, br, ThemeColors.u32(tone, 1f));
            if (z >= 0.5) {
                text(dl, z * 0.9, x2 - br - 4 - (float) (3 * z), cy - (float) (ImGui.getFontSize() * z * 0.9 / 2),
                    GraphColors.rgba(0.08f, 0.08f, 0.1f, 1f), "!");
            }
        }
        if (hit != null && z >= 0.45) {
            String s = "x" + hit.count();
            float w = (float) (ImGui.calcTextSize(s).x * z * 0.85) + 8;
            float px2 = x2 - 2;
            float py2 = y1 - 3;
            dl.addRectFilled(px2 - w, py2 - 14 * (float) z, px2, py2, ThemeColors.surfaceU32(Tone.SUCCESS, 0.5f, 0.95f),
                7 * (float) z);
            text(dl, z * 0.85, px2 - w + 4, py2 - 13 * (float) z, ThemeColors.u32(ImGuiCol.Text, 1f), s);
        }
        if (st.hasBreakpoint(n.id())) {
            dl.addCircleFilled(x1 - 2, cy, Math.max(4, 5.5f * (float) z), ThemeColors.u32(Tone.ERROR, 1f));
            dl.addCircle(x1 - 2, cy, Math.max(4, 5.5f * (float) z), GraphColors.rgba(1f, 1f, 1f, 0.8f), 0, 1.2f);
        }
        if (paused) {
            float y2 = y1 + (float) (n.box().height() * z);
            dl.addRect(x1 - 4, y1 - 4, x2 + 4, y2 + 4, ThemeColors.u32(Tone.WARNING, 1f), 8 * (float) z, 0, 3f);
            text(dl, z, x1, y1 - 18 * (float) z - 2, ThemeColors.u32(Tone.WARNING, 1f), "PAUSED HERE");
        }
    }

    private void pinTooltip(GraphEditorState st, PinHit hit) {
        NodeView n = scene.node(hit.node());
        if (n == null) {
            return;
        }
        Pin p = pin(hit);
        StringBuilder sb = new StringBuilder(p.spec().name()).append(" : ").append(GraphEditor.typeName(p.spec().type()));
        if (!p.spec().doc().isEmpty()) {
            sb.append('\n').append(p.spec().doc());
        }
        if (hit.output() && !p.spec().isExec() && st.debugger != null) {
            UiValue v = st.debugger.value(st.graphId(), st.loc(hit.node()), p.spec().name());
            sb.append("\nlast value: ").append(v == null ? "(not run yet)" : GraphValues.editText(v).isEmpty()
                ? GraphValues.compact(v) : GraphValues.editText(v));
        }
        ImGui.setTooltip(sb.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Pin pin(PinHit h) {
        NodeView n = scene.node(h.node());
        return (h.output() ? n.outputs() : n.inputs()).get(h.index());
    }

    private static Pin pin(List<Pin> pins, String name) {
        for (Pin p : pins) {
            if (p.spec().name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private int linkAt(GraphCanvasView view, double mx, double my) {
        List<GraphCanvasView.Bezier> curves = new ArrayList<>();
        for (LinkView l : scene.links()) {
            curves.add(l.curve());
        }
        return view.hitLink(curves, mx, my, LINK_HIT_PX);
    }

    private static List<String> withGroups(GraphEditor ed, String body, String id) {
        List<String> out = new ArrayList<>();
        out.add(id);
        for (GraphLayout.Group g : ed.groupsOf(body, id)) {
            out.addAll(g.nodes());
        }
        return out;
    }

    private static List<GraphLayout.Comment> commentsOf(GraphEditor ed, String body) {
        return ed.layout().comments().stream().filter(c -> c.function().equals(body)).toList();
    }

    private static GraphLayout.Comment comment(GraphEditor ed, String id) {
        return ed.layout().comments().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean commentExists(GraphEditor ed, String id) {
        return id != null && comment(ed, id) != null;
    }

    private float sx(GraphCanvasView v, double gx) {
        return ox + (float) v.toScreenX(gx);
    }

    private float sy(GraphCanvasView v, double gy) {
        return oy + (float) v.toScreenY(gy);
    }

    private static void text(ImDrawList dl, double zoom, float x, float y, int col, String s) {
        int size = Math.max(6, (int) Math.round(ImGui.getFontSize() * zoom));
        dl.addText(ImGui.getFont(), size, x, y, col, s);
    }

    /** Scales a packed color's alpha. */
    private static int fade(int abgr, float scale) {
        int a = (int) (((abgr >>> 24) & 0xFF) * scale);
        return (abgr & 0x00FFFFFF) | (Math.clamp(a, 0, 255) << 24);
    }
}
