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
     * <p>Undo locates the keyframe by its new time — do NOT use for bulk moves;
     * use {@link #replaceTrackKeyframes} instead.
     */
    public static Command edit(AnimationClip clip, String partId, int index, Keyframe newKf) {
        return new Command() {
            private Keyframe before;

            @Override
            public void execute() {
                Track track = clip.trackFor(partId);
                if (track == null || index < 0 || index >= track.size()) return;
                before = track.get(index);
                track.set(index, newKf);
                track.resort();
            }

            @Override
            public void undo() {
                Track track = clip.trackFor(partId);
                if (track == null || before == null) return;
                // The edit may have moved the keyframe — search by the new time and revert.
                for (int i = 0; i < track.size(); i++) {
                    if (Math.abs(track.get(i).time() - newKf.time()) < 1e-4f) {
                        track.set(i, before);
                        track.resort();
                        return;
                    }
                }
            }

            @Override
            public String getDescription() {
                return "Edit keyframe on " + partId;
            }
        };
    }
}
