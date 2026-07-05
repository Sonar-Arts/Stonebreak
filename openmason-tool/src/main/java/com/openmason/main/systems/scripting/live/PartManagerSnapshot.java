package com.openmason.main.systems.scripting.live;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Deep, data-level snapshot of a {@link ModelPartManager}: every part's id,
 * name, transform, hierarchy, flags and geometry arrays.
 *
 * <p>Exists because {@code MeshSnapshot} only covers the combined editable
 * mesh — script runs also mutate the part manager (create/remove parts,
 * per-part geometry write-backs), so script undo/redo and failure recovery
 * restore both.
 */
public final class PartManagerSnapshot {

    private record PartState(
            String id, String name, PartTransform transform, String parentId,
            boolean visible, boolean locked, PartMeshRebuilder.PartGeometry geometry) {
    }

    private final List<PartState> parts;

    private PartManagerSnapshot(List<PartState> parts) {
        this.parts = parts;
    }

    public static PartManagerSnapshot capture(ModelPartManager pm) {
        List<PartState> out = new ArrayList<>();
        for (ModelPartDescriptor part : pm.getAllParts()) {
            PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
            out.add(new PartState(
                    part.id(), part.name(), copy(part.transform()), part.parentId(),
                    part.visible(), part.locked(), copy(geo)));
        }
        return new PartManagerSnapshot(out);
    }

    /**
     * Clear the manager and rebuild it to this snapshot's state. Best-effort
     * per part: a part that fails to restore (e.g. inconsistent geometry that
     * predates the engine's registration guard) is logged and skipped so a
     * rollback can never make things worse than the failure it is undoing.
     */
    public void restore(ModelPartManager pm) {
        pm.clear();
        for (PartState state : parts) {
            try {
                ModelPartDescriptor added = pm.addPartFromGeometry(
                        state.id(), state.name(), copy(state.geometry()),
                        new Vector3f(state.transform().origin()));
                pm.setPartTransform(added.id(), copy(state.transform()));
                pm.setPartVisible(added.id(), state.visible());
                pm.setPartLocked(added.id(), state.locked());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PartManagerSnapshot.class)
                        .error("Could not restore part '{}' from snapshot: {}",
                                state.name(), e.getMessage());
            }
        }
        // Parents in a second pass so every target exists.
        for (PartState state : parts) {
            if (state.parentId() != null) {
                pm.setPartParent(state.id(), state.parentId());
            }
        }
    }

    private static PartTransform copy(PartTransform t) {
        return new PartTransform(
                new Vector3f(t.origin()), new Vector3f(t.position()),
                new Vector3f(t.rotation()), new Vector3f(t.scale()));
    }

    private static PartMeshRebuilder.PartGeometry copy(PartMeshRebuilder.PartGeometry geo) {
        if (geo == null) return null;
        return new PartMeshRebuilder.PartGeometry(
                geo.vertices() != null ? geo.vertices().clone() : null,
                geo.texCoords() != null ? geo.texCoords().clone() : null,
                geo.indices() != null ? geo.indices().clone() : null,
                geo.triangleToFaceId() != null ? geo.triangleToFaceId().clone() : null,
                geo.vertexCount(), geo.indexCount(), geo.faceCount());
    }
}
