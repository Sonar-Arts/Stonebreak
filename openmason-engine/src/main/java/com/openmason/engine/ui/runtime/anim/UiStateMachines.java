package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentInstance.AuthoringScope;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the UI state machines ({@code animations/<id>.states.json}, #295) of every authoring
 * scope of an instance: the screen and each component instance, so every button instance has its
 * own. Interaction machines follow their element's pseudo-states each frame; manual ones change
 * when a script or graph calls {@link #set}.
 *
 * <p>Entering a state plays the transition's clip (or its reduced alternate under reduced
 * motion), then the state's clip; channels the new clips do not animate go back to the cascade
 * (through declared style transitions). The first clip blends from what is shown over the
 * transition's {@code blend}.
 */
public final class UiStateMachines {

    /** One running machine of one scope. */
    private final class Machine {
        /** Replaced on every sync: a reload brings a new archive revision (edited clips) for the same machine. */
        AuthoringScope scope;
        final UiStateMachine def;
        String state;
        long token;
        int generation;
        int plays;

        Machine(AuthoringScope scope, UiStateMachine def) {
            this.scope = scope;
            this.def = def;
        }
    }

    private record Key(String scope, String id) {
    }

    private final UiDocumentInstance ui;
    private final UiAnimator animator;
    private final Map<Key, Machine> machines = new LinkedHashMap<>();

    public UiStateMachines(UiDocumentInstance ui, UiAnimator animator) {
        this.ui = ui;
        this.animator = animator;
    }

    /**
     * Starts machines of new scopes in their initial state and stops those whose scope is gone.
     * A machine whose definition is unchanged keeps its state but reads clips from the new
     * revision; when the clip of its current state changed (a Timeline edit), that clip restarts.
     * A replaced or removed machine hands everything it held back to the cascade.
     */
    public void sync(List<AuthoringScope> scopes) {
        Set<Key> live = new HashSet<>();
        for (AuthoringScope s : scopes) {
            for (UiStateMachine def : s.archive().stateMachines().values()) {
                Key k = new Key(s.instanceKey(), def.id());
                live.add(k);
                Machine m = machines.get(k);
                if (m != null && m.def.equals(def)) {
                    AuthoringScope before = m.scope;
                    m.scope = s;
                    if (before.archive() != s.archive() && m.state != null && stateClipChanged(m, before, s)) {
                        m.generation++; // a pending transition clip must not chain into the old state clip
                        playState(m, 0, null);
                    }
                    continue;
                }
                if (m != null) {
                    animator.clearAndRelease(m);
                }
                start(k, s, def);
            }
        }
        machines.entrySet().removeIf(e -> {
            if (!live.contains(e.getKey())) {
                animator.clearAndRelease(e.getValue());
                return true;
            }
            return false;
        });
    }

    /**
     * Machines of the scopes {@code instanceKeys} start over in their initial state, as if just
     * built (a recycled list row now shows another item, #325). Whatever they held goes back to
     * the cascade first.
     */
    public void restart(Set<String> instanceKeys) {
        List<Map.Entry<Key, Machine>> stale = new ArrayList<>();
        for (Map.Entry<Key, Machine> e : machines.entrySet()) {
            if (instanceKeys.contains(e.getKey().scope)) {
                stale.add(e);
            }
        }
        for (Map.Entry<Key, Machine> e : stale) {
            Machine m = e.getValue();
            m.generation++; // a pending transition clip must not chain into the old state's clip
            animator.clearAndRelease(m);
            start(e.getKey(), m.scope, m.def);
        }
    }

    private void start(Key k, AuthoringScope s, UiStateMachine def) {
        Machine fresh = new Machine(s, def);
        machines.put(k, fresh);
        String first = def.driver() == UiStateMachine.Driver.INTERACTION ? interactionState(fresh) : null;
        enter(fresh, first != null ? first : def.initial(), false, null);
    }

    private static boolean stateClipChanged(Machine m, AuthoringScope before, AuthoringScope after) {
        UiStateMachine.MachineState s = m.def.state(m.state);
        String id = s == null ? null : s.clip();
        return id != null && !Objects.equals(before.archive().animations().get(id), after.archive().animations().get(id));
    }

    /** Interaction machines follow their element. Once per frame, before sampling. */
    public void poll() {
        for (Machine m : machines.values()) {
            if (m.def.driver() == UiStateMachine.Driver.INTERACTION) {
                String want = interactionState(m);
                if (want != null && !want.equals(m.state)) {
                    enter(m, want, true, null);
                }
            }
        }
    }

    /**
     * Moves machine {@code id} of scope {@code scopeKey} ({@code ""} = the screen) to {@code state}.
     *
     * @return the token of the clip that plays first (its completion = the state was reached), or
     *         0 when nothing plays (already there, or no clip)
     * @throws IllegalArgumentException for an unknown machine or state
     */
    public long set(String scopeKey, String id, String state, UiAnimator.Listener listener) {
        Machine m = machines.get(new Key(scopeKey, id));
        if (m == null) {
            throw new IllegalArgumentException("no state machine '" + id + "'" + (scopeKey.isEmpty() ? ""
                : " in " + scopeKey) + " (machines: " + ids(scopeKey) + ")");
        }
        if (m.def.state(state) == null) {
            throw new IllegalArgumentException("state machine " + id + " has no state '" + state + "'");
        }
        if (state.equals(m.state)) {
            return 0;
        }
        return enter(m, state, true, listener);
    }

    /** Current state of a machine, or null when there is none. */
    public String state(String scopeKey, String id) {
        Machine m = machines.get(new Key(scopeKey, id));
        return m == null ? null : m.state;
    }

    /** Machine ids of one scope. */
    public List<String> ids(String scopeKey) {
        List<String> out = new ArrayList<>();
        machines.keySet().forEach(k -> {
            if (k.scope.equals(scopeKey)) {
                out.add(k.id);
            }
        });
        return out;
    }

    /** Stops every machine's clips (screen close). */
    public void clear() {
        machines.values().forEach(animator::clear);
        machines.clear();
    }

    private long enter(Machine m, String state, boolean withTransition, UiAnimator.Listener listener) {
        String from = m.state;
        m.state = state;
        int gen = ++m.generation;
        MachineTransition t = withTransition && from != null ? m.def.transition(from, state) : null;
        String transitionClip = t == null ? null : ui.preferences().reducedMotion() ? t.reduced() : t.clip();
        double blend = t == null ? 0 : t.blend();
        UiAnimationClip c = clip(m, transitionClip);
        if (c != null) {
            return play(m, c, blend, (token, stopped) -> {
                if (!stopped && m.generation == gen) {
                    playState(m, 0, null);
                }
                if (listener != null) {
                    listener.finished(token, stopped);
                }
            });
        }
        return playState(m, blend, listener);
    }

    /**
     * Plays the current state's clip, or hands the machine's channels back when it has none.
     *
     * @return the clip's token when it is one-shot, else 0 (the state is reached as soon as a
     *         looping clip starts, or at once without a clip)
     */
    private long playState(Machine m, double blend, UiAnimator.Listener listener) {
        UiStateMachine.MachineState s = m.def.state(m.state);
        UiAnimationClip c = clip(m, s == null ? null : s.clip());
        if (c == null) {
            m.token = 0;
            animator.releaseHeld(m, 0);
            return 0;
        }
        boolean once = c.loop() == UiAnimationClip.LoopMode.ONCE;
        long token = play(m, c, blend, once && listener != null ? listener : UiAnimator.Listener.NONE);
        return once ? token : 0;
    }

    /** Plays {@code c} for {@code m}; whatever else {@code m} held goes back to the cascade. */
    private long play(Machine m, UiAnimationClip c, double blend, UiAnimator.Listener listener) {
        int mine = ++m.plays;
        long token = animator.play(m, c, m.scope::keyOf, UiAnimator.PlayOptions.DEFAULTS.withBlend(blend), listener);
        if (m.plays == mine) { // a clip that ended at once may already have chained the next one
            m.token = token;
            animator.releaseHeld(m, token);
        }
        return token;
    }

    private UiAnimationClip clip(Machine m, String clipId) {
        if (clipId == null) {
            return null;
        }
        UiAnimationClip c = m.scope.archive().animations().get(clipId);
        if (c == null) {
            ui.reportDiagnostic(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.STATE_MACHINE,
                m.scope.instanceKey(), "state machine " + m.def.id() + ": no clip '" + clipId + "'"));
        }
        return c;
    }

    private String interactionState(Machine m) {
        UiElement el = m.def.element() == null ? null : ui.find(m.scope.keyOf(m.def.element()));
        if (el == null) {
            return null;
        }
        for (String s : UiStateMachine.INTERACTION_STATES) {
            if (m.def.state(s) != null && active(el, s)) {
                return s;
            }
        }
        return m.def.state("normal") != null ? "normal" : m.def.initial();
    }

    private static boolean active(UiElement el, String state) {
        return switch (state) {
            case "disabled" -> !el.isEnabledInHierarchy();
            case "pressed" -> el.hasState(UiElement.ACTIVE);
            case "hover" -> el.hasState(UiElement.HOVER);
            case "focused" -> el.hasState(UiElement.FOCUS);
            default -> Objects.equals(state, "normal");
        };
    }
}
