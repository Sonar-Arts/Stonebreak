package com.openmason.main.systems.menus.animationEditor.preview;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Drives the 3D viewport from an animation clip + playhead time.
 *
 * <p>Mirrors {@code TexturePreviewPipeline}: a thin
 * adapter that translates editor state into mutations on the rendering manager.
 * Snapshots each part's rest-pose transform on capture, then on every
 * {@link #applyPose} call writes the sampled pose into the manager. On
 * {@link #release} the rest pose is restored so the model isn't left frozen at
 * the last preview frame.
 */
public final class AnimationPreviewPipeline {

    private final ModelPartManager partManager;

    /** Rest-pose snapshot taken when the editor session begins. */
    private final Map<String, PartTransform> restPose = new HashMap<>();

    private boolean captured = false;

    public AnimationPreviewPipeline(ModelPartManager partManager) {
        if (partManager == null) {
            throw new IllegalArgumentException("partManager required");
        }
        this.partManager = partManager;
    }

    /**
     * Snapshot the current local transform of every part. Called when the
     * editor opens or the user begins playback so {@link #release} can restore.
     */
    public void captureRestPose() {
        restPose.clear();
        for (ModelPartDescriptor p : partManager.getAllParts()) {
            restPose.put(p.id(), p.transform());
        }
        captured = true;
    }

    /**
     * Sample the clip at {@code time} and push the resulting per-part poses
     * into the manager. Parts without a track in the clip stay at their rest
     * pose. Has no effect until {@link #captureRestPose()} has run.
     */
    public void applyPose(AnimationClip clip, float time) {
        if (clip == null || !captured) return;

        Map<String, PartTransform> updates = new HashMap<>();
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            Track track = clip.trackFor(part.id());
            PartTransform target;
            if (track != null && !track.isEmpty()) {
                Track.Sample sample = track.sample(time);
                if (sample == null) {
                    target = restPose.getOrDefault(part.id(), part.transform());
                } else {
                    target = new PartTransform(
                            new Vector3f(part.transform().origin()),
                            sample.position(),
                            sample.rotation(),
                            sample.scale()
                    );
                }
            } else {
                target = restPose.getOrDefault(part.id(), part.transform());
            }
            updates.put(part.id(), target);
        }
        // Single rebuild for the whole pose — see ModelPartManager#setPartTransformsBatch.
        partManager.setPartTransformsBatch(updates);
    }

    /**
     * Restore the rest pose snapshot. Idempotent.
     */
    public void release() {
        if (!captured) return;
        partManager.setPartTransformsBatch(new HashMap<>(restPose));
        restPose.clear();
        captured = false;
    }

    public boolean hasCapturedRestPose() {
        return captured;
    }
}
