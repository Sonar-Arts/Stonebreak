package com.stonebreak.battle.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered run of shots. Each step says how it is entered (cut or blend from whatever was on
 * screen) and what moves the sequence on: its own duration, or a battle event (so a cut lands on the
 * blow, not on a guessed time). A sequence is finished once its last step advances.
 */
public record ShotSequence(String name, List<Step> steps) {

    public ShotSequence {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("sequence " + name + " has no shots");
    }

    /** How a step takes over the screen. */
    public record Transition(boolean blend, float seconds) {
        public static final Transition CUT = new Transition(false, 0f);
        public static Transition blend(float seconds) { return new Transition(true, seconds); }
    }

    /** What advances a step. Event-synced steps keep playing (hold / loop) until the event arrives. */
    public enum AdvanceOn { TIME, IMPACT, ACTION_FINISHED, NEVER }

    public record Step(CameraShot shot, Transition entry, AdvanceOn advanceOn) {}

    /** First step's entry transition = how the whole sequence takes over from the previous one. */
    public Transition entry() { return steps.getFirst().entry(); }

    public static Builder sequence(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private final List<Step> steps = new ArrayList<>();

        private Builder(String name) { this.name = name; }

        public Builder cut(CameraShot shot, AdvanceOn advanceOn) {
            steps.add(new Step(shot, Transition.CUT, advanceOn));
            return this;
        }

        public Builder blend(float seconds, CameraShot shot, AdvanceOn advanceOn) {
            steps.add(new Step(shot, Transition.blend(seconds), advanceOn));
            return this;
        }

        public ShotSequence build() { return new ShotSequence(name, steps); }
    }
}
