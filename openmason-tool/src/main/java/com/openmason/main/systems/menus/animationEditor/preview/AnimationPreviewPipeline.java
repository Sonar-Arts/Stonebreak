package com.openmason.main.systems.menus.animationEditor.preview;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.AnimSampler;
import com.openmason.engine.rendering.model.gmr.parts.IPartChangeListener;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiPredicate;

/**
 * Drives the 3D viewport from an animation clip + playhead time.
 *
 * <p>Snapshots each part's rest-pose transform on capture, then on every
 * {@link #applyPose} call writes the sampled pose into the manager. On
 * {@link #release} the rest pose is restored so the model isn't left frozen at
 * the last preview frame.
 *
 * <p>The pipeline also listens to the part manager so the rest-pose snapshot
 * tracks the model's truth while the editor is open: a part edited in the
 * property panel or with the gizmo (any write that did not come from this
 * pipeline) updates its rest-pose entry, a part added/removed enters/leaves
 * the snapshot, and a full rebuild (model undo/redo) re-captures. Without
 * this, closing the editor would revert the user's modelling edits to a
 * stale snapshot. External edits are also reported to an optional callback
 * so the controller can auto-key them.
 *
 * <p>{@link #restoreRestPose()} writes the rest pose back while keeping the
 * snapshot — the hook the model save path uses so an animated preview pose
 * is never baked into the .omo.
 */
public final class AnimationPreviewPipeline implements IPartChangeListener {

    private final ModelPartManager partManager;

    /** Rest-pose snapshot taken when the editor session begins. */
    private final Map<String, PartTransform> restPose = new HashMap<>();

    private boolean captured = false;
    /** True while this pipeline is writing — its own notifications are ignored. */
    private boolean applying = false;
    /** True after {@link #restoreRestPose()} until the next {@link #applyPose}. */
    private boolean atRest = true;

    /** Parts whose current viewport transform came from the clip (not rest). */
    private final Set<String> animatedParts = new HashSet<>();
    /**
     * Parts added since the last frame tick / pose apply. A model load reuses
     * the manager (clear → add → setPartTransform), so transform writes on
     * these are load traffic, not user edits: they update the rest snapshot
     * but are never reported (auto-key must not key a freshly loaded model).
     */
    private final Set<String> freshParts = new HashSet<>();

    private BiPredicate<String, PartTransform> externalEditListener;

    public AnimationPreviewPipeline(ModelPartManager partManager) {
        if (partManager == null) {
            throw new IllegalArgumentException("partManager required");
        }
        this.partManager = partManager;
        partManager.addPartChangeListener(this);
    }

    /**
     * Called with (partId, newTransform) for every part write not made by the
     * preview. Return true when the edit was consumed as animation (auto-key)
     * — the rest-pose snapshot is then left untouched; false lets an edit of
     * a non-animated part become that part's new rest pose.
     */
    public void setExternalEditListener(BiPredicate<String, PartTransform> listener) {
        this.externalEditListener = listener;
    }

    /**
     * Snapshot the current local transform of every part. Called when the
     * editor opens so {@link #release} can restore.
     */
    public void captureRestPose() {
        restPose.clear();
        for (ModelPartDescriptor p : partManager.getAllParts()) {
            restPose.put(p.id(), p.transform());
        }
        captured = true;
        atRest = true;
        animatedParts.clear();
        freshParts.clear();
        // release()/abandon() detach; a re-opened session must listen again.
        partManager.removePartChangeListener(this);
        partManager.addPartChangeListener(this);
    }

    /** Once per UI frame: parts added during the previous frame are no longer "fresh". */
    public void frameTick() {
        freshParts.clear();
    }

    /** The rest-pose transform recorded for a part, or null if unknown. */
    public PartTransform restPoseOf(String partId) {
        return restPose.get(partId);
    }

