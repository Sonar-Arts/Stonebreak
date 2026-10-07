package com.openmason.main.systems.uiEditor.timeline;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiBezier;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure edits of a timeline clip (#295): every Timeline gesture is one of these, wrapped in a
 * command so it is one undo step. Tracks are identified by (target, property) and keys by their
 * time, never by index, because the format keeps both sorted. Edits that would make the clip
 * invalid (two keys at one time, a key after the end) throw {@link IllegalArgumentException}
 * with a message for the author.
 */
public final class ClipEdits {

    /** Keys closer than this are the same key (one 60 Hz frame is ~0.0167 s). */
    public static final double EPS = 1e-6;

    /** A track by identity. */
    public record TrackId(String target, String property) {
        public static TrackId of(AnimTrack t) {
            return new TrackId(t.target(), t.property());
        }
    }

    /** A key by identity: its track and time. */
    public record KeyId(TrackId track, double time) {
    }

    /** A copied key, relative to the earliest copied key. */
    public record CopiedKey(String property, double offset, UiValue value, UiEasing easing, UiBezier bezier) {
    }

    /** A move's result: the clip and the keys' new identities (the selection follows them). */
    public record Moved(UiAnimationClip clip, List<KeyId> keys) {
    }

    private ClipEdits() {
    }

    public static UiAnimationClip create(String id, double duration) {
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a valid clip id (lowercase, digits, _ - /)");
        }
        return new UiAnimationClip(id, positive(duration), LoopMode.ONCE, List.of(), List.of(), Map.of());
    }

    public static UiAnimationClip rename(UiAnimationClip c, String id) {
        create(id, 1);
        return new UiAnimationClip(id, c.duration(), c.loop(), c.tracks(), c.events(), c.unknown());
    }

    /** A shorter duration may not cut off keys or events. */
    public static UiAnimationClip setDuration(UiAnimationClip c, double duration) {
        double d = positive(duration);
        double last = lastTime(c);
        if (last > d + EPS) {
            throw new IllegalArgumentException("keys or events at " + format(last) + " s lie after " + format(d) + " s");
        }
        return new UiAnimationClip(c.id(), d, c.loop(), c.tracks(), c.events(), c.unknown());
    }

    public static UiAnimationClip setLoop(UiAnimationClip c, LoopMode loop) {
        return new UiAnimationClip(c.id(), c.duration(), loop, c.tracks(), c.events(), c.unknown());
    }

    // ── tracks ──────────────────────────────────────────────────────────────

    public static UiAnimationClip addTrack(UiAnimationClip c, String target, String property) {
        if (track(c, new TrackId(target, property)) != null) {
            throw new IllegalArgumentException("the clip already animates " + property + " on " + target);
        }
        List<AnimTrack> tracks = new ArrayList<>(c.tracks());
        tracks.add(new AnimTrack(target, property, List.of(), Map.of()));
        return withTracks(c, tracks);
    }

    public static UiAnimationClip removeTrack(UiAnimationClip c, TrackId id) {
        List<AnimTrack> tracks = new ArrayList<>(c.tracks());
        if (!tracks.removeIf(t -> TrackId.of(t).equals(id))) {
            throw new IllegalArgumentException("no track " + id.property() + " on " + id.target());
        }
        return withTracks(c, tracks);
    }

    public static AnimTrack track(UiAnimationClip c, TrackId id) {
        for (AnimTrack t : c.tracks()) {
            if (TrackId.of(t).equals(id)) {
                return t;
            }
        }
        return null;
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /** Sets the key at {@code time} (replacing one there), creating the track when needed. */
    public static UiAnimationClip setKey(UiAnimationClip c, TrackId id, double time, UiValue value, UiEasing easing) {
        double t = inside(c, time);
        AnimTrack track = track(c, id);
        List<AnimKey> keys = new ArrayList<>(track == null ? List.of() : track.keys());
        AnimKey existing = keyAt(keys, t);
        UiEasing e = easing != null ? easing : existing != null ? existing.easing() : UiEasing.LINEAR;
        UiBezier b = easing == null && existing != null ? existing.bezier() : null;
        keys.remove(existing);
        keys.add(new AnimKey(t, value, e, b, existing == null ? Map.of() : existing.unknown()));
        return withKeys(c, id, keys);
    }

    /** A named easing (clears a custom curve). */
    public static UiAnimationClip setEasing(UiAnimationClip c, KeyId key, UiEasing easing) {
        AnimKey k = require(c, key);
        return replace(c, key, new AnimKey(k.time(), k.value(), easing, null, k.unknown()));
    }

    /** A custom cubic-bezier curve for the segment after {@code key} (null = back to its named easing). */
    public static UiAnimationClip setCurve(UiAnimationClip c, KeyId key, UiBezier curve) {
        if (curve != null && curve.problem() != null) {
            throw new IllegalArgumentException(curve.problem());
        }
        AnimKey k = require(c, key);
        return replace(c, key, new AnimKey(k.time(), k.value(), k.easing(), curve, k.unknown()));
    }

    public static UiAnimationClip setValue(UiAnimationClip c, KeyId key, UiValue value) {
        AnimKey k = require(c, key);
        return replace(c, key, new AnimKey(k.time(), value, k.easing(), k.bezier(), k.unknown()));
    }

    public static UiAnimationClip removeKeys(UiAnimationClip c, Collection<KeyId> keys) {
        UiAnimationClip out = c;
        for (KeyId k : keys) {
            AnimKey existing = require(out, k);
            AnimTrack t = track(out, k.track());
            List<AnimKey> rest = new ArrayList<>(t.keys());
            rest.remove(existing);
            out = withKeys(out, k.track(), rest);
        }
        return out;
    }

    /**
     * Moves {@code keys} by {@code dt} seconds (clamped so the group stays inside the clip).
     *
     * @throws IllegalArgumentException when a moved key would land on another key
     */
    public static Moved moveKeys(UiAnimationClip c, Collection<KeyId> keys, double dt) {
        if (keys.isEmpty()) {
            return new Moved(c, List.of());
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (KeyId k : keys) {
            require(c, k);
            min = Math.min(min, k.time());
            max = Math.max(max, k.time());
        }
        double shift = Math.clamp(dt, -min, c.duration() - max);
        UiAnimationClip out = c;
        List<KeyId> moved = new ArrayList<>();
        Set<KeyId> moving = new HashSet<>(keys);
        for (AnimTrack t : c.tracks()) {
            TrackId id = TrackId.of(t);
            List<AnimKey> next = new ArrayList<>();
            List<AnimKey> stay = new ArrayList<>();
            for (AnimKey k : t.keys()) {
                if (moving.contains(new KeyId(id, k.time()))) {
                    double nt = round(k.time() + shift);
                    next.add(new AnimKey(nt, k.value(), k.easing(), k.bezier(), k.unknown()));
                    moved.add(new KeyId(id, nt));
                } else {
                    stay.add(k);
                }
            }
            if (next.isEmpty()) {
                continue;
            }
            for (AnimKey k : next) {
                if (keyAt(stay, k.time()) != null) {
                    throw new IllegalArgumentException("a key of " + id.property() + " is already at "
                        + format(k.time()) + " s");
                }
            }
            stay.addAll(next);
            out = withKeys(out, id, stay);
        }
        return new Moved(out, moved);
    }

    /** Copies {@code keys} relative to the earliest; empty for no keys. */
    public static List<CopiedKey> copy(UiAnimationClip c, Collection<KeyId> keys) {
        double first = keys.stream().mapToDouble(KeyId::time).min().orElse(0);
        List<CopiedKey> out = new ArrayList<>();
        for (KeyId k : keys) {
            AnimKey key = require(c, k);
            out.add(new CopiedKey(k.track().property(), k.time() - first, key.value(), key.easing(), key.bezier()));
        }
        out.sort(Comparator.comparingDouble(CopiedKey::offset));
        return out;
    }

    /**
     * Pastes copied keys at {@code at}: keys of one property go to {@code into}; keys of several
     * properties go to the tracks of those properties on {@code into}'s element (created as
     * needed). Keys past the clip's end are dropped; existing keys at the same time are replaced.
     */
    public static UiAnimationClip paste(UiAnimationClip c, TrackId into, double at, List<CopiedKey> copied) {
        boolean oneProperty = copied.stream().map(CopiedKey::property).distinct().count() <= 1;
        UiAnimationClip out = c;
        for (CopiedKey k : copied) {
            double t = round(at + k.offset());
            if (t < 0 || t > c.duration() + EPS) {
                continue;
            }
            TrackId target = oneProperty ? into : new TrackId(into.target(), k.property());
            out = setKey(out, target, t, k.value(), k.easing());
            if (k.bezier() != null) {
                out = setCurve(out, new KeyId(target, t), k.bezier());
            }
        }
        return out;
    }

    // ── events ──────────────────────────────────────────────────────────────

    public static UiAnimationClip addEvent(UiAnimationClip c, double time, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an event needs a name");
        }
        List<AnimEvent> events = new ArrayList<>(c.events());
        events.add(new AnimEvent(inside(c, time), name.trim(), Map.of()));
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), c.tracks(), events, c.unknown());
    }

    public static UiAnimationClip removeEvent(UiAnimationClip c, int index) {
        List<AnimEvent> events = new ArrayList<>(c.events());
        events.remove(index);
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), c.tracks(), events, c.unknown());
    }

    public static UiAnimationClip moveEvent(UiAnimationClip c, int index, double time) {
        List<AnimEvent> events = new ArrayList<>(c.events());
        AnimEvent e = events.remove(index);
        events.add(new AnimEvent(inside(c, time), e.name(), e.unknown()));
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), c.tracks(), events, c.unknown());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Keys and times snap to microseconds so drags never produce 0.30000000000000004. */
    public static double round(double t) {
        return Math.round(t * 1e6) / 1e6;
    }

    public static String format(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    private static double positive(double d) {
        if (!(d > 0) || d > OmuiFormat.MAX_SECONDS) {
            throw new IllegalArgumentException("a clip lasts more than 0 and at most " + (int) OmuiFormat.MAX_SECONDS + " s");
        }
        return round(d);
    }

    private static double inside(UiAnimationClip c, double time) {
        if (!(time >= -EPS) || time > c.duration() + EPS) {
            throw new IllegalArgumentException(format(time) + " s lies outside the clip (0 to " + format(c.duration())
                + " s)");
        }
        return round(Math.clamp(time, 0, c.duration()));
    }

    private static double lastTime(UiAnimationClip c) {
        double last = 0;
        for (AnimTrack t : c.tracks()) {
            for (AnimKey k : t.keys()) {
                last = Math.max(last, k.time());
            }
        }
        for (AnimEvent e : c.events()) {
            last = Math.max(last, e.time());
        }
        return last;
    }

    private static AnimKey keyAt(List<AnimKey> keys, double t) {
        for (AnimKey k : keys) {
            if (Math.abs(k.time() - t) <= EPS) {
                return k;
            }
        }
        return null;
    }

    private static AnimKey require(UiAnimationClip c, KeyId id) {
        AnimTrack t = track(c, id.track());
        AnimKey k = t == null ? null : keyAt(t.keys(), id.time());
        if (k == null) {
            throw new IllegalArgumentException("no key of " + id.track().property() + " at " + format(id.time()) + " s");
        }
        return k;
    }

    private static UiAnimationClip replace(UiAnimationClip c, KeyId id, AnimKey key) {
        AnimTrack t = track(c, id.track());
        List<AnimKey> keys = new ArrayList<>(t.keys());
        keys.replaceAll(k -> Math.abs(k.time() - id.time()) <= EPS ? key : k);
        return withKeys(c, id.track(), keys);
    }

    private static UiAnimationClip withKeys(UiAnimationClip c, TrackId id, List<AnimKey> keys) {
        List<AnimKey> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(AnimKey::time));
        List<AnimTrack> tracks = new ArrayList<>();
        boolean found = false;
        for (AnimTrack t : c.tracks()) {
            if (TrackId.of(t).equals(id)) {
                tracks.add(new AnimTrack(t.target(), t.property(), sorted, t.unknown()));
                found = true;
            } else {
                tracks.add(t);
            }
        }
        if (!found) {
            tracks.add(new AnimTrack(id.target(), id.property(), sorted, Map.of()));
        }
        return withTracks(c, tracks);
    }

    private static UiAnimationClip withTracks(UiAnimationClip c, List<AnimTrack> tracks) {
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), tracks, c.events(), c.unknown());
    }
}
