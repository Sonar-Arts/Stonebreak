package com.openmason.main.systems.services;

import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.ViewportRenderPipeline;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Service for edge-related operations in the viewport.
 * Follows Single Responsibility Principle - only handles edge operations.
 * Delegates geometric subdivision logic to SubdivisionProcessor (via GenericModelRenderer).
 */
public class EdgeOperationService {

    private static final Logger logger = LoggerFactory.getLogger(EdgeOperationService.class);

    private final ViewportRenderPipeline viewportRenderPipeline;
    private final EdgeSelectionState edgeSelectionState;

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;

    public EdgeOperationService(ViewportRenderPipeline viewportRenderPipeline, EdgeSelectionState edgeSelectionState) {
        this.viewportRenderPipeline = viewportRenderPipeline;
        this.edgeSelectionState = edgeSelectionState;
    }

    /**
     * Set the command history for undo/redo recording.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    /**
     * Subdivide the currently hovered edge at its midpoint.
     * Coordinates between renderers and delegates subdivision logic to SubdivisionProcessor.
     *
     * @return Index of newly created vertex, or -1 if failed
     */
    public int subdivideHoveredEdge() {
        if (viewportRenderPipeline == null) {
            logger.warn("Cannot subdivide edge: render pipeline not initialized");
            return -1;
        }

        EdgeRenderer edgeRenderer = viewportRenderPipeline.getEdgeRenderer();
        VertexRenderer vertexRenderer = viewportRenderPipeline.getVertexRenderer();
        GenericModelRenderer modelRenderer = viewportRenderPipeline.getBlockModelRenderer();

        if (edgeRenderer == null || vertexRenderer == null || modelRenderer == null) {
            logger.warn("Cannot subdivide edge: renderers not available");
            return -1;
        }

        // Get edge vertex indices
        int hoveredEdgeIndex = edgeRenderer.getHoveredEdgeIndex();
        int[] edgeVertexIndices = edgeRenderer.getEdgeVertexIndices(hoveredEdgeIndex);
        if (edgeVertexIndices == null || edgeVertexIndices.length != 2) {
            logger.warn("Cannot get edge vertex indices for subdivision");
            return -1;
        }

        // Get endpoint positions from VertexRenderer (source of truth)
        Vector3f endpoint1 = vertexRenderer.getVertexPosition(edgeVertexIndices[0]);
        Vector3f endpoint2 = vertexRenderer.getVertexPosition(edgeVertexIndices[1]);
        if (endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot get vertex positions for subdivision endpoints");
            return -1;
        }

        // Make copies to avoid mutation issues
        endpoint1 = new Vector3f(endpoint1);
        endpoint2 = new Vector3f(endpoint2);

        // Calculate midpoint
        Vector3f midpoint = calculateMidpoint(endpoint1, endpoint2);

        logger.info("Subdivision: edge {} connects vertices {} and {} at ({},{},{}) to ({},{},{}), midpoint: ({},{},{})",
            hoveredEdgeIndex, edgeVertexIndices[0], edgeVertexIndices[1],
            endpoint1.x, endpoint1.y, endpoint1.z,
            endpoint2.x, endpoint2.y, endpoint2.z,
            midpoint.x, midpoint.y, midpoint.z);

        // Capture snapshot before subdivision for undo
        MeshSnapshot before = (commandHistory != null && synchronizer != null)
            ? MeshSnapshot.capture(modelRenderer) : null;

        // Delegate subdivision to GenericModelRenderer (which uses SubdivisionProcessor)
        int meshVertexIndex = modelRenderer.applyEdgeSubdivisionByPosition(
            midpoint, endpoint1, endpoint2
        );

        if (meshVertexIndex >= 0) {
            // Synchronize MeshManager and FaceRenderer with updated geometry
            synchronizeRenderersAfterSubdivision(modelRenderer);

            // Record undo command
            if (before != null) {
                MeshSnapshot after = MeshSnapshot.capture(modelRenderer);
                commandHistory.pushCompleted(
                    SnapshotCommand.subdivision(before, after, modelRenderer, synchronizer));
            }

            logger.info("Subdivided edge {}, created mesh vertex {}", hoveredEdgeIndex, meshVertexIndex);
            return meshVertexIndex;
        } else {
            logger.warn("Failed to apply subdivision to GenericModelRenderer");
            return -1;
        }
    }

