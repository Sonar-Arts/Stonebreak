package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.graph.PortType;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Variables and functions of the graph: add, rename, retype, set defaults, delete, and edit a
 * function's signature (rows of name and type, where {@code exec} makes it a statement
 * function). Renames go through {@link GraphEditor}, which rewrites every node that names them.
 */
final class GraphDataPanel {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final CommitField fields = new CommitField();
    private final ImString newVariable = new ImString(64);
    private ValueType newVariableType = ValueType.NUMBER;
    private final ImString newFunction = new ImString(64);

    void draw(GraphEditorState st) {
        variables(st);
        ImGui.dummy(0, 8);
        functions(st);
    }

    // ── variables ───────────────────────────────────────────────────────────

    private void variables(GraphEditorState st) {
        GraphEditor ed = st.editor;
        ImGui.separatorText("Variables");
        for (GraphVariable v : ed.graph().variables()) {
            ImGui.pushID("var-" + v.name());
            String renamed = fields.text("var-name-" + v.name(), v.name(), 110);
            if (renamed != null && !renamed.equals(v.name())) {
                attempt(st, () -> ed.renameVariable(v.name(), renamed), IDENT.matcher(renamed).matches(), "variable name");
            }
            ImGui.sameLine();
            ImGui.setNextItemWidth(84);
            if (ImGui.beginCombo("##vtype", v.type().wire())) {
                for (ValueType t : ValueType.values()) {
                    if (ImGui.selectable(t.wire(), t == v.type())) {
                        UiValue keep = t.accepts(v.defaultValue()) ? v.defaultValue() : UiValue.NULL;
                        ed.setVariable(v.name(), t, keep);
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine();
            String key = "var-def-" + v.name();
            String t = fields.text(key, v.defaultValue() == null ? "" : GraphValues.editText(v.defaultValue()), 100);
            if (t != null) {
                UiValue parsed = GraphValues.parse(PortType.of(v.type()), t);
                if (parsed != null) {
                    ed.setVariable(v.name(), v.type(), parsed);
                } else {
                    st.status = "'" + t + "' is not a valid " + v.type().wire();
                    fields.revert(key);
                }
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                ed.removeVariable(v.name());
            }
            ImGui.popID();
        }
        ImGui.setNextItemWidth(110);
        ImGui.inputText("##newVar", newVariable);
        ImGui.sameLine();
        ImGui.setNextItemWidth(84);
        if (ImGui.beginCombo("##newVarType", newVariableType.wire())) {
            for (ValueType t : ValueType.values()) {
                if (ImGui.selectable(t.wire(), t == newVariableType)) {
                    newVariableType = t;
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (ImGui.button("Add variable")) {
            String name = newVariable.get().strip();
            attempt(st, () -> {
                ed.addVariable(name, newVariableType, defaultFor(newVariableType));
                newVariable.set("");
            }, IDENT.matcher(name).matches(), "variable name");
        }
    }

    private static UiValue defaultFor(ValueType t) {
        return switch (t) {
            case BOOL -> UiValue.FALSE;
            case INT, NUMBER -> UiValue.of(0);
            case STRING, ASSET -> UiValue.of("");
            case COLOR -> UiValue.of("#FFFFFF");
            case LIST -> new UiValue.Arr(List.of());
            case OBJECT -> UiValue.Obj.EMPTY;
        };
    }

    // ── functions ───────────────────────────────────────────────────────────

    private void functions(GraphEditorState st) {
        GraphEditor ed = st.editor;
        ImGui.separatorText("Functions");
        for (GraphFunction f : ed.graph().functions()) {
            ImGui.pushID("fn-" + f.id());
            boolean open = ImGui.treeNode(f.id() + "###fn");
            ImGui.sameLine();
            if (ImGui.smallButton("Edit body")) {
                st.switchBody(f.id());
                st.frameAll = true;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Delete")) {
                if (st.body.equals(f.id())) {
                    st.switchBody("");
                }
                ed.removeFunction(f.id());
                if (open) {
                    ImGui.treePop();
                }
                ImGui.popID();
                break;
            }
            if (open) {
                String renamed = fields.text("fn-name-" + f.id(), f.id(), 160);
                if (renamed != null && !renamed.equals(f.id())) {
                    boolean wasBody = st.body.equals(f.id());
                    attempt(st, () -> {
                        ed.renameFunction(f.id(), renamed);
                        if (wasBody) {
                            st.body = renamed;
                        }
                    }, IDENT.matcher(renamed).matches(), "function name");
                }
                signature(st, f, "Inputs", f.inputs(), true);
                signature(st, f, "Outputs", f.outputs(), false);
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.setNextItemWidth(160);
        ImGui.inputText("##newFn", newFunction);
        ImGui.sameLine();
        if (ImGui.button("Add function")) {
            String name = newFunction.get().strip();
            attempt(st, () -> {
                ed.addFunction(name, List.of(), List.of());
                newFunction.set("");
            }, IDENT.matcher(name).matches(), "function name");
        }
    }

    private void signature(GraphEditorState st, GraphFunction f, String title, List<GraphPort> ports, boolean inputs) {
        ImGui.textDisabled(title);
        List<GraphPort> rows = new ArrayList<>(ports);
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            GraphPort p = rows.get(i);
            ImGui.pushID(title + i);
            String typed = fields.text("sig-" + f.id() + title + i, p.name(), 110);
            String newType = p.type();
            ImGui.sameLine();
            ImGui.setNextItemWidth(84);
            if (ImGui.beginCombo("##ptype", p.type())) {
                for (PortType t : PortType.values()) {
                    if (ImGui.selectable(t.wire(), t.wire().equals(p.type()))) {
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
            if ((typed != null && IDENT.matcher(typed).matches()) || !newType.equals(p.type())) {
                rows.set(i, new GraphPort(typed != null && IDENT.matcher(typed).matches() ? typed : p.name(), newType,
                    p.unknown()));
                changed = true;
            }
        }
        if (ImGui.smallButton("+ " + title.toLowerCase().substring(0, title.length() - 1))) {
            rows.add(new GraphPort(uniqueName(rows, inputs ? "in" : "out"), "number", java.util.Map.of()));
            changed = true;
        }
        if (changed) {
            List<GraphPort> next = rows;
            st.status = "";
            attempt(st, () -> st.editor.setSignature(f.id(), inputs ? next : f.inputs(), inputs ? f.outputs() : next),
                true, "signature");
        }
    }

    private static String uniqueName(List<GraphPort> rows, String base) {
        for (int n = rows.size() + 1; ; n++) {
            String candidate = base + n;
            if (rows.stream().noneMatch(p -> p.name().equals(candidate))) {
                return candidate;
            }
        }
    }

    private void attempt(GraphEditorState st, Runnable edit, boolean valid, String what) {
        if (!valid) {
            st.status = "Not a valid " + what + " (letters, digits and _, not starting with a digit)";
            return;
        }
        try {
            edit.run();
            st.status = "";
        } catch (IllegalArgumentException e) {
            st.status = e.getMessage();
        }
    }
}
