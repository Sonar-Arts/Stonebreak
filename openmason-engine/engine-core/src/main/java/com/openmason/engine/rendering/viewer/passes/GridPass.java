package com.openmason.engine.rendering.viewer.passes;

import com.openmason.engine.rendering.shaders.ShaderType;
import com.openmason.engine.rendering.viewer.ViewerFrame;
import com.openmason.engine.rendering.viewer.ViewerPass;
import com.openmason.engine.rendering.viewer.ViewerPassOrder;

/**
 * Draws the infinite ground grid, gated on {@code ViewerSettings.isGridVisible()}.
 *
 * <p>Owns its {@link GridRenderer} and initializes it lazily on first render, because
 * the GL context is only guaranteed once the viewer is actually drawing.
 */
public final class GridPass implements ViewerPass {

    private final GridRenderer gridRenderer = new GridRenderer();

    @Override
    public int order() {
        return ViewerPassOrder.GRID;
    }

    @Override
    public String name() {
        return "grid";
    }

    @Override
    public boolean isEnabled() {
        return true; // visibility is a per-frame settings read, see render()
    }

    @Override
    public void render(ViewerFrame frame) {
        if (!frame.settings().isGridVisible()) {
            return;
        }
        if (!gridRenderer.isInitialized()) {
            gridRenderer.initialize();
        }
        gridRenderer.render(frame.shaders().getShaderProgram(ShaderType.INFINITE_GRID), frame.context());
    }

    @Override
    public void cleanup() {
        if (gridRenderer.isInitialized()) {
            gridRenderer.cleanup();
        }
    }
}
