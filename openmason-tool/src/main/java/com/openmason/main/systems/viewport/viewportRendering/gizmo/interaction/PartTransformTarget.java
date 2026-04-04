package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Transform target for selected model parts.
 * When parts are selected in the ModelPartManager, this target redirects
 * gizmo transforms to the selected parts' local transforms.
 *
 * <p>Supports single and multi-part selection. When multiple parts are selected,
 * the gizmo centers on the combined centroid and transforms are applied to all
 * selected parts as a group (using relative deltas from drag start).
 *
 * <p>The world center computation accounts for both the part's local transform
 * and the model-level {@link TransformState} transform, ensuring the gizmo
 * appears at the correct on-screen position even after the model has been moved.
 */
public class PartTransformTarget implements ITransformTarget {

    private static final Logger logger = LoggerFactory.getLogger(PartTransformTarget.class);

    private final ModelPartManager partManager;
    private final TransformState transformState;

    // Snapshot of each selected part's transform at drag start (for multi-part relative transforms)
    private final List<PartDragSnapshot> dragSnapshots = new ArrayList<>();

    public PartTransformTarget(ModelPartManager partManager, TransformState transformState) {
        this.partManager = partManager;
        this.transformState = transformState;
    }

    @Override
    public Vector3f getPosition() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }
        if (selected.size() == 1) {
            return new Vector3f(selected.getFirst().transform().position());
        }
        // For multi-part: return the average position (used as drag start reference)
        Vector3f avg = new Vector3f();
        for (ModelPartDescriptor part : selected) {
            avg.add(part.transform().position());
        }
        avg.div(selected.size());
        return avg;
    }

    @Override
    public Vector3f getRotation() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }
        if (selected.size() == 1) {
            return new Vector3f(selected.getFirst().transform().rotation());
        }
        // For multi-part: return the average rotation
        Vector3f avg = new Vector3f();
        for (ModelPartDescriptor part : selected) {
            avg.add(part.transform().rotation());
        }
        avg.div(selected.size());
        return avg;
    }

    @Override
    public Vector3f getScale() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return new Vector3f(1, 1, 1);
        }
        if (selected.size() == 1) {
            return new Vector3f(selected.getFirst().transform().scale());
        }
        // For multi-part: return the average scale
        Vector3f avg = new Vector3f();
        for (ModelPartDescriptor part : selected) {
            avg.add(part.transform().scale());
        }
        avg.div(selected.size());
        return avg;
    }

    @Override
    public void setPosition(float x, float y, float z) {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return;
        }

        if (selected.size() == 1) {
            // Single part: set absolute position
            ModelPartDescriptor part = selected.getFirst();
            if (part.locked()) return;

            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(),
                    new Vector3f(x, y, z),
                    new Vector3f(current.rotation()),
                    new Vector3f(current.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        } else {
            // Multi-part: apply delta from the group's start position to each part
            applyMultiPartTranslation(selected, x, y, z);
        }
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        if (snap && snapIncrement > 0) {
            x = Math.round(x / snapIncrement) * snapIncrement;
            y = Math.round(y / snapIncrement) * snapIncrement;
            z = Math.round(z / snapIncrement) * snapIncrement;
        }
        setPosition(x, y, z);
    }

    @Override
    public void setRotation(float x, float y, float z) {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return;
        }

        if (selected.size() == 1) {
            ModelPartDescriptor part = selected.getFirst();
            if (part.locked()) return;

            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(),
                    new Vector3f(current.position()),
                    new Vector3f(x, y, z),
                    new Vector3f(current.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        } else {
            applyMultiPartRotation(selected, x, y, z);
        }
    }

    @Override
    public void setScale(float x, float y, float z) {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return;
        }

        if (selected.size() == 1) {
            ModelPartDescriptor part = selected.getFirst();
            if (part.locked()) return;

            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(),
                    new Vector3f(current.position()),
                    new Vector3f(current.rotation()),
                    new Vector3f(x, y, z)
            );
            partManager.setPartTransform(part.id(), updated);
        } else {
            applyMultiPartScale(selected, x, y, z);
        }
    }

    @Override
    public Vector3f getWorldCenter() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }

        // Compute centroid across all selected parts
        Vector3f combinedCenter = new Vector3f();
        int contributingParts = 0;

        for (ModelPartDescriptor part : selected) {
            Vector3f partCenter = computePartLocalCenter(part);
            if (partCenter != null) {
                combinedCenter.add(partCenter);
                contributingParts++;
            }
        }

        if (contributingParts == 0) {
            return new Vector3f(0, 0, 0);
        }

        combinedCenter.div(contributingParts);

        // Apply the model-level transform so the gizmo follows the model's position
        Matrix4f modelTransform = transformState.getTransformMatrix();
        modelTransform.transformPosition(combinedCenter);

        return combinedCenter;
    }

    @Override
    public boolean isActive() {
        return !partManager.getSelectedPartIds().isEmpty();
    }

    /**
     * Check if all currently selected parts are locked.
     * Used to prevent gizmo drags on fully-locked selections.
     */
    public boolean areAllSelectedPartsLocked() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) return false;
        return selected.stream().allMatch(ModelPartDescriptor::locked);
    }

    // Snapshots of all parts' transforms at model-level drag start (for delta-based application)
    private final java.util.Map<String, PartTransform> modelDragSnapshots = new java.util.HashMap<>();

    /**
     * Snapshot all parts' transforms at the start of a model-level drag.
     * Called when no part is selected and the user starts dragging the gizmo.
     */
    public void snapshotAllPartsForModelDrag() {
        modelDragSnapshots.clear();
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            modelDragSnapshots.put(part.id(), part.transform());
        }
    }

    /**
     * Apply a translation delta to all unlocked parts relative to their drag-start positions.
     */
    public void applyTranslationDeltaToUnlocked(Vector3f delta) {
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            if (part.locked()) continue;
            PartTransform snap = modelDragSnapshots.get(part.id());
            if (snap == null) snap = part.transform();
            PartTransform updated = new PartTransform(
                    snap.origin(),
                    new Vector3f(snap.position()).add(delta),
                    new Vector3f(snap.rotation()),
                    new Vector3f(snap.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    /**
     * Apply a rotation to all unlocked parts.
     */
    public void applyRotationToUnlocked(Vector3f rotation) {
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            if (part.locked()) continue;
            PartTransform snap = modelDragSnapshots.get(part.id());
            if (snap == null) snap = part.transform();
            PartTransform updated = new PartTransform(
                    snap.origin(),
                    new Vector3f(snap.position()),
                    new Vector3f(rotation),
                    new Vector3f(snap.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    /**
     * Apply a scale to all unlocked parts.
     */
    public void applyScaleToUnlocked(float sx, float sy, float sz) {
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            if (part.locked()) continue;
            PartTransform snap = modelDragSnapshots.get(part.id());
            if (snap == null) snap = part.transform();
            PartTransform updated = new PartTransform(
                    snap.origin(),
                    new Vector3f(snap.position()),
                    new Vector3f(snap.rotation()),
                    new Vector3f(sx, sy, sz)
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    @Override
    public String getTargetName() {
        List<ModelPartDescriptor> selected = getSelectedParts();
        if (selected.isEmpty()) {
            return "No Part Selected";
        }
        if (selected.size() == 1) {
            return "Part: " + selected.getFirst().name();
        }
        return selected.size() + " Parts Selected";
    }

    /**
     * Snapshot the transforms of all currently selected parts.
     * Must be called at drag start so multi-part transforms can compute
     * relative deltas correctly.
     */
    public void snapshotDragStart() {
        dragSnapshots.clear();
        for (ModelPartDescriptor part : getSelectedParts()) {
            dragSnapshots.add(new PartDragSnapshot(
                    part.id(),
                    new Vector3f(part.transform().position()),
                    new Vector3f(part.transform().rotation()),
                    new Vector3f(part.transform().scale())
            ));
        }
    }

    /**
     * Clear drag snapshots. Called when drag ends to avoid stale data.
     */
    public void clearDragSnapshots() {
        dragSnapshots.clear();
    }

    // ========== Multi-Part Transform Helpers ==========

    private void applyMultiPartTranslation(List<ModelPartDescriptor> selected, float newX, float newY, float newZ) {
        if (dragSnapshots.isEmpty()) {
            snapshotDragStart();
        }

        // The gizmo reports the new "group average" position.
        // Compute the delta from the group's start average.
        Vector3f startAvg = computeSnapshotAveragePosition();
        Vector3f delta = new Vector3f(newX - startAvg.x, newY - startAvg.y, newZ - startAvg.z);

        for (PartDragSnapshot snapshot : dragSnapshots) {
            ModelPartDescriptor part = partManager.getPartById(snapshot.partId).orElse(null);
            if (part == null || part.locked()) continue;

            Vector3f newPos = new Vector3f(snapshot.startPosition).add(delta);
            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(), newPos,
                    new Vector3f(current.rotation()), new Vector3f(current.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    private void applyMultiPartRotation(List<ModelPartDescriptor> selected, float newX, float newY, float newZ) {
        if (dragSnapshots.isEmpty()) {
            snapshotDragStart();
        }

        Vector3f startAvg = computeSnapshotAverageRotation();
        Vector3f delta = new Vector3f(newX - startAvg.x, newY - startAvg.y, newZ - startAvg.z);

        for (PartDragSnapshot snapshot : dragSnapshots) {
            ModelPartDescriptor part = partManager.getPartById(snapshot.partId).orElse(null);
            if (part == null || part.locked()) continue;

            Vector3f newRot = new Vector3f(snapshot.startRotation).add(delta);
            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(), new Vector3f(current.position()),
                    newRot, new Vector3f(current.scale())
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    private void applyMultiPartScale(List<ModelPartDescriptor> selected, float newX, float newY, float newZ) {
        if (dragSnapshots.isEmpty()) {
            snapshotDragStart();
        }

        Vector3f startAvg = computeSnapshotAverageScale();
        // Compute scale ratio (new / startAvg) to apply proportionally
        float ratioX = startAvg.x > 0.0001f ? newX / startAvg.x : 1.0f;
        float ratioY = startAvg.y > 0.0001f ? newY / startAvg.y : 1.0f;
        float ratioZ = startAvg.z > 0.0001f ? newZ / startAvg.z : 1.0f;

        for (PartDragSnapshot snapshot : dragSnapshots) {
            ModelPartDescriptor part = partManager.getPartById(snapshot.partId).orElse(null);
            if (part == null || part.locked()) continue;

            Vector3f newScale = new Vector3f(
                    snapshot.startScale.x * ratioX,
                    snapshot.startScale.y * ratioY,
                    snapshot.startScale.z * ratioZ
            );
            PartTransform current = part.transform();
            PartTransform updated = new PartTransform(
                    current.origin(), new Vector3f(current.position()),
                    new Vector3f(current.rotation()), newScale
            );
            partManager.setPartTransform(part.id(), updated);
        }
    }

    // ========== Snapshot Helpers ==========

    private Vector3f computeSnapshotAveragePosition() {
        Vector3f avg = new Vector3f();
        for (PartDragSnapshot s : dragSnapshots) avg.add(s.startPosition);
        if (!dragSnapshots.isEmpty()) avg.div(dragSnapshots.size());
        return avg;
    }

    private Vector3f computeSnapshotAverageRotation() {
        Vector3f avg = new Vector3f();
        for (PartDragSnapshot s : dragSnapshots) avg.add(s.startRotation);
        if (!dragSnapshots.isEmpty()) avg.div(dragSnapshots.size());
        return avg;
    }

    private Vector3f computeSnapshotAverageScale() {
        Vector3f avg = new Vector3f();
        for (PartDragSnapshot s : dragSnapshots) avg.add(s.startScale);
        if (!dragSnapshots.isEmpty()) avg.div(dragSnapshots.size());
        return avg;
    }

    // ========== Internal ==========

    /**
     * Compute a part's center in local model space (after part transform, before model transform).
     */
    private Vector3f computePartLocalCenter(ModelPartDescriptor part) {
        if (part.meshRange() == null) {
            return new Vector3f(part.transform().position());
        }

        PartMeshRebuilder.PartGeometry geo = partManager.getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null || geo.vertices().length == 0) {
            return new Vector3f(part.transform().position());
        }

        // Average all vertex positions for the centroid
        float cx = 0, cy = 0, cz = 0;
        int vertexCount = geo.vertexCount();
        for (int i = 0; i < vertexCount; i++) {
            cx += geo.vertices()[i * 3];
            cy += geo.vertices()[i * 3 + 1];
            cz += geo.vertices()[i * 3 + 2];
        }
        cx /= vertexCount;
        cy /= vertexCount;
        cz /= vertexCount;

        // Apply part transform to get model-space center
        Vector3f localCenter = new Vector3f(cx, cy, cz);
        part.transform().toMatrix().transformPosition(localCenter);

        return localCenter;
    }

    /**
     * Get all selected parts as a list.
     */
    private List<ModelPartDescriptor> getSelectedParts() {
        Set<String> selectedIds = partManager.getSelectedPartIds();
        if (selectedIds.isEmpty()) {
            return List.of();
        }

        List<ModelPartDescriptor> result = new ArrayList<>();
        for (String id : selectedIds) {
            partManager.getPartById(id).ifPresent(result::add);
        }
        return result;
    }

    /**
     * Snapshot of a part's transform at drag start, for computing relative deltas.
     */
    private record PartDragSnapshot(
            String partId,
            Vector3f startPosition,
            Vector3f startRotation,
            Vector3f startScale
    ) {}
}
