package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.graph.NodeKind;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.PortType;
import com.openmason.engine.ui.graph.PropSpec;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.graph.edit.GraphLayout;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inspector of the selection: the selected node's kind, properties (a widget per
 * {@link PropSpec.Kind}) and typed literals for unconnected data inputs, or the selected
 * comment's text and color. Every change is one {@link GraphEditor} edit, committed when the
 * author finishes typing so undo steps stay meaningful.
 */
final class GraphInspectorPanel {

    private final CommitField fields = new CommitField();

    void draw(GraphEditorState st) {
        GraphEditor ed = st.editor;
        if (st.selectedComment != null) {
            comment(st);
            return;
        }
        if (st.selection.size() > 1) {
            ImGui.text(st.selection.size() + " nodes selected");
            ImGui.textDisabled("Select one node to edit its properties.");
            return;
        }
        String id = st.single();
        GraphNode node = id == null ? null : ed.node(st.body, id);
        if (node == null) {
            ImGui.textDisabled("Select a node or a comment to edit it.");
            return;
        }
        NodeKind kind = ed.kind(st.body, id);
        ImGui.text(kind == null ? "Unknown node kind" : kind.title());
        ImGui.textDisabled(node.kind() + "   id: " + id);
        if (kind == null) {
            ThemedWidgets.statusTextWrapped(Tone.ERROR, "This node kind is not known to this build; it is kept as is.");
            return;
        }
        if (!kind.doc().isEmpty()) {
            ImGui.textWrapped(kind.doc());
        }
        List<String> targets = ed.elementTargets(st.body, id);
        for (String t : targets) {
            if (ImGui.smallButton("Show " + t + " in preview")) {
                st.jumpToElement.accept(t);
            }
        }
        ImGui.separator();
        ImGui.pushID(id);
        for (PropSpec ps : kind.props()) {
            prop(st, node, ps);
        }
        inputs(st, node);
        ImGui.popID();
    }

    // ── comment ─────────────────────────────────────────────────────────────

    private void comment(GraphEditorState st) {
        GraphEditor ed = st.editor;
        GraphLayout.Comment c = ed.layout().comments().stream().filter(x -> x.id().equals(st.selectedComment)).findFirst()
            .orElse(null);
        if (c == null) {
            st.selectedComment = null;
            return;
        }
        ImGui.text("Comment");
        ImGui.textDisabled(c.id());
        String text = fields.text("cmt-text-" + c.id(), c.text(), -1);
        if (text != null) {
            ed.editComment(c.id(), c.x(), c.y(), c.width(), c.height(), text, c.color(), null);
        }
        ImGui.textDisabled("Color (#RRGGBB, empty for default)");
        String color = fields.text("cmt-color-" + c.id(), c.color(), -1);
        if (color != null) {
            if (color.isEmpty() || GraphColors.parseHex(color, 1f) != 0) {
                ed.editComment(c.id(), c.x(), c.y(), c.width(), c.height(), c.text(), color, null);
            } else {
                st.status = "'" + color + "' is not a #RRGGBB color";
                fields.revert("cmt-color-" + c.id());
            }
        }
        if (ThemedWidgets.dangerButton("Delete comment", 0, 0)) {
            ed.removeComment(c.id());
            st.selectedComment = null;
        }
    }

    // ── properties ──────────────────────────────────────────────────────────

    private void prop(GraphEditorState st, GraphNode node, PropSpec ps) {
        GraphEditor ed = st.editor;
        UiValue cur = node.props().get(ps.name());
        String label = ps.name() + (ps.required() ? " *" : "");
        ImGui.pushID(ps.name());
        ImGui.textUnformatted(label);
        if (ImGui.isItemHovered() && !ps.doc().isEmpty()) {
            ImGui.setTooltip(ps.doc());
        }
        switch (ps.kind()) {
            case BOOL -> {
                boolean v = cur instanceof UiValue.Bool b ? b.value()
                    : ps.defaultValue() instanceof UiValue.Bool d && d.value();
                if (ImGui.checkbox("##v", v)) {
                    ed.setProp(st.body, node.id(), ps.name(), UiValue.of(!v));
                }
            }
            case PARAMS -> params(st, node, ps, cur);
            case NAMES -> names(st, node, ps, cur);
            case INT -> {
                String key = fieldKey(st, node, ps.name());
                String t = fields.text(key, cur == null ? "" : GraphValues.editText(cur), -1);
                if (t != null) {
                    UiValue v = t.isBlank() ? null : GraphValues.parse(PortType.INT, t);
                    if (v != null || t.isBlank()) {
                        ed.setProp(st.body, node.id(), ps.name(), v);
                    } else {
                        st.status = "'" + t + "' is not an integer";
                        fields.revert(key);
                    }
                }
            }
            default -> {
                List<String> options = ed.options(st.body, node.id(), ps.name());
                String current = cur instanceof UiValue.Str s ? s.value() : cur == null ? "" : GraphValues.editText(cur);
                if (!options.isEmpty()) {
                    combo(st, node, ps, options, current);
                } else {
                    String key = fieldKey(st, node, ps.name());
                    String t = fields.text(key, current, -1);
                    if (t != null) {
                        ed.setProp(st.body, node.id(), ps.name(), t.isEmpty() && !ps.required() ? null : UiValue.of(t));
                    }
                }
            }
        }
        if (!ps.required() && cur != null && ps.kind() != PropSpec.Kind.BOOL) {
            ImGui.sameLine();
            if (ImGui.smallButton("clear")) {
                ed.setProp(st.body, node.id(), ps.name(), null);
            }
        }
        ImGui.popID();
    }

