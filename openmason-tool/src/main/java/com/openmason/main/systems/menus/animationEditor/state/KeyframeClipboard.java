package com.openmason.main.systems.menus.animationEditor.state;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-local clipboard for keyframes. Copy normalizes times so the earliest
 * copied keyframe sits at 0 — paste then offsets everything by the playhead.
 * Entries carry the source part's name hint so a future cross-model paste
 * could rebind by name (paste today targets the same partId).
 */
public final class KeyframeClipboard {

    /** One copied keyframe with its source track identity. */
    public record Entry(String partId, String partNameHint, Keyframe keyframe) {}

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Snapshot the given selection out of the clip. Times are re-based so the
     * earliest keyframe is at 0. Returns the number of keyframes copied.
     */
    public int copyFrom(AnimationClip clip, KeyframeSelection selection) {
        List<KeyframeSelection.ResolvedKey> resolved = selection.resolve(clip);
        if (resolved.isEmpty()) return 0;

        float earliest = Float.MAX_VALUE;
        for (KeyframeSelection.ResolvedKey key : resolved) {
            earliest = Math.min(earliest, key.keyframe().time());
        }

        entries.clear();
        for (KeyframeSelection.ResolvedKey key : resolved) {
            Track track = clip.trackFor(key.partId());
            String hint = track != null ? track.partNameHint() : null;
            entries.add(new Entry(key.partId(), hint,
                    key.keyframe().withTime(key.keyframe().time() - earliest)));
        }
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** Clipboard contents (times normalized to earliest = 0). Defensive copy. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }
}
