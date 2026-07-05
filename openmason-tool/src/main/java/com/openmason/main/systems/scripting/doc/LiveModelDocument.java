package com.openmason.main.systems.scripting.doc;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.ViewportController;

/**
 * The live viewport's model as a script target. Thin delegation — the part
 * manager's mesh consumer already pushes every change through the render
 * pipeline, so scripted edits appear in the viewport immediately.
 *
 * <p>GL-thread only: construct and use exclusively inside a
 * {@code MainThreadExecutor} task (enforced by {@code ScriptingService}).
 */
public final class LiveModelDocument implements ModelDocument {

    private final ViewportController viewport;

    public LiveModelDocument(ViewportController viewport) {
        this.viewport = viewport;
    }

    @Override
    public ModelPartManager parts() {
        ModelPartManager pm = viewport.getPartManager();
        if (pm == null) {
            throw new IllegalStateException("No model is open in the viewport");
        }
        return pm;
    }

    @Override
    public FaceTextureManager faceTextures() {
        GenericModelRenderer gmr = viewport.getModelRenderer();
        if (gmr == null) {
            throw new IllegalStateException("Model renderer not available");
        }
        return gmr.getFaceTextureManager();
    }

    @Override
    public OMOFormat.MeshData extractMeshData() {
        return viewport.extractMeshData();
    }
}
