package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.textureCreator.commands.Command;

/**
 * Undo/redo-able mutations on an {@link AnimationClip}. Reuses the existing
 * {@link Command} interface from the texture editor so a single
 * {@code CommandHistory} can drive both editors interchangeably.
 */
public final class KeyframeCommands {

    private KeyframeCommands() {}

    /**
     * Insert or upsert a keyframe on the given part's track. If a keyframe
     * already exists at the same time, it is replaced (and the original is
     * remembered for undo).
     */
    public static Command insert(AnimationClip clip, String partId, Keyframe kf) {
        return new Command() {
            private Keyframe replaced;     // non-null if upsert overwrote a sibling
            private boolean trackCreated;
            private int insertedIndex = -1;

            @Override
            public void execute() {
                trackCreated = clip.trackFor(partId) == null;
                Track track = clip.ensureTrack(partId);
                // capture replacement target if any
                replaced = null;
                for (int i = 0; i < track.size(); i++) {
                    if (Math.abs(track.get(i).time() - kf.time()) < 1e-4f) {
                        replaced = track.get(i);
                        break;
                    }
                }
                insertedIndex = track.upsert(kf);
            }

            @Override
            public void undo() {
                Track track = clip.trackFor(partId);
                if (track == null) return;
                if (replaced != null) {
                    track.set(insertedIndex, replaced);
                } else {
                    track.removeAt(insertedIndex);
                    if (trackCreated && track.isEmpty()) {
                        clip.removeTrack(partId);
                    }
                }
            }

            @Override
            public String getDescription() {
                return "Insert keyframe @ " + kf.time() + "s on " + partId;
            }
        };
    }

    /**
     * Delete the keyframe at {@code index} on the given track.
     */
    public static Command delete(AnimationClip clip, String partId, int index) {
        return new Command() {
            private Keyframe removed;
            private boolean trackEmptied;

            @Override
            public void execute() {
                Track track = clip.trackFor(partId);
                if (track == null || index < 0 || index >= track.size()) return;
                removed = track.get(index);
                track.removeAt(index);
                if (track.isEmpty()) {
                    trackEmptied = true;
                    clip.removeTrack(partId);
                }
            }

            @Override
            public void undo() {
                if (removed == null) return;
                Track track = trackEmptied ? clip.ensureTrack(partId) : clip.trackFor(partId);
                if (track == null) return;
                track.upsert(removed);
            }

            @Override
            public String getDescription() {
                return "Delete keyframe " + index + " on " + partId;
            }
        };
    }

    /**
     * Delete an entire track. The removed track (keyframes + name hint) is
     * snapshotted so undo can recreate it. Re-insertion appends at the end of
     * the clip's track map — ordering is presentational only.
     */
    public static Command deleteTrack(AnimationClip clip, String partId) {
        return new Command() {
            private java.util.List<Keyframe> removedKeyframes;
            private String removedNameHint;

            @Override
            public void execute() {
                Track track = clip.trackFor(partId);
                if (track == null) return;
                removedKeyframes = new java.util.ArrayList<>(track.keyframes());
                removedNameHint = track.partNameHint();
                clip.removeTrack(partId);
            }

            @Override
            public void undo() {
                if (removedKeyframes == null) return;
                Track track = clip.ensureTrack(partId);
                track.setPartNameHint(removedNameHint);
                for (Keyframe kf : removedKeyframes) {
                    track.upsert(kf);
                }
            }

            @Override
            public String getDescription() {
                return "Delete track " + partId;
            }
        };
    }

    /**
     * Replace a track's entire keyframe list with a new one; undo restores the
     * old list wholesale. The safe primitive for bulk moves (timeline drags,
     * paste) where per-keyframe time-based undo would be ambiguous.
     */
    public static Command replaceTrackKeyframes(AnimationClip clip, String partId,
                                                java.util.List<Keyframe> before,
                                                java.util.List<Keyframe> after) {
        java.util.List<Keyframe> beforeCopy = java.util.List.copyOf(before);
        java.util.List<Keyframe> afterCopy = java.util.List.copyOf(after);
        return new Command() {
            private void apply(java.util.List<Keyframe> target) {
                Track track = clip.ensureTrack(partId);
                track.keyframes().clear();
                for (Keyframe kf : target) {
                    track.upsert(kf);
                }
                if (track.isEmpty()) {
                    clip.removeTrack(partId);
                }
            }

            @Override
            public void execute() {
                apply(afterCopy);
            }

            @Override
            public void undo() {
                apply(beforeCopy);
            }

            @Override
            public String getDescription() {
                return "Move keyframes on " + partId;
            }
        };
    }

