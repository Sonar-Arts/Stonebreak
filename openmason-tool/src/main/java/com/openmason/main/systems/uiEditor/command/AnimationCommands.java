package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Timeline clips and UI state machines (#295). Every Timeline gesture is one of these: a key drag
 * or a field being typed into shares a merge key, so it lands as one undo step. Clip renames carry
 * their references along (state machines and the {@code clip} of animation graph nodes); Lua is
 * never rewritten.
 */
public final class AnimationCommands {

    private AnimationCommands() {
    }

    /** A new, empty clip of {@code duration} seconds. */
    public static UiCommand addClip(String id, double duration) {
        return UiCommand.of("Add animation " + id, ctx -> {
            if (ctx.doc().animations().containsKey(id)) {
                throw new UiCommandException("An animation named '" + id + "' already exists");
            }
            ctx.setDoc(ctx.doc().withAnimation(edit(() -> ClipEdits.create(id, duration))));
        });
    }

    /**
     * Applies {@code edit} to clip {@code id}.
     *
     * @param mergeKey non-null to merge with the previous step of the same interaction (drags, typing)
     */
    public static UiCommand editClip(String label, String id, String mergeKey, UnaryOperator<UiAnimationClip> edit) {
        return UiCommand.of(label, mergeKey == null ? null : "clip:" + id + ":" + mergeKey, ctx -> {
            UiAnimationClip clip = ctx.doc().animations().get(id);
            if (clip == null) {
                throw new UiCommandException("No animation '" + id + "'");
            }
            UiAnimationClip next = edit(() -> edit.apply(clip));
            if (!next.id().equals(id)) {
                throw new UiCommandException("Use rename to change a clip's id");
            }
            ctx.setDoc(ctx.doc().withAnimation(next));
        });
    }

    /** Deleting a clip a state machine still plays is refused (the machine would break). */
    public static UiCommand removeClip(String id) {
        return UiCommand.of("Delete animation " + id, ctx -> {
            OmuiArchive d = ctx.doc();
            if (!d.animations().containsKey(id)) {
                throw new UiCommandException("No animation '" + id + "'");
            }
            List<String> users = new ArrayList<>();
            d.stateMachines().values().forEach(m -> {
                if (m.clips().contains(id)) {
                    users.add(m.id());
                }
            });
            if (!users.isEmpty()) {
                throw new UiCommandException("'" + id + "' is played by state machine " + String.join(", ", users));
            }
            ctx.setDoc(d.withoutAnimation(id));
        });
    }

    /** Renames a clip and every state machine and animation graph node that names it. */
    public static UiCommand renameClip(String id, String newId) {
        return UiCommand.of("Rename animation " + id + " to " + newId, ctx -> {
            OmuiArchive d = ctx.doc();
            UiAnimationClip clip = d.animations().get(id);
            if (clip == null) {
                throw new UiCommandException("No animation '" + id + "'");
            }
            if (d.animations().containsKey(newId)) {
                throw new UiCommandException("An animation named '" + newId + "' already exists");
            }
            UiAnimationClip renamed = edit(() -> ClipEdits.rename(clip, newId));
            OmuiArchive next = d.withoutAnimation(id).withAnimation(renamed);
            for (UiStateMachine m : d.stateMachines().values()) {
                next = next.withStateMachine(renameIn(m, id, newId));
            }
            for (UiGraph g : d.graphs().values()) {
                next = next.withGraph(renameIn(g, id, newId));
            }
            ctx.setDoc(next);
        });
    }

    // ── state machines ──────────────────────────────────────────────────────

    /** A new machine with one state, {@code initial}; interaction machines are driven by {@code element}. */
    public static UiCommand addStateMachine(String id, UiStateMachine.Driver driver, String element) {
        return UiCommand.of("Add state machine " + id, ctx -> {
            if (!OmuiFormat.PART_ID.matcher(id).matches()) {
                throw new UiCommandException("'" + id + "' is not a valid id (lowercase, digits, _ - /)");
            }
            if (ctx.doc().stateMachines().containsKey(id)) {
                throw new UiCommandException("A state machine named '" + id + "' already exists");
            }
            if (driver == UiStateMachine.Driver.INTERACTION && element == null) {
                throw new UiCommandException("Select the element whose hover/pressed/disabled states drive it");
            }
            String initial = "normal";
            List<MachineState> states = new ArrayList<>(List.of(new MachineState(initial, null, Map.of())));
            ctx.setDoc(ctx.doc().withStateMachine(new UiStateMachine(id, driver,
                driver == UiStateMachine.Driver.INTERACTION ? element : null, initial, states, List.of(), Map.of())));
        });
    }

    /** Replaces machine {@code m.id()} (state and transition edits from the panel). */
    public static UiCommand putStateMachine(UiStateMachine m, String mergeKey) {
        return UiCommand.of("Edit state machine " + m.id(), mergeKey == null ? null : "machine:" + m.id() + ":"
            + mergeKey, ctx -> ctx.setDoc(ctx.doc().withStateMachine(m)));
    }

    public static UiCommand removeStateMachine(String id) {
        return UiCommand.of("Delete state machine " + id, ctx -> {
            if (!ctx.doc().stateMachines().containsKey(id)) {
                throw new UiCommandException("No state machine '" + id + "'");
            }
            ctx.setDoc(ctx.doc().withoutStateMachine(id));
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private interface Edit<T> {
        T get();
    }

    private static <T> T edit(Edit<T> e) throws UiCommandException {
        try {
            return e.get();
        } catch (IllegalArgumentException ex) {
            throw new UiCommandException(ex.getMessage());
        }
    }

    private static UiStateMachine renameIn(UiStateMachine m, String from, String to) {
        List<MachineState> states = new ArrayList<>();
        for (MachineState s : m.states()) {
            states.add(new MachineState(s.name(), from.equals(s.clip()) ? to : s.clip(), s.unknown()));
        }
        List<MachineTransition> transitions = new ArrayList<>();
        for (MachineTransition t : m.transitions()) {
            transitions.add(new MachineTransition(t.from(), t.to(), from.equals(t.clip()) ? to : t.clip(), t.blend(),
                from.equals(t.reduced()) ? to : t.reduced(), t.unknown()));
        }
        return new UiStateMachine(m.id(), m.driver(), m.element(), m.initial(), states, transitions, m.unknown());
    }

    /** The {@code clip} prop of animation graph nodes ({@code ui:anim.*}) in the event graph and functions. */
    private static UiGraph renameIn(UiGraph g, String from, String to) {
        List<UiGraph.GraphFunction> functions = new ArrayList<>();
        for (UiGraph.GraphFunction f : g.functions()) {
            functions.add(new UiGraph.GraphFunction(f.id(), f.inputs(), f.outputs(), renameNodes(f.nodes(), from, to),
                f.edges(), f.unknown()));
        }
        return new UiGraph(g.id(), g.variables(), renameNodes(g.nodes(), from, to), g.edges(), functions, g.unknown());
    }

    private static List<UiGraph.GraphNode> renameNodes(List<UiGraph.GraphNode> nodes, String from, String to) {
        List<UiGraph.GraphNode> out = new ArrayList<>();
        for (UiGraph.GraphNode n : nodes) {
            if (n.kind().startsWith("ui:anim.") && n.props().get("clip") instanceof UiValue.Str s
                && s.value().equals(from)) {
                Map<String, UiValue> props = new LinkedHashMap<>(n.props());
                props.put("clip", UiValue.of(to));
                n = new UiGraph.GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(), n.inputs(), props,
                    n.unknown());
            }
            out.add(n);
        }
        return out;
    }
}
