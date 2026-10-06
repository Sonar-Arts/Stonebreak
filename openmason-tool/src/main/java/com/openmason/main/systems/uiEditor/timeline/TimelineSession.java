package com.openmason.main.systems.uiEditor.timeline;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.anim.AnimProperty;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.CopiedKey;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.KeyId;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.TrackId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Timeline's per-document working state (#295): playhead, key selection, selected track and
 * the scrubber, plus the gestures that turn into commands. GL- and ImGui-free, so the editing rules
 * are tested headlessly; the panel only draws and routes input here.
 */
public final class TimelineSession {

    /** Keys copied with Ctrl+C, shared by every document (paste across clips and documents). */
    private static List<CopiedKey> clipboard = List.of();

    public final TimelineScrubber scrubber = new TimelineScrubber();
    public final Set<KeyId> selection = new LinkedHashSet<>();
    public TrackId selectedTrack;
    private double playhead;

    public double playhead() {
        return playhead;
    }

    public void setPlayhead(double seconds) {
        playhead = Math.max(0, seconds);
    }

    /** Snaps {@code t} to {@code fps} frames (0 = no snapping). */
    public static double snap(double t, double fps) {
        return fps <= 0 ? ClipEdits.round(t) : ClipEdits.round(Math.round(t * fps) / fps);
    }

    /** Drops selected keys and the track the clip no longer has (after undo, another clip). */
    public void retain(UiAnimationClip clip) {
        if (clip == null) {
            selection.clear();
            selectedTrack = null;
            return;
        }
        selection.removeIf(k -> !exists(clip, k));
        if (selectedTrack != null && ClipEdits.track(clip, selectedTrack) == null) {
            selectedTrack = null;
        }
        playhead = Math.min(playhead, clip.duration());
    }

    private static boolean exists(UiAnimationClip clip, KeyId k) {
        AnimTrack t = ClipEdits.track(clip, k.track());
        if (t == null) {
            return false;
        }
        for (AnimKey key : t.keys()) {
            if (Math.abs(key.time() - k.time()) <= ClipEdits.EPS) {
                return true;
            }
        }
        return false;
    }

    // ── gestures → commands ─────────────────────────────────────────────────

    public UiCommand deleteSelected(UiAnimationClip clip) {
        if (selection.isEmpty()) {
            return null;
        }
        List<KeyId> keys = List.copyOf(selection);
        selection.clear();
        return AnimationCommands.editClip("Delete " + keys.size() + (keys.size() == 1 ? " key" : " keys"), clip.id(),
            null, c -> ClipEdits.removeKeys(c, keys));
    }

    public int copy(UiAnimationClip clip) {
        if (selection.isEmpty()) {
            return 0;
        }
        clipboard = ClipEdits.copy(clip, selection);
        return clipboard.size();
    }

    public static boolean canPaste() {
        return !clipboard.isEmpty();
    }

    /** Pastes the clipboard at the playhead into the selected track (or the first selected key's). */
    public UiCommand paste(UiAnimationClip clip) {
        TrackId into = selectedTrack != null ? selectedTrack
            : selection.isEmpty() ? null : selection.iterator().next().track();
        if (into == null || clipboard.isEmpty()) {
            return null;
        }
        List<CopiedKey> keys = clipboard;
        double at = playhead;
        selection.clear();
        for (CopiedKey k : keys) {
            double t = ClipEdits.round(at + k.offset());
            if (t <= clip.duration() + ClipEdits.EPS) {
                selection.add(new KeyId(keys.stream().map(CopiedKey::property).distinct().count() <= 1 ? into
                    : new TrackId(into.target(), k.property()), t));
            }
        }
        return AnimationCommands.editClip("Paste " + keys.size() + (keys.size() == 1 ? " key" : " keys"), clip.id(),
            null, c -> ClipEdits.paste(c, into, at, keys));
    }

    /**
     * A key on {@code track} at the playhead holding what that track shows there now: the track's
     * own value when it has keys, else what the element shows (the cascade).
     */
    public UiCommand keyAtPlayhead(UiAnimationClip clip, TrackId track, UiDocumentInstance ui) {
        UiValue value = valueAt(clip, track, playhead, ui);
        if (value == null) {
            return null;
        }
        double t = Math.min(playhead, clip.duration());
        selection.clear();
        selection.add(new KeyId(track, ClipEdits.round(t)));
        selectedTrack = track;
        return AnimationCommands.editClip("Add key", clip.id(), null,
            c -> ClipEdits.setKey(c, track, t, value, null));
    }

    /** The value {@code track} shows at {@code t}: sampled from its keys, else the element's own, else null. */
    public static UiValue valueAt(UiAnimationClip clip, TrackId track, double t, UiDocumentInstance ui) {
        UiElement el = ui == null ? null : ui.find(track.target());
        AnimProperty p = property(track, el);
        AnimTrack existing = ClipEdits.track(clip, track);
        if (existing != null && !existing.keys().isEmpty() && p != null) {
            return p.sample(existing.keys(), t);
        }
        if (el == null || p == null) {
            return null;
        }
        UiValue shown = p.isStyle() ? el.baseStyle().get(p.name()) : el.prop(p.name());
        return shown == null || shown == UiValue.NULL ? p.neutral(null) : shown;
    }

    /** The channel type of {@code track} on {@code el} (style tracks resolve without an element). */
    public static AnimProperty property(TrackId track, UiElement el) {
        if (track.property().startsWith(AnimProperty.STYLE)) {
            return AnimProperty.style(track.property().substring(AnimProperty.STYLE.length()));
        }
        return el == null ? null : AnimProperty.of(track.property(), el.descriptor());
    }

    /** Easing for every selected key. */
    public UiCommand setEasing(UiAnimationClip clip, UiEasing easing) {
        if (selection.isEmpty()) {
            return null;
        }
        List<KeyId> keys = new ArrayList<>(selection);
        return AnimationCommands.editClip("Set easing", clip.id(), null, c -> {
            UiAnimationClip out = c;
            for (KeyId k : keys) {
                out = ClipEdits.setEasing(out, k, easing);
            }
            return out;
        });
    }
}