    /**
     * Subdivide all currently selected edges at their midpoints.
     * If no edges are selected, falls back to subdividing the hovered edge.
     *
     * @return Number of edges successfully subdivided
     */
    public int subdivideSelectedEdges() {
        if (viewportRenderPipeline == null) {
            logger.warn("Cannot subdivide edges: render pipeline not initialized");
            return 0;
        }

        EdgeRenderer edgeRenderer = viewportRenderPipeline.getEdgeRenderer();
        VertexRenderer vertexRenderer = viewportRenderPipeline.getVertexRenderer();
        GenericModelRenderer modelRenderer = viewportRenderPipeline.getBlockModelRenderer();

        if (edgeRenderer == null || vertexRenderer == null || modelRenderer == null) {
            logger.warn("Cannot subdivide edges: renderers not available");
            return 0;
        }

        // Get selected edges
        Set<Integer> selectedEdges = edgeSelectionState.getSelectedEdgeIndices();

        if (selectedEdges.isEmpty()) {
            // Fall back to hovered edge if no selection
            logger.debug("No edges selected, falling back to hovered edge");
            int result = subdivideHoveredEdge();
            return result >= 0 ? 1 : 0;
        }

        // Collect all edge endpoint positions BEFORE any subdivision
        List<EdgeEndpoints> edgeEndpointsList = new ArrayList<>();
        for (int edgeIndex : selectedEdges) {
            int[] edgeVertexIndices = edgeRenderer.getEdgeVertexIndices(edgeIndex);
            if (edgeVertexIndices == null || edgeVertexIndices.length != 2) {
                logger.warn("Cannot get edge vertex indices for edge {}", edgeIndex);
                continue;
            }

            Vector3f endpoint1 = vertexRenderer.getVertexPosition(edgeVertexIndices[0]);
            Vector3f endpoint2 = vertexRenderer.getVertexPosition(edgeVertexIndices[1]);
            if (endpoint1 == null || endpoint2 == null) {
                logger.warn("Cannot get vertex positions for edge {}", edgeIndex);
                continue;
            }

            // Store copies of endpoints
            edgeEndpointsList.add(new EdgeEndpoints(
                new Vector3f(endpoint1),
                new Vector3f(endpoint2)
            ));
        }

        // Capture snapshot before batch subdivision for undo
        MeshSnapshot before = (commandHistory != null && synchronizer != null)
            ? MeshSnapshot.capture(modelRenderer) : null;

        // Apply subdivisions sequentially
        int successCount = 0;
        for (EdgeEndpoints endpoints : edgeEndpointsList) {
            Vector3f midpoint = calculateMidpoint(endpoints.endpoint1, endpoints.endpoint2);

            // Delegate to GenericModelRenderer (which uses SubdivisionProcessor)
            int meshVertexIndex = modelRenderer.applyEdgeSubdivisionByPosition(
                midpoint, endpoints.endpoint1, endpoints.endpoint2
            );

            if (meshVertexIndex >= 0) {
                successCount++;
            }
        }

        // Synchronize renderers after all subdivisions
        if (successCount > 0) {
            synchronizeRenderersAfterSubdivision(modelRenderer);

            // Record undo command for the entire batch
            if (before != null) {
                MeshSnapshot after = MeshSnapshot.capture(modelRenderer);
                commandHistory.pushCompleted(
                    SnapshotCommand.subdivision(before, after, modelRenderer, synchronizer));
            }

            // Clear edge selection (indices are invalid after subdivision)
            edgeSelectionState.clearSelection();
            edgeRenderer.clearSelection();
        }

        logger.info("Subdivided {} edges", successCount);
        return successCount;
    }

    // ========== Private Helper Methods ==========

    /**
     * Calculate midpoint between two endpoints.
     */
    private Vector3f calculateMidpoint(Vector3f endpoint1, Vector3f endpoint2) {
        return new Vector3f(
            (endpoint1.x + endpoint2.x) / 2.0f,
            (endpoint1.y + endpoint2.y) / 2.0f,
            (endpoint1.z + endpoint2.z) / 2.0f
        );
    }

    /**
     * Synchronize MeshManager and FaceRenderer with updated model geometry.
     */
    private void synchronizeRenderersAfterSubdivision(GenericModelRenderer modelRenderer) {
        // Sync MeshManager with GenericModelRenderer's actual vertices
        MeshManager meshManager = MeshManager.getInstance();
        float[] modelMeshVertices = modelRenderer.getAllMeshVertexPositions();
        if (modelMeshVertices != null) {
            meshManager.setMeshVertices(modelMeshVertices);
            logger.debug("Synced MeshManager with GenericModelRenderer: {} mesh vertices",
                modelMeshVertices.length / 3);
        }

        // Rebuild FaceRenderer data from GenericModelRenderer's triangles
        FaceRenderer faceRenderer = viewportRenderPipeline.getFaceRenderer();
        if (faceRenderer != null) {
            faceRenderer.setGenericModelRenderer(modelRenderer);
            faceRenderer.rebuildFromGenericModelRenderer();
            logger.debug("Rebuilt FaceRenderer from GenericModelRenderer triangles");
        }
    }

    /**
     * Simple record to hold edge endpoint positions.
     */
    private record EdgeEndpoints(Vector3f endpoint1, Vector3f endpoint2) {}
}
