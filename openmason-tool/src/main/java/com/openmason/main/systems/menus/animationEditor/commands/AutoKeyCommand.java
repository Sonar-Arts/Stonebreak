package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.textureCreator.commands.Command;

/**
 * Keyframe upsert produced by auto-key (a viewport edit of a part while the
 * editor records). A gizmo drag fires dozens of transform changes; rather
 * than one history entry per event, the controller {@link #amend}s the
 * command already on top of the stack when it targets the same part and
 * time, so one drag is one undo step.
 */
public final class AutoKeyCommand implements Command {

    private final AnimationClip clip;
    private final String partId;
    private final float time;
    private Keyframe after;
    private Keyframe replaced;      // pre-existing key at this time, if any
    private boolean trackCreated;

    public AutoKeyCommand(AnimationClip clip, String partId, Keyframe keyframe) {
        this.clip = clip;
        this.partId = partId;
        this.time = keyframe.time();
        this.after = keyframe;
    }

    public boolean matches(String otherPartId, float otherTime) {
        return partId.equals(otherPartId) && Math.abs(time - otherTime) < 1e-4f;
    }

    /** Replace the recorded pose in place (the command stays applied). */
    public void amend(Keyframe newKeyframe) {
        Track track = clip.trackFor(partId);
        if (track == null) return;
        KeyframeCommands.removeByIdentity(track, after);
        after = newKeyframe.withTime(time);
        track.upsert(after);
    }

    @Override
    public void execute() {
        trackCreated = clip.trackFor(partId) == null;
        Track track = clip.ensureTrack(partId);
        replaced = null;
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) < 1e-4f) {
                replaced = track.get(i);
                break;
            }
        }
        track.upsert(after);
    }

    @Override
    public void undo() {
        Track track = clip.trackFor(partId);
        if (track == null) return;
        KeyframeCommands.removeByIdentity(track, after);
        if (replaced != null) {
            track.upsert(replaced);
        } else if (trackCreated && track.isEmpty()) {
            clip.removeTrack(partId);
        }
    }

    @Override
    public String getDescription() {
        return "Auto-key " + partId + " @ " + time + "s";
    }
}
