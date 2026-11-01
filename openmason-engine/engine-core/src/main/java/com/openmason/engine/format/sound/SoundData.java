package com.openmason.engine.format.sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional {@code sounds[]} block embedded in an SBO (1.7+) or SBE (1.4+)
 * manifest: a flat list of {@link SoundDef} bindings from event names to
 * audio samples.
 *
 * <p>The four standard block events cover the vanilla interactions —
 * {@link #EVENT_BREAK} (block destroyed), {@link #EVENT_HIT} (being
 * demolished/attacked while breaking), {@link #EVENT_PLACE} (placed in the
 * world), {@link #EVENT_STEP} (walked on) — but event names are free-form:
 * an asset may declare any additional events (e.g. an entity's
 * {@code "hurt"}/{@code "death"}, a door's {@code "open"}) and the runtime
 * looks them up by name.
 *
 * @param sounds sound defs; never null but may be empty
 */
public record SoundData(List<SoundDef> sounds) {

    /** Standard event: the block was destroyed. */
    public static final String EVENT_BREAK = "break";
    /** Standard event: the block is being demolished/attacked (crack progress). */
    public static final String EVENT_HIT = "hit";
    /** Standard event: the block was placed in the world. */
    public static final String EVENT_PLACE = "place";
    /** Standard event: an entity stepped/walked on the block. */
    public static final String EVENT_STEP = "step";

    public SoundData {
        sounds = sounds == null ? Collections.emptyList() : List.copyOf(sounds);
    }

    /** All defs bound to {@code event} (case-sensitive); empty when none. */
    public List<SoundDef> defsFor(String event) {
        if (event == null || sounds.isEmpty()) return List.of();
        List<SoundDef> matches = new ArrayList<>();
        for (SoundDef def : sounds) {
            if (def.event().equals(event)) matches.add(def);
        }
        return matches;
    }

    /** Distinct event names declared, in manifest order. */
    public Set<String> events() {
        Set<String> names = new LinkedHashSet<>();
        for (SoundDef def : sounds) names.add(def.event());
        return names;
    }

    public boolean isEmpty() {
        return sounds.isEmpty();
    }
}
