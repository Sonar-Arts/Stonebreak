package com.openmason.engine.ui.runtime.anim;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The time sources of one document instance (#295). Every animation samples one clock by its
 * absolute reading, so a clip sampled at the same clock time always shows the same frame.
 *
 * <ul>
 *   <li>{@link #UI}: unscaled UI time; advanced by {@code UiDocumentView.frame(dt)} every frame,
 *       including while gameplay is paused (pause menus animate). Style transitions use it.</li>
 *   <li>{@link #GAME}: gameplay time; the host advances it only while the game runs, so a clip on
 *       it freezes with the game.</li>
 *   <li>external clocks ({@code battle}, {@code intro}): defined and set by the host from its own
 *       deterministic time (an encounter's simulated seconds), never from the frame clock.</li>
 * </ul>
 * Clocks never run backwards on their own; {@link #set} may rewind an external clock (a replay),
 * and animations on it then sample the earlier time.
 */
public final class UiClocks {

    public static final String UI = "ui";
    public static final String GAME = "game";

    private final Map<String, double[]> clocks = new LinkedHashMap<>();

    public UiClocks() {
        clocks.put(UI, new double[1]);
        clocks.put(GAME, new double[1]);
    }

    /** @throws IllegalArgumentException for a clock nobody defined */
    public double now(String name) {
        double[] c = clocks.get(name);
        if (c == null) {
            throw new IllegalArgumentException("no clock '" + name + "' (clocks: " + clocks.keySet() + ")");
        }
        return c[0];
    }

    public boolean has(String name) {
        return clocks.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(clocks.keySet());
    }

    /** Defines an external clock at 0 (a no-op when it exists). */
    public void define(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("clock name must not be blank");
        }
        clocks.putIfAbsent(name, new double[1]);
    }

    /** Advances {@code name} by {@code dt} seconds; non-positive or non-finite steps are ignored. */
    public void advance(String name, double dt) {
        if (dt > 0 && Double.isFinite(dt)) {
            double[] c = clocks.get(name);
            if (c == null) {
                throw new IllegalArgumentException("no clock '" + name + "'");
            }
            c[0] += dt;
        }
    }

    /** Sets an external clock to {@code seconds} (defining it); the UI clock is the frame's and cannot be set. */
    public void set(String name, double seconds) {
        if (UI.equals(name)) {
            throw new IllegalArgumentException("the ui clock follows frames; advance it instead");
        }
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("clock time must be finite");
        }
        define(name);
        clocks.get(name)[0] = seconds;
    }
}
