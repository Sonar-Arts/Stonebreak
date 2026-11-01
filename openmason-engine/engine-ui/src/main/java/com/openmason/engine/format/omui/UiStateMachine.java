package com.openmason.engine.format.omui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code animations/<id>.states.json} (feature {@code ui-states}, #295): a named UI state
 * machine that poses one document scope through clips. It never replaces a gameplay state
 * machine; it only chooses which clip plays.
 *
 * <ul>
 *   <li>{@link Driver#INTERACTION}: the {@code element}'s pseudo-states pick the state, in the
 *       order disabled, pressed, hover, focused, normal (the first one declared wins);</li>
 *   <li>{@link Driver#MANUAL}: scripts and graphs set the state ({@code ui.state}); screen
 *       transitions such as open/closed.</li>
 * </ul>
 * Entering a state plays the matching transition's clip (an exact {@code from → to} beats
 * {@code * → to}), then the state's own clip. Without either, the machine's channels go back
 * to the cascade.
 *
 * @param element node id whose pseudo-states drive an interaction machine; null for manual
 * @param initial state entered when the scope opens, without a transition
 * @param states  sorted by name
 * @param transitions sorted by (from, to)
 */
public record UiStateMachine(String id, Driver driver, String element, String initial, List<MachineState> states,
                             List<MachineTransition> transitions, Map<String, UiValue> unknown) {

    /** {@code from} of a transition taken from any state. */
    public static final String ANY = "*";

    /** The interaction states, highest priority first. */
    public static final List<String> INTERACTION_STATES = List.of("disabled", "pressed", "hover", "focused", "normal");

    public UiStateMachine {
        Objects.requireNonNull(id, "id");
        driver = driver == null ? Driver.MANUAL : driver;
        Objects.requireNonNull(initial, "initial");
        states = Canon.sortedBy(states, MachineState::name);
        transitions = Canon.sortedBy(transitions, MachineTransition.ORDER);
        unknown = Canon.unknown(unknown);
    }

    public enum Driver implements WireEnum {
        MANUAL("manual"), INTERACTION("interaction");

        private final String wire;

        Driver(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** @param clip clip held (or looped) while in this state; null = the cascade */
    public record MachineState(String name, String clip, Map<String, UiValue> unknown) {
        public MachineState {
            Objects.requireNonNull(name, "name");
            unknown = Canon.unknown(unknown);
        }
    }

    /**
     * @param from    a state name or {@link #ANY}
     * @param clip    played on the way in; null = straight to the target state's clip
     * @param blend   seconds over which the first clip cross-fades from what is shown
     * @param reduced clip played instead under reduced motion; null = jump to the state
     */
    public record MachineTransition(String from, String to, String clip, double blend, String reduced,
                                    Map<String, UiValue> unknown) {
        static final Comparator<MachineTransition> ORDER = Comparator
                .comparing(MachineTransition::from, UiValue.KEY_ORDER)
                .thenComparing(MachineTransition::to, UiValue.KEY_ORDER);

        public MachineTransition {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            blend = Canon.num(blend);
            unknown = Canon.unknown(unknown);
        }
    }

    public MachineState state(String name) {
        for (MachineState s : states) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** The transition into {@code to} from {@code from}: exact first, then {@code * → to}; or null. */
    public MachineTransition transition(String from, String to) {
        MachineTransition any = null;
        for (MachineTransition t : transitions) {
            if (t.to().equals(to)) {
                if (t.from().equals(from)) {
                    return t;
                }
                if (ANY.equals(t.from())) {
                    any = t;
                }
            }
        }
        return any;
    }

    /** Every clip id this machine plays. */
    public Set<String> clips() {
        Set<String> out = new java.util.TreeSet<>();
        states.forEach(s -> addIf(out, s.clip()));
        transitions.forEach(t -> {
            addIf(out, t.clip());
            addIf(out, t.reduced());
        });
        return out;
    }

    private static void addIf(Set<String> out, String id) {
        if (id != null) {
            out.add(id);
        }
    }
}
