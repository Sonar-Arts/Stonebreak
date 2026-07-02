package com.openmason.main.systems.menus.animationEditor.state;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-selection of keyframes across tracks. Keys are stored as
 * {@code (partId, time)} references rather than indices so the selection
 * survives inserts, deletes, and undo/redo that shift indices; refs are
 * resolved lazily against the clip with a small time tolerance.
 */
public final class KeyframeSelection {

    /** Time tolerance matching {@link Track#upsert}'s coincidence threshold. */
    public static final float TIME_EPS = 1e-4f;

    /** A stable reference to one keyframe: its track + its time. */
    public record KeyRef(String partId, float time) {}

    /** A ref resolved to a live keyframe index within the current clip. */
    public record ResolvedKey(String partId, int index, Keyframe keyframe) {}

    private final List<KeyRef> refs = new ArrayList<>();

    public void set(KeyRef ref) {
        refs.clear();
        if (ref != null) refs.add(ref);
    }

    public void add(KeyRef ref) {
        if (ref == null || contains(ref.partId(), ref.time())) return;
        refs.add(ref);
    }

    public void toggle(KeyRef ref) {
        if (ref == null) return;
        for (int i = 0; i < refs.size(); i++) {
            KeyRef r = refs.get(i);
            if (r.partId().equals(ref.partId()) && Math.abs(r.time() - ref.time()) < TIME_EPS) {
                refs.remove(i);
                return;
            }
        }
        refs.add(ref);
    }

    public boolean contains(String partId, float time) {
        for (KeyRef r : refs) {
            if (r.partId().equals(partId) && Math.abs(r.time() - time) < TIME_EPS) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        refs.clear();
    }

    public boolean isEmpty() {
        return refs.isEmpty();
    }

    public int size() {
        return refs.size();
    }

    /** Selection contents, in insertion order. Defensive copy. */
    public List<KeyRef> all() {
        return List.copyOf(refs);
    }

    /**
     * Resolve every ref against the clip. Refs whose track or keyframe no
     * longer exists (deleted, moved beyond tolerance) are silently skipped —
     * callers get only live keyframes.
     */
    public List<ResolvedKey> resolve(AnimationClip clip) {
        List<ResolvedKey> out = new ArrayList<>(refs.size());
        if (clip == null) return out;
        for (KeyRef ref : refs) {
            Track track = clip.trackFor(ref.partId());
            if (track == null) continue;
            for (int i = 0; i < track.size(); i++) {
                if (Math.abs(track.get(i).time() - ref.time()) < TIME_EPS) {
                    out.add(new ResolvedKey(ref.partId(), i, track.get(i)));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Re-point refs after a bulk move: the ref at {@code oldTime} on
     * {@code partId} becomes {@code newTime}. No-op if the ref isn't selected.
     */
    public void retime(String partId, float oldTime, float newTime) {
        for (int i = 0; i < refs.size(); i++) {
            KeyRef r = refs.get(i);
            if (r.partId().equals(partId) && Math.abs(r.time() - oldTime) < TIME_EPS) {
                refs.set(i, new KeyRef(partId, newTime));
                return;
            }
        }
    }
}