    /**
     * Replace the keyframe at {@code index} with a new pose at the same or
     * different time. Used by the inspector for single-keyframe edits.
     *
     * <p>Retiming onto another keyframe's time <em>displaces</em> that
     * keyframe (a track never holds two keys at one time — matching
     * {@link Track#upsert}); undo restores both. Undo locates the edited key
     * by identity, not time, so it is safe even when times coincide.
     */
    public static Command edit(AnimationClip clip, String partId, int index, Keyframe newKf) {
        return new Command() {
            private Keyframe before;
            private Keyframe displaced;   // key that sat at newKf.time(), if any

            @Override
            public void execute() {
                Track track = clip.trackFor(partId);
                if (track == null || index < 0 || index >= track.size()) return;
                before = track.get(index);
                track.removeAt(index);
                displaced = null;
                for (int i = 0; i < track.size(); i++) {
                    if (Math.abs(track.get(i).time() - newKf.time()) < 1e-4f) {
                        displaced = track.get(i);
                        break;
                    }
                }
                track.upsert(newKf);
            }

            @Override
            public void undo() {
                Track track = clip.trackFor(partId);
                if (track == null || before == null) return;
                removeByIdentity(track, newKf);
                if (displaced != null) track.upsert(displaced);
                track.upsert(before);
            }

            @Override
            public String getDescription() {
                return "Edit keyframe on " + partId;
            }
        };
    }

    /**
     * Re-key a track under a different part id (the manual "rebind orphan
     * track" operation). If the target already has a track, the rebound
     * keyframes are merged into it (same-time keys replace); undo restores
     * both tracks exactly.
     */
    public static Command rebindTrack(AnimationClip clip, String fromPartId,
                                      String toPartId, String toPartName) {
        return new Command() {
            private java.util.List<Keyframe> fromKeys;
            private String fromHint;
            private java.util.List<Keyframe> toKeysBefore;   // null = no target track existed
            private String toHintBefore;

            @Override
            public void execute() {
                Track from = clip.trackFor(fromPartId);
                if (from == null || fromPartId.equals(toPartId)) return;
                fromKeys = new java.util.ArrayList<>(from.keyframes());
                fromHint = from.partNameHint();
                clip.removeTrack(fromPartId);

                Track to = clip.trackFor(toPartId);
                if (to != null) {
                    toKeysBefore = new java.util.ArrayList<>(to.keyframes());
                    toHintBefore = to.partNameHint();
                } else {
                    toKeysBefore = null;
                    to = clip.ensureTrack(toPartId);
                }
                to.setPartNameHint(toPartName);
                for (Keyframe kf : fromKeys) to.upsert(kf);
            }

            @Override
            public void undo() {
                if (fromKeys == null) return;
                if (toKeysBefore == null) {
                    clip.removeTrack(toPartId);
                } else {
                    Track to = clip.ensureTrack(toPartId);
                    to.keyframes().clear();
                    for (Keyframe kf : toKeysBefore) to.upsert(kf);
                    to.setPartNameHint(toHintBefore);
                }
                Track from = clip.ensureTrack(fromPartId);
                from.keyframes().clear();
                from.setPartNameHint(fromHint);
                for (Keyframe kf : fromKeys) from.upsert(kf);
            }

            @Override
            public String getDescription() {
                return "Rebind track " + fromPartId + " -> " + toPartId;
            }
        };
    }

    /**
     * Drop every keyframe past {@code duration} on every track, as one
     * command (tracks left empty are removed). No-op when nothing is beyond.
     */
    public static Command trimBeyond(AnimationClip clip, float duration) {
        java.util.List<Command> parts = new java.util.ArrayList<>();
        for (Track track : clip.tracks().values()) {
            java.util.List<Keyframe> keep = new java.util.ArrayList<>();
            for (Keyframe kf : track.keyframes()) {
                if (kf.time() <= duration + 1e-4f) keep.add(kf);
            }
            if (keep.size() != track.size()) {
                parts.add(replaceTrackKeyframes(clip, track.partId(), track.keyframes(), keep));
            }
        }
        return parts.isEmpty() ? null
                : new CompositeCommand("Trim keyframes beyond " + duration + "s", parts);
    }

    /** Remove the exact keyframe instance (identity, not time) from a track. */
    static void removeByIdentity(Track track, Keyframe kf) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i) == kf) {
                track.removeAt(i);
                return;
            }
        }
    }
}
