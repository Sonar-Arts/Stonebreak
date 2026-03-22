package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.rendering.model.gmr.parts.PartTransform;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Transform target for an individual model part.
 * When parts are selected in the ModelPartManager, this target redirects
 * gizmo transforms to the selected part's local transform.
 *
 * <p>The gizmo positions itself at the center of the selected part's geometry
 * and applies translate/rotate/scale via {@link ModelPartManager}.
 */
public class PartTransformTarget implements ITransformTarget {

    private static final Logger logger = LoggerFactory.getLogger(PartTransformTarget.class);

    private final ModelPartManager partManager;

    public PartTransformTarget(ModelPartManager partManager) {
        this.partManager = partManager;
    }

    @Override
    public Vector3f getPosition() {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null) {
            return new Vector3f(0, 0, 0);
        }
        return new Vector3f(part.transform().position());
    }

    @Override
    public Vector3f getRotation() {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null) {
            return new Vector3f(0, 0, 0);
        }
        return new Vector3f(part.transform().rotation());
    }

    @Override
    public Vector3f getScale() {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null) {
            return new Vector3f(1, 1, 1);
        }
        return new Vector3f(part.transform().scale());
    }

    @Override
    public void setPosition(float x, float y, float z) {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null || part.locked()) {
            return;
        }

        PartTransform current = part.transform();
        PartTransform updated = new PartTransform(
                current.origin(),
                new Vector3f(x, y, z),
                new Vector3f(current.rotation()),
                new Vector3f(current.scale())
        );
        partManager.setPartTransform(part.id(), updated);
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
        ModelPartDescriptor part = getSelectedPart();
        if (part == null || part.locked()) {
            return;
        }

        PartTransform current = part.transform();
        PartTransform updated = new PartTransform(
                current.origin(),
                new Vector3f(current.position()),
                new Vector3f(x, y, z),
                new Vector3f(current.scale())
        );
        partManager.setPartTransform(part.id(), updated);
    }

    @Override
    public void setScale(float x, float y, float z) {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null || part.locked()) {
            return;
        }

        PartTransform current = part.transform();
        PartTransform updated = new PartTransform(
                current.origin(),
                new Vector3f(current.position()),
                new Vector3f(current.rotation()),
                new Vector3f(x, y, z)
        );
        partManager.setPartTransform(part.id(), updated);
    }

    @Override
    public Vector3f getWorldCenter() {
        ModelPartDescriptor part = getSelectedPart();
        if (part == null || part.meshRange() == null) {
            return new Vector3f(0, 0, 0);
        }

        // Compute center from the part's geometry
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

        // Apply part transform to get world-space center
        Vector3f localCenter = new Vector3f(cx, cy, cz);
        part.transform().toMatrix().transformPosition(localCenter);

        return localCenter;
    }

    @Override
    public boolean isActive() {
        return getSelectedPart() != null;
    }

    @Override
    public String getTargetName() {
        ModelPartDescriptor part = getSelectedPart();
        return part != null ? "Part: " + part.name() : "No Part Selected";
    }

    // ========== Internal ==========

    /**
     * Get the first selected part, or null if none selected.
     * When multiple parts are selected, operates on the first one.
     */
    private ModelPartDescriptor getSelectedPart() {
        Set<String> selectedIds = partManager.getSelectedPartIds();
        if (selectedIds.isEmpty()) {
            return null;
        }

        String firstId = selectedIds.iterator().next();
        return partManager.getPartById(firstId).orElse(null);
    }
}
