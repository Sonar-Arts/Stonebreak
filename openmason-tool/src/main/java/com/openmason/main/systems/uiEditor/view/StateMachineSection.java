package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UI state machines in the Timeline's side bar (#295): states pick a clip, transitions pick the
 * clip played on the way in (plus a blend and a reduced-motion alternate). The state buttons
 * preview a state (with its transition) on the design runtime; interaction machines follow the
 * forced pseudo-states of the Details panel instead. Previewing never edits the document.
 */
final class StateMachineSection {

    private final ImString newId = new ImString(64);
    private final ImString newState = new ImString(64);

    StateMachineSection() {
    }

    void render(UiEditorDocument doc, UiDocumentInstance ui, boolean design) {
        EditorWidgets.caption("UI State Machines");
        for (UiStateMachine m : doc.archive().stateMachines().values()) {
            machine(doc, ui, design, m);
        }
        if (ImGui.button("New State Machine")) {
            newId.set("states");
            ImGui.openPopup("##newMachine");
        }
        if (ImGui.beginPopup("##newMachine")) {
            ImGui.setNextItemWidth(160f);
            ImGui.inputText("id##nm", newId);
            if (ImGui.button("Manual (screen states)")) {
                doc.execute(AnimationCommands.addStateMachine(newId.get().trim(), Driver.MANUAL, null));
                ImGui.closeCurrentPopup();
            }
            String primary = doc.primary();
            boolean canInteract = primary != null && primary.indexOf('/') < 0;
            if (ImGui.button(canInteract ? "Interaction of " + primary : "Interaction (select an element first)")
                && canInteract) {
                doc.execute(AnimationCommands.addStateMachine(newId.get().trim(), Driver.INTERACTION, primary));
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void machine(UiEditorDocument doc, UiDocumentInstance ui, boolean design, UiStateMachine m) {
        String title = m.id() + (m.driver() == Driver.INTERACTION ? "  (interaction: " + m.element() + ")" : "  (manual)");
        if (!ImGui.treeNode(title + "##m" + m.id())) {
            return;
        }
        String current = ui == null ? null : ui.stateMachines().state("", m.id());
        ImGui.textDisabled("now: " + (current == null ? "-" : current));
        if (design && ui != null && m.driver() == Driver.MANUAL) {
            for (MachineState s : m.states()) {
                ImGui.sameLine();
                if (ImGui.smallButton(s.name() + "##pv" + m.id())) {
                    ui.stateMachines().set("", m.id(), s.name(), UiAnimator.Listener.NONE); // preview only
                }
            }
        } else if (m.driver() == Driver.INTERACTION) {
            ImGui.textDisabled("Force :hover / :active / :disabled in Details to preview.");
        }

        List<String> clips = TimelinePanel.clipIds(doc);
        List<String> names = m.states().stream().map(MachineState::name).toList();
        ImGui.setNextItemWidth(120f);
        if (ImGui.beginCombo("initial##" + m.id(), m.initial())) {
            for (String n : names) {
                if (ImGui.selectable(n, n.equals(m.initial()))) {
                    put(doc, with(m, n, m.states(), m.transitions()));
                }
            }
            ImGui.endCombo();
        }
        for (int i = 0; i < m.states().size(); i++) {
            MachineState s = m.states().get(i);
            ImGui.text(s.name());
            ImGui.sameLine(110f);
            String pick = clipCombo("##sc" + m.id() + i, s.clip(), clips);
            if (pick != null) {
                List<MachineState> states = new ArrayList<>(m.states());
                states.set(i, new MachineState(s.name(), pick.isEmpty() ? null : pick, s.unknown()));
                put(doc, with(m, m.initial(), states, m.transitions()));
            }
            if (!s.name().equals(m.initial())) {
                ImGui.sameLine();
                if (ImGui.smallButton("x##sd" + m.id() + i)) {
                    List<MachineState> states = new ArrayList<>(m.states());
                    states.remove(i);
                    List<MachineTransition> ts = m.transitions().stream()
                        .filter(t -> !t.to().equals(s.name()) && !t.from().equals(s.name())).toList();
                    put(doc, with(m, m.initial(), states, ts));
                }
            }
        }
        addState(doc, m, names);
        transitions(doc, m, names, clips);
        if (ImGui.smallButton("Delete machine##" + m.id())) {
            doc.execute(AnimationCommands.removeStateMachine(m.id()));
        }
        ImGui.treePop();
    }

    private void addState(UiEditorDocument doc, UiStateMachine m, List<String> names) {
        if (m.driver() == Driver.INTERACTION) {
            for (String s : UiStateMachine.INTERACTION_STATES) {
                if (!names.contains(s)) {
                    if (ImGui.smallButton("+ " + s + "##as" + m.id())) {
                        List<MachineState> states = new ArrayList<>(m.states());
                        states.add(new MachineState(s, null, Map.of()));
                        put(doc, with(m, m.initial(), states, m.transitions()));
                    }
                    ImGui.sameLine();
                }
            }
            ImGui.newLine();
            return;
        }
        ImGui.setNextItemWidth(100f);
        ImGui.inputText("##ns" + m.id(), newState);
        ImGui.sameLine();
        String name = newState.get().trim();
        if (ImGui.smallButton("+ state##" + m.id()) && !name.isEmpty() && !names.contains(name)) {
            List<MachineState> states = new ArrayList<>(m.states());
            states.add(new MachineState(name, null, Map.of()));
            put(doc, with(m, m.initial(), states, m.transitions()));
            newState.set("");
        }
    }

    private void transitions(UiEditorDocument doc, UiStateMachine m, List<String> names, List<String> clips) {
        ImGui.textDisabled("Transitions (from > to: clip, blend, reduced-motion clip)");
        List<String> froms = new ArrayList<>();
        froms.add(UiStateMachine.ANY);
        froms.addAll(names);
        for (int i = 0; i < m.transitions().size(); i++) {
            MachineTransition t = m.transitions().get(i);
            String id = m.id() + i;
            String from = combo("##tf" + id, t.from(), froms, 60f);
            ImGui.sameLine();
            String to = combo("##tt" + id, t.to(), names, 70f);
            ImGui.sameLine();
            String clip = clipCombo("##tc" + id, t.clip(), clips);
            ImGui.sameLine();
            float[] blend = {(float) t.blend()};
            ImGui.setNextItemWidth(50f);
            boolean blended = ImGui.dragFloat("##tb" + id, blend, 0.01f, 0f, 10f, "%.2fs");
            ImGui.sameLine();
            String reduced = clipCombo("##tr" + id, t.reduced(), clips);
            ImGui.sameLine();
            boolean delete = ImGui.smallButton("x##td" + id);
            if (from != null || to != null || clip != null || blended || reduced != null || delete) {
                List<MachineTransition> ts = new ArrayList<>(m.transitions());
                if (delete) {
                    ts.remove(i);
                } else {
                    ts.set(i, new MachineTransition(from != null ? from : t.from(), to != null ? to : t.to(),
                        clip != null ? (clip.isEmpty() ? null : clip) : t.clip(), blend[0],
                        reduced != null ? (reduced.isEmpty() ? null : reduced) : t.reduced(), t.unknown()));
                }
                put(doc, with(m, m.initial(), m.states(), ts), blended ? "blend" + i : null);
            }
            if (ImGui.isItemDeactivated()) {
                doc.endInteraction();
            }
        }
        if (ImGui.smallButton("+ transition##" + m.id()) && !names.isEmpty()) {
            List<MachineTransition> ts = new ArrayList<>(m.transitions());
            for (String to : names) {
                if (m.transitions().stream().noneMatch(t -> t.from().equals(UiStateMachine.ANY) && t.to().equals(to))) {
                    ts.add(new MachineTransition(UiStateMachine.ANY, to, null, 0, null, Map.of()));
                    put(doc, with(m, m.initial(), m.states(), ts));
                    break;
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static UiStateMachine with(UiStateMachine m, String initial, List<MachineState> states,
                                       List<MachineTransition> transitions) {
        return new UiStateMachine(m.id(), m.driver(), m.element(), initial, states, transitions, m.unknown());
    }

    private static void put(UiEditorDocument doc, UiStateMachine m) {
        put(doc, m, null);
    }

    private static void put(UiEditorDocument doc, UiStateMachine m, String mergeKey) {
        doc.execute(AnimationCommands.putStateMachine(m, mergeKey));
    }

    /** A clip picker; returns the new id ("" = none) or null when unchanged. */
    private static String clipCombo(String id, String current, List<String> clips) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(clips);
        String pick = combo(id, current == null ? "" : current, options, 90f);
        return pick;
    }

    private static String combo(String id, String current, List<String> options, float width) {
        String out = null;
        ImGui.setNextItemWidth(width);
        if (ImGui.beginCombo(id, current.isEmpty() ? "(none)" : current)) {
            for (String o : options) {
                if (ImGui.selectable(o.isEmpty() ? "(none)" : o, o.equals(current)) && !o.equals(current)) {
                    out = o;
                }
            }
            ImGui.endCombo();
        }
        return out;
    }
}
