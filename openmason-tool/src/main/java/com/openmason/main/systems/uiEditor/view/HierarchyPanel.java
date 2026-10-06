package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The element tree, Unreal-style: type glyphs, names, binding and problem badges, per-row
 * designer visibility and lock, inline rename, multi-select (Ctrl/Shift), drag and drop to
 * reorder and reparent (above / inside / below zones), component slots as their own drop
 * targets, and a read-only view of each instance's component internals for override inspection.
 */
final class HierarchyPanel {

    static final String TITLE = "Hierarchy###uiHierarchy";
    static final String NODE_PAYLOAD = "OM_UI_NODE";

    private enum Kind { NODE, SLOT, INTERNAL }

    private record Row(Kind kind, String key, int depth, String type, String label, String detail, boolean expandable,
                       boolean expanded, String instanceId, String slot) {
    }

    private final UiEditorContext ctx;
    private final ImString search = new ImString(64);
    private final ImString renameBuf = new ImString(64);
    private String renaming;
    private boolean renameFocus;
    private String anchorKey;
    private List<String> lastSelection = List.of();
    private boolean scrollToSelection;

    HierarchyPanel(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            EditorWidgets.emptyState("No document", "Open or create a UI document to see its elements.");
            ImGui.end();
            return;
        }
        if (ctx.renameRequest != null) {
            startRename(doc, ctx.renameRequest);
            ctx.renameRequest = null;
        }
        if (!doc.selection().equals(lastSelection)) {
            lastSelection = doc.selection();
            scrollToSelection = true;
            revealSelection(doc);
        }
        EditorWidgets.searchField("##hierSearch", search, "Search elements");
        ImGui.spacing();
        List<Row> rows = rows(doc);
        Map<String, UiRuntimeDiagnostic.Severity> problems = problems();
        boolean focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, ImGui.getStyle().getItemSpacingX(), 0f);
        if (ImGui.beginChild("##hierRows")) {
            for (Row r : rows) {
                row(doc, r, rows, problems);
            }
            ImGui.dummy(0, 40); // drop room below the last row (appends to the root)
            if (ImGui.beginDragDropTarget()) {
                acceptDrop(doc, NodeLocation.endOf(doc.archive().document().root().id()));
                ImGui.endDragDropTarget();
            }
        }
        ImGui.endChild();
        ImGui.popStyleVar();
        if (focused && !ImGui.getIO().getWantTextInput() && renaming == null) {
            keyboard(doc, rows);
        }
        scrollToSelection = false;
        ImGui.end();
    }

    // ── rows ────────────────────────────────────────────────────────────────

    private List<Row> rows(UiEditorDocument doc) {
        List<Row> out = new ArrayList<>();
        DocumentViewState v = ctx.view();
        String q = search.get().trim().toLowerCase(Locale.ROOT);
        UiNode root = doc.archive().document().root();
        if (!q.isEmpty()) {
            for (UiNode n : root.flatten()) {
                if (matches(n, q)) {
                    out.add(nodeRow(n, 0, false, false));
                }
            }
            return out;
        }
        addNode(out, root, 0, v);
        return out;
    }

    private static boolean matches(UiNode n, String q) {
        return n.id().toLowerCase(Locale.ROOT).contains(q) || n.name() != null && n.name().toLowerCase(Locale.ROOT).contains(q)
            || n.type().toLowerCase(Locale.ROOT).contains(q) || n.classes().stream().anyMatch(c -> c.contains(q))
            || n.instance() != null && n.instance().component().contains(q);
    }

    private Row nodeRow(UiNode n, int depth, boolean expandable, boolean expanded) {
        String label = n.name() != null ? n.name() : n.id();
        String detail = n.instance() != null ? stem(n.instance().component()) : n.type();
        if (n.props().get("text") instanceof com.openmason.engine.format.omui.UiValue.Str s && !s.value().isEmpty()) {
            detail = detail + "  \"" + (s.value().length() > 18 ? s.value().substring(0, 18) + "..." : s.value()) + "\"";
        }
        return new Row(Kind.NODE, n.id(), depth, n.type(), label, detail, expandable, expanded, null, null);
    }

    private void addNode(List<Row> out, UiNode n, int depth, DocumentViewState v) {
        boolean hasKids = !n.children().isEmpty() || n.instance() != null;
        boolean expanded = hasKids && !v.collapsed.contains(n.id());
        out.add(nodeRow(n, depth, hasKids, expanded));
        if (!expanded) {
            return;
        }
        if (n.instance() != null) {
            for (String slot : slotNames(n)) {
                out.add(new Row(Kind.SLOT, n.id() + "#slot:" + slot, depth + 1, "Slot", slot, "slot", false, true, n.id(),
                    slot));
                for (UiNode c : n.instance().slots().getOrDefault(slot, List.of())) {
                    addNode(out, c, depth + 2, v);
                }
            }
            UiElement el = ctx.element(n.id());
            if (el != null && v.showInternals.contains(n.id())) {
                for (UiElement c : el.children()) {
                    addInternal(out, c, depth + 1, n.id());
                }
            }
            return;
        }
        for (UiNode c : n.children()) {
            addNode(out, c, depth + 1, v);
        }
    }

    private void addInternal(List<Row> out, UiElement el, int depth, String instanceId) {
        if (el.key().indexOf('/') < 0) {
            return; // slot content authored by this document: listed under its slot
        }
        String label = el.name() != null ? el.name() : el.id();
        out.add(new Row(Kind.INTERNAL, el.key(), depth, el.type(), label, el.type(), false, true, instanceId, null));
        for (UiElement c : el.children()) {
            addInternal(out, c, depth + 1, instanceId);
        }
    }

    /** Slots the component declares plus any slot the instance fills. */
    private List<String> slotNames(UiNode n) {
        Set<String> names = new LinkedHashSet<>();
        UiDocumentInstance ui = ctx.instance();
        if (ui != null) {
            OmuiArchive comp = ui.context().source().component(n.instance().component());
            UiDocument.ComponentDef def = comp == null ? null : comp.document().component();
            if (def != null) {
                def.slots().forEach(s -> names.add(s.name()));
            }
        }
        names.addAll(n.instance().slots().keySet());
        return new ArrayList<>(names);
    }

    private Map<String, UiRuntimeDiagnostic.Severity> problems() {
        Map<String, UiRuntimeDiagnostic.Severity> out = new HashMap<>();
        UiDocumentInstance ui = ctx.instance();
        if (ui == null) {
            return out;
        }
        for (UiRuntimeDiagnostic d : ui.diagnostics()) {
            if (d.severity() == UiRuntimeDiagnostic.Severity.INFO || d.element() == null || d.element().isEmpty()) {
                continue;
            }
            String key = d.element();
            out.merge(key, d.severity(), (a, b) -> a == UiRuntimeDiagnostic.Severity.ERROR ? a : b);
        }
        return out;
    }

    private void row(UiEditorDocument doc, Row r, List<Row> rows, Map<String, UiRuntimeDiagnostic.Severity> problems) {
        DocumentViewState v = ctx.view();
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getTextLineHeight() + 8;
        float w = ImGui.getContentRegionAvailX();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float indent = 14f;
        float ix = x + 4 + r.depth() * indent;
        boolean selected = r.kind() != Kind.SLOT && doc.isSelected(r.key());
        boolean hoveredFromCanvas = r.key().equals(ctx.hoverKey);

        ImGui.pushID(r.key());
        ImGui.invisibleButton("##row", w, h);
        boolean hovered = ImGui.isItemHovered();
        if (hovered && r.kind() != Kind.SLOT) {
            ctx.hover(r.key());
        }
        if (scrollToSelection && selected && r.key().equals(doc.primary())) {
            ImGui.setScrollHereY(0.5f);
        }

        // background
        if (selected) {
            dl.addRectFilled(x, y, x + w, y + h, EditorWidgets.accent(ImGui.isWindowFocused() ? 0.42f : 0.26f), 3f);
        } else if (hovered || hoveredFromCanvas) {
            dl.addRectFilled(x, y, x + w, y + h, EditorWidgets.accent(0.12f), 3f);
        }
        // indent guides
        for (int i = 1; i <= r.depth(); i++) {
            float gx = x + 4 + i * indent - indent / 2f;
            dl.addLine(gx, y, gx, y + h, EditorWidgets.border(0.35f), 1f);
        }
        // chevron
        float cs = h * 0.55f;
        if (r.expandable()) {
            boolean overChevron = ImGui.isMouseHoveringRect(ix, y, ix + cs + 2, y + h);
            Glyphs.chevron(dl, ix, y + (h - cs) / 2f, cs, overChevron ? EditorWidgets.text(1f) : EditorWidgets.dim(1f),
                r.expanded());
            if (overChevron && ImGui.isItemClicked()) {
                toggleCollapsed(v, r.key());
                ImGui.popID();
                return;
            }
        }
        float gx = ix + cs + 2;
        float gs = h - 8;
        boolean dimmed = r.kind() == Kind.INTERNAL || v.hidden.contains(r.key());
        if (r.kind() == Kind.SLOT) {
            Glyphs.dashedRect(dl, gx + 1, y + 5, gx + gs - 1, y + h - 5, Glyphs.typeColor("Instance", 0.8f), 1f);
        } else {
            Glyphs.widget(dl, r.type(), gx, y + 4, gs, Glyphs.typeColor(r.type(), dimmed ? 0.55f : 1f));
        }
        float tx = gx + gs + 6;
        float ty = y + (h - ImGui.getTextLineHeight()) / 2f;

        // label or rename field
        if (r.key().equals(renaming)) {
            ImGui.setCursorScreenPos(tx, y + 2);
            ImGui.setNextItemWidth(Math.max(80, w - (tx - x) - 60));
            if (renameFocus) {
                ImGui.setKeyboardFocusHere();
                renameFocus = false;
            }
            boolean enter = ImGui.inputText("##rename", renameBuf, ImGuiInputTextFlags.EnterReturnsTrue
                | ImGuiInputTextFlags.AutoSelectAll);
            if (enter || ImGui.isItemDeactivated() && !ImGui.isKeyPressed(ImGuiKey.Escape)) {
                ctx.actions.run(NodeCommands.rename(renaming, renameBuf.get()));
                renaming = null;
            } else if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                renaming = null;
            }
        } else {
            int col = r.kind() == Kind.SLOT ? Glyphs.typeColor("Instance", 1f)
                : dimmed ? EditorWidgets.dim(1f) : EditorWidgets.text(1f);
            String label = r.kind() == Kind.SLOT ? "slot  " + r.label() : r.label();
            dl.addText(tx, ty, col, label);
            float lw = ImGui.calcTextSize(label).x;
            dl.addText(tx + lw + 8, ty, EditorWidgets.dim(0.85f), r.detail());
        }

        // right side: badges and toggles
        float bx = x + w - 4;
        if (r.kind() == Kind.NODE || r.kind() == Kind.INTERNAL) {
            float bs = h - 10;
            boolean showToggles = hovered || v.hidden.contains(r.key()) || v.locked.contains(r.key());
            if (showToggles) {
                bx -= bs + 4;
                boolean overLock = ImGui.isMouseHoveringRect(bx - 2, y, bx + bs + 2, y + h);
                Glyphs.lock(dl, bx, y + 5, bs, overLock ? EditorWidgets.text(1f) : EditorWidgets.dim(1f), v.locked.contains(r.key()));
                if (overLock && ImGui.isItemClicked()) {
                    if (!v.locked.remove(r.key())) {
                        v.locked.add(r.key());
                    }
                    ImGui.popID();
                    return;
                }
                if (overLock) {
                    ImGui.setTooltip(v.locked.contains(r.key()) ? "Unlock (canvas can select it)" : "Lock (canvas clicks pass through)");
                }
                bx -= bs + 6;
                boolean overEye = ImGui.isMouseHoveringRect(bx - 2, y, bx + bs + 2, y + h);
                Glyphs.eye(dl, bx, y + 5, bs, overEye ? EditorWidgets.text(1f) : EditorWidgets.dim(1f), !v.hidden.contains(r.key()));
                if (overEye && ImGui.isItemClicked()) {
                    ContextMenus.toggleHidden(ctx, r.key());
                    ImGui.popID();
                    return;
                }
                if (overEye) {
                    ImGui.setTooltip("Designer visibility only; the document is not changed");
                }
            }
            UiNode n = r.kind() == Kind.NODE ? ctx.node(r.key()) : null;
            if (n != null && !n.bindings().isEmpty()) {
                bx -= bs + 6;
                Glyphs.link(dl, bx, y + 5, bs, Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
            }
            if (r.kind() == Kind.INTERNAL && overridden(r)) {
                bx -= bs + 6;
                dl.addCircleFilled(bx + bs / 2f, y + h / 2f, bs * 0.3f, EditorWidgets.accent(1f));
            }
            UiRuntimeDiagnostic.Severity sev = problems.get(r.key());
            if (sev != null) {
                bx -= bs + 6;
                if (sev == UiRuntimeDiagnostic.Severity.ERROR) {
                    Glyphs.error(dl, bx, y + 5, bs, ThemeColors.u32(ThemeColors.Tone.ERROR, 1f));
                } else {
                    Glyphs.warning(dl, bx, y + 5, bs, ThemeColors.u32(ThemeColors.Tone.WARNING, 1f));
                }
            }
        }

        // clicks
        if (ImGui.isItemClicked() && renaming == null) {
            click(doc, r, rows);
        }
        if (hovered && ImGui.isMouseDoubleClicked(0) && r.kind() == Kind.NODE) {
            startRename(doc, r.key());
        }
        if (ImGui.isItemClicked(1) && r.kind() != Kind.SLOT && !doc.isSelected(r.key())) {
            doc.select(List.of(r.key()));
        }
        if (r.kind() != Kind.SLOT && ImGui.beginPopupContextItem("##rowMenu")) {
            if (r.kind() == Kind.NODE && ctx.node(r.key()) != null && ctx.node(r.key()).instance() != null) {
                boolean shown = v.showInternals.contains(r.key());
                if (ImGui.menuItem(shown ? "Hide Component Internals" : "Show Component Internals")) {
                    if (!v.showInternals.remove(r.key())) {
                        v.showInternals.add(r.key());
                        v.collapsed.remove(r.key());
                    }
                }
                ImGui.separator();
            }
            ContextMenus.elementMenu(ctx);
            ImGui.endPopup();
        }

        // drag and drop
        if (r.kind() == Kind.NODE && !r.key().equals(doc.archive().document().root().id())
                && ImGui.beginDragDropSource()) {
            if (!doc.isSelected(r.key())) {
                doc.select(List.of(r.key()));
            }
            ImGui.setDragDropPayload(NODE_PAYLOAD, r.key(), ImGuiCond.Once);
            int n = NodeCommands.documentNodes(doc.selection()).size();
            ImGui.textUnformatted(n > 1 ? n + " elements" : r.label());
            ImGui.endDragDropSource();
        }
        if (r.kind() != Kind.INTERNAL && ImGui.beginDragDropTarget()) {
            NodeLocation at = dropLocation(doc, r, y, h);
            if (at != null) {
                drawDropZone(dl, doc, r, at, x, y, w, h, ix);
                acceptDrop(doc, at);
            }
            ImGui.endDragDropTarget();
        }
        ImGui.popID();
    }

    private boolean overridden(Row r) {
        OverrideCommands.Target t = OverrideCommands.Target.of(r.key());
        UiNode inst = t == null ? null : ctx.node(t.instanceId());
        return inst != null && inst.instance() != null
            && inst.instance().overrides().stream().anyMatch(o -> o.target().equals(t.target()));
    }

    private void click(UiEditorDocument doc, Row r, List<Row> rows) {
        if (r.kind() == Kind.SLOT) {
            doc.select(List.of(r.instanceId()));
            return;
        }
        if (ImGui.getIO().getKeyShift() && anchorKey != null) {
            List<String> range = new ArrayList<>();
            boolean in = false;
            for (Row x : rows) {
                if (x.kind() == Kind.SLOT) {
                    continue;
                }
                boolean edge = x.key().equals(anchorKey) || x.key().equals(r.key());
                if (edge || in) {
                    range.add(x.key());
                }
                if (edge && !anchorKey.equals(r.key())) {
                    in = !in;
                }
            }
            doc.select(range);
            return;
        }
        anchorKey = r.key();
        if (ImGui.getIO().getKeyCtrl()) {
            doc.toggle(r.key());
        } else {
            doc.select(List.of(r.key()));
        }
    }

    /** Above (top quarter), below (bottom quarter) or inside (middle, containers and slots). */
    private NodeLocation dropLocation(UiEditorDocument doc, Row r, float y, float h) {
        UiNode root = doc.archive().document().root();
        if (r.kind() == Kind.SLOT) {
            UiNode inst = UiTree.find(root, r.instanceId());
            return inst == null ? null : new NodeLocation(r.instanceId(), r.slot(), Integer.MAX_VALUE);
        }
        float my = ImGui.getMousePosY();
        UiNode n = UiTree.find(root, r.key());
        if (n == null) {
            return null;
        }
        boolean container = NodeCommands.acceptsChildren(n.type());
        NodeLocation at = UiTree.locate(root, r.key());
        float rel = (my - y) / h;
        if (at == null || container && rel > 0.25f && rel < 0.75f) {
            return container ? NodeLocation.endOf(n.id()) : null;
        }
        return rel < 0.5f ? at : at.at(at.index() + 1);
    }

    private void drawDropZone(ImDrawList dl, UiEditorDocument doc, Row r, NodeLocation at, float x, float y, float w,
                              float h, float ix) {
        int accent = EditorWidgets.accent(1f);
        if (at.index() == Integer.MAX_VALUE) {
            dl.addRect(x + 1, y + 1, x + w - 1, y + h - 1, accent, 3f, 0, 2f);
            return;
        }
        NodeLocation own = UiTree.locate(doc.archive().document().root(), r.key());
        boolean before = own != null && own.index() == at.index();
        float ly = before ? y : y + h;
        dl.addLine(ix, ly, x + w - 4, ly, accent, 2.5f);
        dl.addCircleFilled(ix, ly, 3.5f, accent);
    }

    private void acceptDrop(UiEditorDocument doc, NodeLocation at) {
        Object node = ImGui.acceptDragDropPayload(NODE_PAYLOAD, ImGuiDragDropFlags.AcceptNoDrawDefaultRect);
        if (node instanceof String) {
            ctx.actions.run(NodeCommands.move(NodeCommands.documentNodes(doc.selection()), at));
        }
        Object widget = ImGui.acceptDragDropPayload(PalettePanel.PAYLOAD, ImGuiDragDropFlags.AcceptNoDrawDefaultRect);
        if (widget instanceof String item) {
            ctx.actions.run(PalettePanel.createCommand(ctx, item, at));
        }
    }

    private static void toggleCollapsed(DocumentViewState v, String key) {
        if (!v.collapsed.remove(key)) {
            v.collapsed.add(key);
        }
    }

    /** Expands the ancestors of the primary selection so canvas picks are visible here. */
    private void revealSelection(UiEditorDocument doc) {
        DocumentViewState v = ctx.view();
        String p = doc.primary();
        if (p == null || v == null) {
            return;
        }
        String head = p.indexOf('/') < 0 ? p : p.substring(0, p.indexOf('/'));
        for (String id : UiTree.pathTo(doc.archive().document().root(), head)) {
            if (!id.equals(p)) {
                v.collapsed.remove(id);
            }
        }
        if (p.indexOf('/') >= 0) {
            v.showInternals.add(head);
            v.collapsed.remove(head);
        }
    }

    private void startRename(UiEditorDocument doc, String key) {
        UiNode n = UiTree.find(doc.archive().document().root(), key);
        if (n == null) {
            return;
        }
        renaming = key;
        renameBuf.set(n.name() == null ? n.id() : n.name());
        renameFocus = true;
    }

    private void keyboard(UiEditorDocument doc, List<Row> rows) {
        List<Row> nav = rows.stream().filter(r -> r.kind() != Kind.SLOT).toList();
        String p = doc.primary();
        int i = -1;
        for (int k = 0; k < nav.size(); k++) {
            if (nav.get(k).key().equals(p)) {
                i = k;
            }
        }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow) && i + 1 < nav.size()) {
            doc.select(List.of(nav.get(i + 1).key()));
        } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow) && i > 0) {
            doc.select(List.of(nav.get(i - 1).key()));
        } else if (i >= 0 && ImGui.isKeyPressed(ImGuiKey.LeftArrow)) {
            Row r = nav.get(i);
            if (r.expandable() && r.expanded()) {
                ctx.view().collapsed.add(r.key());
            } else {
                ctx.actions.selectParent();
            }
        } else if (i >= 0 && ImGui.isKeyPressed(ImGuiKey.RightArrow)) {
            ctx.view().collapsed.remove(nav.get(i).key());
        } else if (p != null && ImGui.isKeyPressed(ImGuiKey.F2)) {
            startRename(doc, p);
        }
    }

    private static String stem(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }
}
