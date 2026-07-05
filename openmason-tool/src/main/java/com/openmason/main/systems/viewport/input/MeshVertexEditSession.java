package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.VertexMoveCommand;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A live vertex-editing session over the current selection.
 *
 * <p>Resolves the active selection (per {@link EditMode}) to a set of unique
 * vertices with their original positions, then supports repeatedly applying a
 * position transform ({@link #apply}), reverting ({@link #revert}), or
 * committing with undo support ({@link #commit}).
 *
 * <p>The live-move recipe mirrors {@link
 * com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceTranslationHandler}
 * exactly: GenericModelRenderer per mesh index, VertexRenderer + EdgeRenderer per
 * unique index, then a FaceRenderer overlay rebuild. The commit path mirrors its
 * part-geometry sync + {@link VertexMoveCommand} push.
 *
 * <p>The transform function stays outside the session so modal tools (scale now,
 * rotate later) can reuse it unchanged. All GL-backed dependencies are
 * null-guarded so the session is constructible headless in tests.
 */
public class MeshVertexEditSession {

    private static final Logger logger = LoggerFactory.getLogger(MeshVertexEditSession.class);

    /** Squared distance below which a commit is considered a no-op (no undo entry). */
    private static final float NO_OP_EPSILON_SQ = 1e-12f;

    private final GenericModelRenderer modelRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final FaceRenderer faceRenderer;
    private final VertexSelectionState vertexSelectionState;
    private final EdgeSelectionState edgeSelectionState;
    private final FaceSelectionState faceSelectionState;
    private final ModelCommandHistory commandHistory;
    private final RendererSynchronizer synchronizer;

    // Unique vertex index → original position at session start (insertion-ordered)
    private final Map<Integer, Vector3f> originalPositions = new LinkedHashMap<>();

    public MeshVertexEditSession(GenericModelRenderer modelRenderer,
                                 VertexRenderer vertexRenderer,
                                 EdgeRenderer edgeRenderer,
                                 FaceRenderer faceRenderer,
                                 VertexSelectionState vertexSelectionState,
                                 EdgeSelectionState edgeSelectionState,
                                 FaceSelectionState faceSelectionState,
                                 ModelCommandHistory commandHistory,
                                 RendererSynchronizer synchronizer) {
        this.modelRenderer = modelRenderer;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
        this.faceRenderer = faceRenderer;
        this.vertexSelectionState = vertexSelectionState;
        this.edgeSelectionState = edgeSelectionState;
        this.faceSelectionState = faceSelectionState;
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    /**
     * Resolve the current selection for the given edit mode to a unique-vertex set
     * and capture original positions.
     *
     * @param mode The active edit mode (VERTEX, EDGE or FACE)
     * @return true if at least one vertex was resolved, false otherwise
     */
    public boolean begin(EditMode mode) {
        originalPositions.clear();

        if (modelRenderer == null) {
            return false;
        }

        Set<Integer> uniqueIndices = resolveUniqueIndices(mode);
        for (int uniqueIndex : uniqueIndices) {
            Vector3f pos = modelRenderer.getUniqueVertexPosition(uniqueIndex);
            if (pos != null) {
                originalPositions.put(uniqueIndex, new Vector3f(pos));
            }
        }

        logger.debug("Edit session began with {} unique vertices ({} mode)",
                originalPositions.size(), mode);
        return !originalPositions.isEmpty();
    }

    /**
     * @return Number of unique vertices in this session
     */
    public int vertexCount() {
        return originalPositions.size();
    }

    /**
     * @return Centroid of the original positions (model space), or null if the session is empty
     */
    public Vector3f centroid() {
        if (originalPositions.isEmpty()) {
            return null;
        }
        Vector3f centroid = new Vector3f();
        for (Vector3f pos : originalPositions.values()) {
            centroid.add(pos);
        }
        return centroid.div(originalPositions.size());
    }

    /**
     * Apply a position transform to every vertex in the session.
     * The function receives a COPY of the original position and returns the new
     * position — transforms are always relative to session start, never cumulative.
     *
     * @param originalToNew Maps original position → new position
     */
    public void apply(UnaryOperator<Vector3f> originalToNew) {
        for (Map.Entry<Integer, Vector3f> entry : originalPositions.entrySet()) {
            int uniqueIndex = entry.getKey();
            Vector3f newPos = originalToNew.apply(new Vector3f(entry.getValue()));
            if (newPos == null) {
                continue;
            }

            // Update the solid mesh per mesh index (FaceTranslationHandler recipe)
            int[] meshIndices = modelRenderer.getMeshIndicesForUniqueVertex(uniqueIndex);
            if (meshIndices != null) {
                for (int meshIndex : meshIndices) {
                    modelRenderer.updateVertexPosition(meshIndex, newPos);
                }
            }

            // Keep the wireframe (VertexRenderer + EdgeRenderer) in sync
            if (vertexRenderer != null) {
                vertexRenderer.updateVertexPosition(uniqueIndex, newPos);
            }
            if (edgeRenderer != null) {
                edgeRenderer.updateEdgesConnectedToVertexByIndex(uniqueIndex, newPos);
            }
        }

        // Rebuild face overlay from GenericModelRenderer
        if (faceRenderer != null) {
            faceRenderer.rebuildFromGenericModelRenderer();
        }
    }

    /**
     * Revert all vertices to their original positions (cancel).
     */
    public void revert() {
        apply(original -> original);
        logger.debug("Edit session reverted {} vertices", originalPositions.size());
    }

    /**
     * Commit the current positions: sync part geometry so edits survive a
     * subsequent part transform rebuild, and push a {@link VertexMoveCommand}
     * for undo/redo. A commit with no effective movement records nothing.
     *
     * @param label Undo history label (e.g. "Scale Selection")
     */
    public void commit(String label) {
        if (originalPositions.isEmpty() || modelRenderer == null) {
            return;
        }

        // Build per-mesh-index deltas (original → current), skipping unmoved vertices
        Map<Integer, VertexMoveCommand.VertexDelta> deltas = new java.util.HashMap<>();
        for (Map.Entry<Integer, Vector3f> entry : originalPositions.entrySet()) {
            Vector3f originalPos = entry.getValue();
            int[] meshIndices = modelRenderer.getMeshIndicesForUniqueVertex(entry.getKey());
            if (meshIndices == null) {
                continue;
            }
            for (int meshIndex : meshIndices) {
                Vector3f currentPos = modelRenderer.getVertexPosition(meshIndex);
                if (currentPos != null && currentPos.distanceSquared(originalPos) > NO_OP_EPSILON_SQ) {
                    deltas.put(meshIndex, new VertexMoveCommand.VertexDelta(
                        meshIndex, new Vector3f(originalPos), new Vector3f(currentPos)));
                }
            }
        }

        if (deltas.isEmpty()) {
            logger.debug("Edit session commit skipped: no effective movement");
            return;
        }

        // Sync partGeometry so edits survive a subsequent part transform rebuild
        ModelPartManager pm = modelRenderer.getPartManager();
        if (pm != null) {
            for (VertexMoveCommand.VertexDelta delta : deltas.values()) {
                pm.syncPartVertexFromWorldPos(delta.index(), delta.newPos());
            }
        }

        // Record undo command
        if (commandHistory != null && synchronizer != null) {
            commandHistory.pushCompleted(new VertexMoveCommand(
                deltas, label, modelRenderer, synchronizer));
        }

        logger.debug("Edit session committed '{}' for {} mesh vertices", label, deltas.size());
    }

    /**
     * Resolve the selection for the given edit mode to unique vertex indices.
     */
    private Set<Integer> resolveUniqueIndices(EditMode mode) {
        Set<Integer> uniqueIndices = new LinkedHashSet<>();

        switch (mode) {
            case VERTEX -> {
                // Vertex selection already holds unique vertex indices
                if (vertexSelectionState != null) {
                    uniqueIndices.addAll(vertexSelectionState.getSelectedVertexIndices());
                }
            }
            case EDGE -> {
                // Edge selection stores unique vertex indices per endpoint
                if (edgeSelectionState != null) {
                    uniqueIndices.addAll(edgeSelectionState.getAllSelectedVertexIndices());
                }
            }
            case FACE -> {
                // Face selection resolves to mesh vertex indices via the face renderer,
                // then maps to unique indices (same as FaceTranslationHandler's wireframe sync)
                if (faceSelectionState != null && faceRenderer != null) {
                    for (int faceIndex : faceSelectionState.getSelectedFaceIndices()) {
                        int[] meshIndices = faceRenderer.getTriangleVertexIndicesForFace(faceIndex);
                        if (meshIndices == null) {
                            continue;
                        }
                        for (int meshIndex : meshIndices) {
                            int uniqueIndex = modelRenderer.getUniqueIndexForMeshVertex(meshIndex);
                            if (uniqueIndex >= 0) {
                                uniqueIndices.add(uniqueIndex);
                            }
                        }
                    }
                }
            }
            default -> {
                // NONE: nothing to resolve
            }
        }

        return uniqueIndices;
    }
}