    private void combo(GraphEditorState st, GraphNode node, PropSpec ps, List<String> options, String current) {
        ImGui.setNextItemWidth(-1);
        if (ImGui.beginCombo("##opt", current.isEmpty() ? "(none)" : current)) {
            for (String o : options) {
                if (ImGui.selectable(o.isEmpty() ? "(none)" : o, o.equals(current))) {
                    st.editor.setProp(st.body, node.id(), ps.name(), o.isEmpty() && !ps.required() ? null : UiValue.of(o));
                }
            }
            ImGui.endCombo();
        }
    }

    private void params(GraphEditorState st, GraphNode node, PropSpec ps, UiValue cur) {
        List<UiValue> rows = cur instanceof UiValue.Arr a ? new ArrayList<>(a.items()) : new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            UiValue.Obj row = rows.get(i) instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
            String name = row.get("name") instanceof UiValue.Str s ? s.value() : "";
            String type = row.get("type") instanceof UiValue.Str s ? s.value() : "string";
            ImGui.pushID(i);
            String key = fieldKey(st, node, ps.name() + "#" + i);
            String typed = fields.text(key, name, 120);
            String newType = type;
            ImGui.sameLine();
            ImGui.setNextItemWidth(90);
            if (ImGui.beginCombo("##type", type)) {
                for (ValueType t : ValueType.values()) {
                    if (ImGui.selectable(t.wire(), t.wire().equals(type))) {
                        newType = t.wire();
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine();
            boolean remove = ImGui.smallButton("x");
            ImGui.popID();
            if (remove) {
                rows.remove(i);
                changed = true;
                break;
            }
            if (typed != null || !newType.equals(type)) {
                Map<String, UiValue> m = new LinkedHashMap<>();
                m.put("name", UiValue.of(typed != null ? typed : name));
                m.put("type", UiValue.of(newType));
                rows.set(i, UiValue.Obj.sorted(m));
                changed = true;
            }
        }
        if (ImGui.smallButton("+ parameter")) {
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("name", UiValue.of("value" + (rows.size() + 1)));
            m.put("type", UiValue.of("string"));
            rows.add(UiValue.Obj.sorted(m));
            changed = true;
        }
        if (changed) {
            st.editor.setProp(st.body, node.id(), ps.name(), new UiValue.Arr(rows));
        }
    }

    private void names(GraphEditorState st, GraphNode node, PropSpec ps, UiValue cur) {
        List<UiValue> rows = cur instanceof UiValue.Arr a ? new ArrayList<>(a.items()) : new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            String name = rows.get(i) instanceof UiValue.Str s ? s.value() : "";
            ImGui.pushID(i);
            String typed = fields.text(fieldKey(st, node, ps.name() + "#" + i), name, 160);
            ImGui.sameLine();
            boolean remove = ImGui.smallButton("x");
            ImGui.popID();
            if (remove) {
                rows.remove(i);
                changed = true;
                break;
            }
            if (typed != null) {
                rows.set(i, UiValue.of(typed));
                changed = true;
            }
        }
        if (ImGui.smallButton("+ name")) {
            rows.add(UiValue.of("arg" + (rows.size() + 1)));
            changed = true;
        }
        if (changed) {
            st.editor.setProp(st.body, node.id(), ps.name(), new UiValue.Arr(rows));
        }
    }

    // ── unconnected inputs ──────────────────────────────────────────────────

    private void inputs(GraphEditorState st, GraphNode node) {
        GraphEditor ed = st.editor;
        List<PortSpec> data = ed.ports(st.body, node.id()).dataInputs();
        if (data.isEmpty()) {
            return;
        }
        ImGui.separator();
        ImGui.textDisabled("Inputs");
        for (PortSpec p : data) {
            ImGui.pushID("in-" + p.name());
            boolean linked = false;
            for (GraphEdge e : ed.edges(st.body)) {
                linked |= e.toNode().equals(node.id()) && e.toPort().equals(p.name());
            }
            ImGui.textUnformatted(p.name() + " : " + GraphEditor.typeName(p.type()));
            if (ImGui.isItemHovered() && !p.doc().isEmpty()) {
                ImGui.setTooltip(p.doc());
            }
            if (linked) {
                ImGui.textDisabled("  connected");
                ImGui.popID();
                continue;
            }
            UiValue lit = node.inputs().get(p.name());
            UiValue shown = lit != null ? lit : p.defaultValue();
            if (p.type() == PortType.BOOL) {
                boolean v = shown instanceof UiValue.Bool b && b.value();
                if (ImGui.checkbox("##v", v)) {
                    ed.setInput(st.body, node.id(), p.name(), UiValue.of(!v));
                }
            } else {
                String key = fieldKey(st, node, "in-" + p.name());
                String t = fields.text(key, shown == null ? "" : GraphValues.editText(shown), -1);
                if (t != null) {
                    UiValue v = GraphValues.parse(p.type(), t);
                    if (v != null) {
                        ed.setInput(st.body, node.id(), p.name(), v);
                    } else if (t.isBlank() && p.optional()) {
                        ed.setInput(st.body, node.id(), p.name(), null);
                    } else {
                        st.status = "'" + t + "' is not a valid " + GraphEditor.typeName(p.type());
                        fields.revert(key);
                    }
                }
            }
            if (lit != null) {
                ImGui.sameLine();
                if (ImGui.smallButton("clear")) {
                    ed.setInput(st.body, node.id(), p.name(), null);
                }
            }
            ImGui.popID();
        }
    }

    private static String fieldKey(GraphEditorState st, GraphNode node, String what) {
        return st.body + "/" + node.id() + "/" + what;
    }
}
