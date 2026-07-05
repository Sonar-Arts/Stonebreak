package com.openmason.main.systems.services.commands;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronizes overlay renderers and selection states after an undo/redo operation.
 *
 * <p>After {@link GenericModelRenderer#restoreSnapshot} rebuilds topology and
 * fires change notifications, the existing {@code MeshChangeListener} implementations
 * (VertexRenderer, EdgeRenderer) auto-update. This class handles the remaining work:
 * <ul>
 *   <li>Rebuild FaceRenderer from GMR</li>
 *   <li>Clear all selection states (indices may be stale after topology change)</li>
 * </ul>
 */
public final class RendererSynchronizer {

    private static final Logger logger = LoggerFactory.getLogger(RendererSynchronizer.class);

    private final GenericModelRenderer gmr;
    private final FaceRenderer faceRenderer;
    private final VertexSelectionState vertexSelectionState;
    private final EdgeSelectionState edgeSelectionState;
    private final FaceSelectionState faceSelectionState;

    public RendererSynchronizer(GenericModelRenderer gmr,
                                FaceRenderer faceRenderer,
                                VertexSelectionState vertexSelectionState,
                                EdgeSelectionState edgeSelectionState,
                                FaceSelectionState faceSelectionState) {
        this.gmr = gmr;
        this.faceRenderer = faceRenderer;
        this.vertexSelectionState = vertexSelectionState;
        this.edgeSelectionState = edgeSelectionState;
        this.faceSelectionState = faceSelectionState;
    }

    /**
     * Synchronize all renderers and clear all selections.
     * Call this after any undo/redo restore operation.
     */
    public void synchronize() {
        // Rebuild FaceRenderer from updated GMR triangles
        if (faceRenderer != null) {
            faceRenderer.rebuildFromGenericModelRenderer();
        }

        // Clear all selections — indices may be invalid after topology undo/redo
        vertexSelectionState.clearSelection();
        edgeSelectionState.clearSelection();
        faceSelectionState.clearSelection();

        logger.debug("Renderers synchronized after undo/redo");
    }
}