    /**
     * Sample the clip at {@code time} and push the resulting per-part poses
     * into the manager. Parts without a track in the clip stay at their rest
     * pose. Has no effect until {@link #captureRestPose()} has run.
     */
    public void applyPose(AnimationClip clip, float time) {
        applyPose(clip, time, null);
    }

    /**
     * Like {@link #applyPose(AnimationClip, float)} but, when {@code base} is
     * non-null and {@code clip} is an OVERLAY, blends the overlay onto the
     * base clip exactly as the engine's layering does: the base drives every
     * part, then masked parts lerp toward the overlay pose by the overlay's
     * fade envelope at {@code time}. The base is sampled at {@code time}
     * wrapped to its own duration so the two clips scrub together.
     */
    public void applyPose(AnimationClip clip, float time, AnimationClip base) {
        if (clip == null || !captured) return;

        boolean layered = base != null && clip.layerType() == AnimLayerMeta.LayerType.OVERLAY;
        float overlayWeight = layered ? overlayWeight(clip, time) : 1f;
        float baseTime = layered ? AnimSampler.wrapTime(time, base.duration(), base.loop()) : 0f;

        Map<String, PartTransform> updates = new HashMap<>();
        Set<String> animated = new HashSet<>();
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            PartTransform rest = restPose.getOrDefault(part.id(), part.transform());
            Track.Sample pose = null;

            if (layered) {
                Track baseTrack = base.trackFor(part.id());
                if (baseTrack == null) baseTrack = trackByName(base, part.name());
                Track.Sample basePose = baseTrack != null ? baseTrack.sample(baseTime) : null;
                Track.Sample overlayPose = null;
                if (masksPart(clip, part.id(), part.name())) {
                    Track track = clip.trackFor(part.id());
                    overlayPose = track != null ? track.sample(time) : null;
                }
                if (overlayPose != null && overlayWeight > 0f) {
                    Track.Sample from = basePose != null ? basePose : restSample(rest);
                    pose = lerp(from, overlayPose, overlayWeight);
                } else {
                    pose = basePose;
                }
            } else {
                Track track = clip.trackFor(part.id());
                pose = track != null ? track.sample(time) : null;
            }

            PartTransform target = pose == null ? rest : new PartTransform(
                    new Vector3f(part.transform().origin()),
                    pose.position(), pose.rotation(), pose.scale());
            if (pose != null) animated.add(part.id());
            updates.put(part.id(), target);
        }
        writeBatch(updates);
        animatedParts.clear();
        animatedParts.addAll(animated);
        freshParts.clear();
        atRest = false;
    }

    /** Whether the part's viewport transform currently comes from the clip rather than rest. */
    public boolean isAnimated(String partId) {
        return animatedParts.contains(partId);
    }

    /**
     * Write the rest pose back without dropping the snapshot. Used around a
     * model save so the file gets the authored rest transforms; the caller
     * re-applies the preview pose afterwards. Idempotent.
     */
    public void restoreRestPose() {
        if (!captured || atRest) return;
        writeBatch(new HashMap<>(restPose));
        animatedParts.clear();
        atRest = true;
    }

    /** True when the viewport currently shows the rest pose (nothing animated is applied). */
    public boolean isAtRest() {
        return atRest;
    }

    /**
     * Drop the rest-pose snapshot WITHOUT writing it back. For when the model
     * was replaced under the editor — the snapshotted parts no longer exist,
     * so restoring them would corrupt the new model's transforms. Also
     * detaches from the manager.
     */
    public void abandon() {
        restPose.clear();
        animatedParts.clear();
        captured = false;
        atRest = true;
        partManager.removePartChangeListener(this);
    }

    /**
     * Restore the rest pose snapshot and detach from the manager. Idempotent.
     */
    public void release() {
        if (captured) {
            writeBatch(new HashMap<>(restPose));
            restPose.clear();
            captured = false;
        }
        animatedParts.clear();
        atRest = true;
        partManager.removePartChangeListener(this);
    }

    public boolean hasCapturedRestPose() {
        return captured;
    }

    // ====================== IPartChangeListener ======================

    @Override
    public void onPartTransformChanged(String partId, PartTransform newTransform) {
        if (applying || !captured) return;
        if (freshParts.contains(partId)) {
            // Load traffic on a part that did not exist last frame.
            restPose.put(partId, newTransform);
            return;
        }
        // Someone else (gizmo, property panel, model undo) wrote this part.
        boolean consumed = externalEditListener != null
                && externalEditListener.test(partId, newTransform);
        // Not keyed, and the part was showing its rest pose: this is a
        // modelling edit — it becomes the new rest pose. A part that is
        // showing an animated pose keeps its rest snapshot (the controller
        // hints that the edit is unkeyed).
        if (!consumed && !animatedParts.contains(partId)) {
            restPose.put(partId, newTransform);
        }
    }

    @Override
    public void onPartAdded(ModelPartDescriptor part) {
        if (part == null) return;
        if (captured) restPose.put(part.id(), part.transform());
        freshParts.add(part.id());
    }

    @Override
    public void onPartRemoved(String partId) {
        restPose.remove(partId);
        freshParts.remove(partId);
        animatedParts.remove(partId);
    }

    @Override
    public void onPartsMerged(java.util.List<String> sourceIds, ModelPartDescriptor mergedPart) {
        if (sourceIds != null) sourceIds.forEach(restPose::remove);
        if (captured && mergedPart != null) restPose.put(mergedPart.id(), mergedPart.transform());
    }

    @Override
    public void onPartsRebuilt() {
        // Model undo/redo or reload wrote the model's truth into every part.
        if (!captured || applying) return;
        captureRestPose();
    }

    @Override
    public void onPartSelectionChanged(Set<String> selectedIds) { }

    // ====================== helpers ======================

    private void writeBatch(Map<String, PartTransform> updates) {
        applying = true;
        try {
            partManager.setPartTransformsBatch(updates);
        } finally {
            applying = false;
        }
    }

    /** Mirror of {@code AnimLayering.clipWeight} over the editor clip type. */
    static float overlayWeight(AnimationClip overlay, float time) {
        float in = overlay.fadeInSeconds() <= 0f ? 1f
                : Math.min(time / overlay.fadeInSeconds(), 1f);
        float out = 1f;
        if (!overlay.loop() && overlay.duration() > 0f && overlay.fadeOutSeconds() > 0f) {
            float remaining = overlay.duration() - time;
            out = Math.min(remaining / overlay.fadeOutSeconds(), 1f);
        }
        float w = Math.min(in, out);
        return w < 0f ? 0f : (w > 1f ? 1f : w);
    }

    /** Mirror of {@code AnimLayerMeta.masksPart}: name (case-insensitive) or id; empty = all. */
    static boolean masksPart(AnimationClip clip, String partId, String partName) {
        if (clip.maskParts().isEmpty()) return true;
        for (String mask : clip.maskParts()) {
            if (partName != null && mask.equalsIgnoreCase(partName)) return true;
            if (partId != null && mask.equals(partId)) return true;
        }
        return false;
    }

    private static Track trackByName(AnimationClip clip, String partName) {
        if (partName == null) return null;
        for (Track t : clip.tracks().values()) {
            if (partName.equalsIgnoreCase(t.partNameHint())) return t;
        }
        return null;
    }

    private static Track.Sample restSample(PartTransform rest) {
        return new Track.Sample(new Vector3f(rest.position()), new Vector3f(rest.rotation()),
                new Vector3f(rest.scale()));
    }

    private static Track.Sample lerp(Track.Sample a, Track.Sample b, float w) {
        AnimSampler.PartPose pose = AnimSampler.lerpPose(
                new AnimSampler.PartPose(a.position(), a.rotation(), a.scale()),
                new AnimSampler.PartPose(b.position(), b.rotation(), b.scale()), w);
        return new Track.Sample(pose.position(), pose.rotationDeg(), pose.scale());
    }
}
