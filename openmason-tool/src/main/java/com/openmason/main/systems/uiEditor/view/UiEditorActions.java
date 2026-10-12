package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.main.systems.uiEditor.canvas.AlignDistribute;
import com.openmason.main.systems.uiEditor.canvas.Box;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.command.UiClipboard;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every edit the UI editor's panels make goes through here, and from here through a
 * {@link UiCommand}: one undo step per user action, with the same commands an automation
 * frontend would issue. Selection-only changes go straight to the document's selection (they
 * are not source).
 */
public final class UiEditorActions {

    private final UiEditorContext ctx;
    /** Last copied payload, kept in-process in case the system clipboard is unavailable. */
    private OmuiArchive clipboard;

    UiEditorActions(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    private UiEditorDocument doc() {
        return ctx.doc();
    }

    /** Runs a command on the active document; false when refused or no document. */
    public boolean run(UiCommand command) {
        UiEditorDocument d = doc();
        return d != null && d.execute(command);
    }

    public void endInteraction() {
        if (doc() != null) {
            doc().endInteraction();
        }
    }

    public void undo() {
        if (doc() != null) {
            doc().undo();
        }
    }

    public void redo() {
        if (doc() != null) {
            doc().redo();
        }
    }

    // ── selection ───────────────────────────────────────────────────────────

    public void select(String key, boolean additive) {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        if (key == null) {
            if (!additive) {
                d.clearSelection();
            }
        } else if (additive) {
            d.toggle(key);
        } else {
            d.select(List.of(key));
        }
    }

    /** Children of the primary selection's parent (or of the root). */
    public void selectAll() {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        UiNode root = d.archive().document().root();
        String primary = d.primary();
        NodeLocation at = primary == null || primary.indexOf('/') >= 0 ? null : UiTree.locate(root, primary);
        UiNode parent = at == null ? root : UiTree.find(root, at.parentId());
        List<String> keys = UiTree.list(parent, at == null ? null : at.slot()).stream().map(UiNode::id).toList();
        d.select(keys.isEmpty() ? List.of(root.id()) : keys);
    }

    public void selectParent() {
        UiEditorDocument d = doc();
        String p = d == null ? null : d.primary();
        if (p == null) {
            return;
        }
        int slash = p.lastIndexOf('/');
        if (slash >= 0) {
            d.select(List.of(p.substring(0, slash)));
            return;
        }
        NodeLocation at = UiTree.locate(d.archive().document().root(), p);
        if (at != null) {
            d.select(List.of(at.parentId()));
        }
    }

    // ── structure ───────────────────────────────────────────────────────────

    /** Where new content goes: inside the primary selection when it holds children, else after it. */
    public NodeLocation insertionPoint() {
        UiEditorDocument d = doc();
        UiNode root = d.archive().document().root();
        String primary = d.primary();
        if (primary == null || primary.indexOf('/') >= 0) {
            return NodeLocation.endOf(root.id());
        }
        UiNode n = UiTree.find(root, primary);
        if (n != null && NodeCommands.acceptsChildren(n.type())) {
            return NodeLocation.endOf(n.id());
        }
        NodeLocation at = UiTree.locate(root, primary);
        return at == null ? NodeLocation.endOf(root.id()) : at.at(at.index() + 1);
    }

    public void deleteSelection() {
        UiEditorDocument d = doc();
        if (d == null || d.selection().isEmpty()) {
            return;
        }
        List<String> internals = d.selection().stream().filter(k -> k.indexOf('/') >= 0).toList();
        if (!internals.isEmpty() && NodeCommands.documentNodes(d.selection()).isEmpty()) {
            List<UiCommand> resets = new ArrayList<>();
            internals.forEach(k -> resets.add(OverrideCommands.reset(k)));
            run(UiCommand.compound("Reset overrides", resets));
            return;
        }
        run(NodeCommands.delete(d.selection()));
    }

    public void duplicateSelection() {
        if (doc() != null) {
            run(NodeCommands.duplicate(doc().selection()));
        }
    }

    public void copySelection() {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        OmuiArchive payload = UiClipboard.copy(d.archive(), d.selection());
        if (payload == null) {
            d.setLastMessage("Select elements to copy (component internals cannot be copied)");
            return;
        }
        clipboard = payload;
        String text = UiClipboard.toText(payload);
        if (text != null) {
            ImGui.setClipboardText(text);
        }
        int n = payload.document().root().children().size();
        d.setLastMessage("Copied " + n + (n == 1 ? " element" : " elements"));
    }

    public void cutSelection() {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        copySelection();
        if (clipboard != null) {
            run(NodeCommands.delete(d.selection()));
        }
    }

    public boolean canPaste() {
        return clipboard != null || UiClipboard.fromText(safeClipboard()) != null;
    }

    public void paste() {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        OmuiArchive payload = UiClipboard.fromText(safeClipboard());
        if (payload == null) {
            payload = clipboard;
        }
        if (payload == null) {
            d.setLastMessage("Nothing to paste");
            return;
        }
        run(UiClipboard.paste(payload, insertionPoint()));
    }

    private static String safeClipboard() {
        try {
            return ImGui.getClipboardText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void add(String type) {
        if (doc() != null) {
            run(NodeCommands.create(type, insertionPoint(), null));
        }
    }

    public void wrapSelection(String type) {
        if (doc() != null) {
            run(NodeCommands.wrap(doc().selection(), type));
        }
    }

    public void reorderSelection(int delta) {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        List<UiCommand> steps = new ArrayList<>();
        for (String id : NodeCommands.documentNodes(d.selection())) {
            steps.add(delta == Integer.MAX_VALUE || delta == Integer.MIN_VALUE
                ? NodeCommands.reorderToEnd(id, delta == Integer.MAX_VALUE) : NodeCommands.reorder(id, delta));
        }
        if (!steps.isEmpty()) {
            run(steps.size() == 1 ? steps.getFirst() : UiCommand.compound("Reorder", steps));
        }
    }

    // ── geometry ────────────────────────────────────────────────────────────

    /** True when the element is laid out with {@code position: absolute}. */
    public boolean isAbsolute(String key) {
        UiElement el = ctx.element(key);
        return el != null && "absolute".equals(el.computedStyle().keyword("position", "relative"));
    }

    /**
     * Current {@code left}/{@code top} of an absolute element in logical px: the authored numbers
     * when set, else what its rect implies relative to the parent's padding box.
     */
    public float[] offsets(String key) {
        UiElement el = ctx.element(key);
        DocumentViewState v = ctx.view();
        float scale = v == null ? 1f : v.scale();
        if (el == null) {
            return new float[]{0, 0};
        }
        UiValue l = el.computedStyle().get("left");
        UiValue t = el.computedStyle().get("top");
        UiRect r = el.rect();
        UiRect p = el.parent() == null ? new UiRect(0, 0, 0, 0) : el.parent().rect();
        float bl = el.parent() == null ? 0 : (float) el.parent().computedStyle().number("border-left-width", 0);
        float bt = el.parent() == null ? 0 : (float) el.parent().computedStyle().number("border-top-width", 0);
        float left = l instanceof UiValue.Num n ? (float) n.value() : (r.x() - p.x()) / scale - bl;
        float top = t instanceof UiValue.Num n ? (float) n.value() : (r.y() - p.y()) / scale - bt;
        return new float[]{left, top};
    }

    /** Nudges absolute elements of the selection by logical pixels (arrow keys). */
    public void nudge(int dx, int dy) {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        List<UiCommand> steps = new ArrayList<>();
        for (String key : NodeCommands.documentNodes(d.selection())) {
            if (!isAbsolute(key)) {
                continue;
            }
            float[] o = offsets(key);
            Map<String, UiValue> s = new LinkedHashMap<>();
            s.put("left", UiValue.of(Math.round(o[0] + dx)));
            s.put("top", UiValue.of(Math.round(o[1] + dy)));
            steps.add(NodeCommands.setStyle(List.of(key), s, "Nudge"));
        }
        if (steps.isEmpty()) {
            d.setLastMessage("Arrow keys move absolutely positioned elements; flow elements follow their container");
            return;
        }
        run(UiCommand.of("Nudge", "nudge:" + d.selection(), c -> {
            for (UiCommand step : steps) {
                step.apply(c);
            }
        }));
    }

    /** Align or distribute the absolute elements of the selection. */
    public void align(AlignDistribute.Op op) {
        UiEditorDocument d = doc();
        DocumentViewState v = ctx.view();
        if (d == null || v == null) {
            return;
        }
        List<AlignDistribute.Item> items = new ArrayList<>();
        Box parent = null;
        for (String key : NodeCommands.documentNodes(d.selection())) {
            UiElement el = ctx.element(key);
            if (el == null || !isAbsolute(key)) {
                continue;
            }
            float[] o = offsets(key);
            items.add(new AlignDistribute.Item(key, Box.of(el.rect()), o[0], o[1]));
            if (el.parent() != null) {
                parent = Box.of(el.parent().rect());
            }
        }
        if (items.isEmpty()) {
            d.setLastMessage("Align works on absolutely positioned elements; use the container's flex controls for flow layout");
            return;
        }
        List<UiCommand> steps = new ArrayList<>();
        for (AlignDistribute.Placement p : AlignDistribute.apply(op, items, parent, v.scale())) {
            Map<String, UiValue> s = new LinkedHashMap<>();
            s.put("left", UiValue.of(p.left()));
            s.put("top", UiValue.of(p.top()));
            steps.add(NodeCommands.setStyle(List.of(p.key()), s, "Align"));
        }
        if (!steps.isEmpty()) {
            run(UiCommand.compound(op.distributes() ? "Distribute" : "Align", steps));
        }
    }

    /** True when at least one selected element can be aligned (absolute). */
    public boolean canAlign() {
        UiEditorDocument d = doc();
        if (d == null) {
            return false;
        }
        for (String key : NodeCommands.documentNodes(d.selection())) {
            if (isAbsolute(key)) {
                return true;
            }
        }
        return false;
    }
}
