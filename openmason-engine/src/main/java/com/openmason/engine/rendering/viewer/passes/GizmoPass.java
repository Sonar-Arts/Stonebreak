package com.openmason.engine.rendering.viewer.passes;

import com.openmason.engine.rendering.viewer.ViewerFrame;
import com.openmason.engine.rendering.viewer.ViewerPass;
import com.openmason.engine.rendering.viewer.ViewerPassOrder;
import com.openmason.engine.rendering.viewer.gizmo.rendering.GizmoRenderer;

/**
 * Draws the transform gizmo on top of the scene.
 *
 * <p>The renderer is supplied by the host, because the host also owns which
 * {@code ITransformTarget} it is pointed at.
 */
public final class GizmoPass implements ViewerPass {

    private final GizmoRenderer gizmoRenderer;

    public GizmoPass(GizmoRenderer gizmoRenderer) {
        this.gizmoRenderer = java.util.Objects.requireNonNull(gizmoRenderer, "gizmoRenderer");
    }

    @Override
    public int order() {
        return ViewerPassOrder.GIZMO;
    }

    @Override
    public String name() {
        return "gizmo";
    }

    @Override
    public void render(ViewerFrame frame) {
        if (!gizmoRenderer.isInitialized()) {
            return;
        }
        gizmoRenderer.render(
                frame.context().getCamera().getViewMatrix(),
                frame.context().getCamera().getProjectionMatrix());
    }
}
