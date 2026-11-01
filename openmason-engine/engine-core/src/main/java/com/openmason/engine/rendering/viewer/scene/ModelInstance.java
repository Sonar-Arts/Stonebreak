package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.transform.TransformLimits;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One placement of a {@link ModelHandle} in a {@link ModelScene}.
 *
 * <p>The transform is {@link TransformLimits#UNBOUNDED} — a scene spans an arbitrary
 * layout, unlike the model editor which deliberately confines its single model to the
 * visible grid.
 */
public final class ModelInstance {

    private final String id;
    private final ModelHandle model;
    private final TransformState transform;

    private String name;
    private boolean visible = true;
    private boolean locked = false;

    ModelInstance(String id, ModelHandle model, String name) {
        this.id = id;
        this.model = model;
        this.name = name;
        this.transform = new TransformState(TransformLimits.UNBOUNDED);
    }

    public String id() { return id; }
    public ModelHandle model() { return model; }

    /** Live transform — the gizmo writes straight into this. */
    public TransformState transform() { return transform; }

    public String name() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    /** A locked instance is skipped by picking and refuses gizmo drags. */
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    /** This instance's model matrix. */
    public Matrix4f modelMatrix() {
        return transform.getTransformMatrix();
    }

    /**
     * Axis-aligned bounds in world space.
     *
     * <p>Recomputed from the model matrix each call rather than rotating the min/max
     * pair directly: rotating a box's corners and re-fitting is the only way to get a
     * correct AABB for a rotated instance (naively transforming min/max produces a box
     * that is too small and makes picking miss).
     */
    public ModelBounds worldBounds() {
        ModelBounds local = model.bounds();
        Vector3f min = local.min();
        Vector3f max = local.max();
        Matrix4f m = modelMatrix();

        Vector3f worldMin = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f worldMax = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        for (int corner = 0; corner < 8; corner++) {
            Vector3f p = new Vector3f(
                    (corner & 1) == 0 ? min.x : max.x,
                    (corner & 2) == 0 ? min.y : max.y,
                    (corner & 4) == 0 ? min.z : max.z);
            m.transformPosition(p);
            worldMin.min(p);
            worldMax.max(p);
        }

        Vector3f center = new Vector3f(worldMin).add(worldMax).mul(0.5f);
        Vector3f size = new Vector3f(worldMax).sub(worldMin);
        return new ModelBounds(worldMin, worldMax, center, size);
    }
}
