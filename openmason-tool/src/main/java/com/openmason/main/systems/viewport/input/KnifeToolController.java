package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeHoverDetector;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.KnifePreviewRenderer;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshEdge;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.TransformState;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Modal input controller for the knife tool.
 * Implements a two-click workflow: click first edge, click second edge on the same face,
 * then subdivides both edges and inserts an edge between the new vertices to split the face.
 *
 * <p>State machine:
 * <ul>
 *   <li>INACTIVE → toggle() → AWAITING_FIRST_CLICK</li>
 *   <li>AWAITING_FIRST_CLICK → click valid edge → AWAITING_SECOND_CLICK</li>
 *   <li>AWAITING_SECOND_CLICK → click valid edge on same face → execute cut → AWAITING_FIRST_CLICK</li>
 *   <li>Any phase → Esc or toggle() → INACTIVE</li>
 * </ul>
 *
 * <p>Only active in Edge edit mode.
 */
public class KnifeToolController {

    private static final Logger logger = LoggerFactory.getLogger(KnifeToolController.class);

    // Dependencies (set via setters, same pattern as other input controllers)
    private EdgeRenderer edgeRenderer;
    private GenericModelRenderer modelRenderer;
    private TransformState transformState;
    private KnifePreviewRenderer previewRenderer;

    // Tool state
    private KnifeToolState state = KnifeToolState.inactive();

    /**
     * Toggle the knife tool on/off.
     * Only activates in Edge edit mode.
     */
    public void toggle() {
        if (state.isActive()) {
            deactivate();
        } else {
            activate();
        }
    }

    /**
     * Activate the knife tool.
     */
    private void activate() {
        if (!EditModeManager.getInstance().isEdgeEditingAllowed()) {
            logger.debug("Knife tool requires Edge edit mode");
            return;
        }

        state = KnifeToolState.awaitingFirst();
        if (previewRenderer != null) {
            previewRenderer.setActive(true);
        }
        logger.info("Knife tool activated");
    }

    /**
     * Deactivate the knife tool and clear all state.
     */
    private void deactivate() {
        state = KnifeToolState.inactive();
        if (previewRenderer != null) {
            previewRenderer.setActive(false);
        }
        logger.info("Knife tool deactivated");
    }

    /**
     * @return true if the knife tool is currently active
     */
    public boolean isActive() {
        return state.isActive();
    }

    // =========================================================================
    // DEPENDENCY SETTERS
    // =========================================================================

    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
    }

    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
    }

    public void setTransformState(TransformState transformState) {
        this.transformState = transformState;
    }

    public void setPreviewRenderer(KnifePreviewRenderer previewRenderer) {
        this.previewRenderer = previewRenderer;
    }

    // =========================================================================
    // INPUT HANDLING
    // =========================================================================

    /**
     * Handle input for the knife tool.
     *
     * @param context Input context with mouse and keyboard state
     * @return true if input was consumed (blocks lower-priority controllers)
     */
    public boolean handleInput(InputContext context) {
        if (!state.isActive()) {
            return false;
        }

        // Deactivate if no longer in edge mode
        if (!EditModeManager.getInstance().isEdgeEditingAllowed()) {
            deactivate();
            return false;
        }

        // Check for Esc to cancel
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            deactivate();
            return true;
        }

        // Guard: need renderers
        if (edgeRenderer == null || !edgeRenderer.isInitialized() ||
            modelRenderer == null || !modelRenderer.isInitialized()) {
            return true; // Still consume input while active
        }

        return switch (state.phase()) {
            case AWAITING_FIRST_CLICK -> handleAwaitingFirstClick(context);
            case AWAITING_SECOND_CLICK -> handleAwaitingSecondClick(context);
            default -> false;
        };
    }

    /**
     * Handle input in AWAITING_FIRST_CLICK phase.
     * Detects edge hover with parameter and processes clicks.
     */
    private boolean handleAwaitingFirstClick(InputContext context) {
        // Run hover detection with parameter
        EdgeHoverDetector.EdgeHitResult hitResult = detectEdgeWithParameter(context);

        // Update edge renderer hover for visual feedback
        edgeRenderer.setHoveredEdgeIndex(hitResult.isHit() ? hitResult.edgeIndex() : -1);

        // Show hover point where cut will land
        if (previewRenderer != null) {
            previewRenderer.clearPreview();
            if (hitResult.isHit()) {
                Vector3f hoverPos = computePositionOnEdge(hitResult);
                previewRenderer.setHoverPoint(hoverPos);
            }
        }

        // Handle click on valid edge
        if (context.mouseInBounds && context.mouseClicked && hitResult.isHit()) {
            MeshTopology topology = modelRenderer.getTopology();
            if (topology == null) {
                return true;
            }

            MeshEdge edge = topology.getEdge(hitResult.edgeIndex());
            if (edge == null) {
                return true;
            }

            // Compute cut position for preview
            Vector3f posA = modelRenderer.getUniqueVertexPosition(edge.vertexA());
            Vector3f posB = modelRenderer.getUniqueVertexPosition(edge.vertexB());
            if (posA == null || posB == null) {
                return true;
            }

            Vector3f cutPos = new Vector3f(
                posA.x * (1f - hitResult.t()) + posB.x * hitResult.t(),
                posA.y * (1f - hitResult.t()) + posB.y * hitResult.t(),
                posA.z * (1f - hitResult.t()) + posB.z * hitResult.t()
            );

            // Transition to AWAITING_SECOND_CLICK
            state = state.withFirstCut(
                hitResult.edgeIndex(), hitResult.t(),
                edge.adjacentFaceIds(),
                edge.vertexA(), edge.vertexB(),
                cutPos
            );

            // Show cut point indicator
            if (previewRenderer != null) {
                previewRenderer.setCutPoint(cutPos);
            }

            logger.info("Knife tool: first cut on edge {} at t={}, faces={}",
                hitResult.edgeIndex(), hitResult.t(), Arrays.toString(edge.adjacentFaceIds()));
        }

        return true; // Consume all input while active
    }

    /**
     * Handle input in AWAITING_SECOND_CLICK phase.
     * Only allows clicking edges that share a face with the first edge.
     */
    private boolean handleAwaitingSecondClick(InputContext context) {
        // Run hover detection
        EdgeHoverDetector.EdgeHitResult hitResult = detectEdgeWithParameter(context);

        boolean isValidSecondEdge = false;
        Vector3f secondCutPos = null;

        if (hitResult.isHit()) {
            MeshTopology topology = modelRenderer.getTopology();
            if (topology != null) {
                MeshEdge secondEdge = topology.getEdge(hitResult.edgeIndex());
                if (secondEdge != null) {
                    // Check if second edge shares a face with first edge
                    isValidSecondEdge = sharesAnyFace(
                        state.firstEdgeAdjacentFaceIds(), secondEdge.adjacentFaceIds());

                    if (isValidSecondEdge) {
                        // Compute second cut position for preview line
                        Vector3f posA = modelRenderer.getUniqueVertexPosition(secondEdge.vertexA());
                        Vector3f posB = modelRenderer.getUniqueVertexPosition(secondEdge.vertexB());
                        if (posA != null && posB != null) {
                            secondCutPos = new Vector3f(
                                posA.x * (1f - hitResult.t()) + posB.x * hitResult.t(),
                                posA.y * (1f - hitResult.t()) + posB.y * hitResult.t(),
                                posA.z * (1f - hitResult.t()) + posB.z * hitResult.t()
                            );
                        }
                    }
                }
            }
        }

        // Update hover: only highlight valid second edges
        edgeRenderer.setHoveredEdgeIndex(isValidSecondEdge ? hitResult.edgeIndex() : -1);

        // Update preview: show line from first cut to second hover position + hover point
        if (previewRenderer != null) {
            previewRenderer.setCutPoint(state.firstCutPosition());
            if (isValidSecondEdge && secondCutPos != null) {
                previewRenderer.setPreviewLine(state.firstCutPosition(), secondCutPos);
                previewRenderer.setHoverPoint(secondCutPos);
            } else {
                previewRenderer.setPreviewLine(null, null);
                previewRenderer.setHoverPoint(null);
            }
        }

        // Handle click on valid second edge
        if (context.mouseInBounds && context.mouseClicked && isValidSecondEdge) {
            MeshTopology topology = modelRenderer.getTopology();
            if (topology != null) {
                MeshEdge secondEdge = topology.getEdge(hitResult.edgeIndex());
                if (secondEdge != null) {
                    executeKnifeCut(
                        state.firstUniqueVertexA(), state.firstUniqueVertexB(), state.firstT(),
                        secondEdge.vertexA(), secondEdge.vertexB(), hitResult.t()
                    );
                }
            }

            // Return to AWAITING_FIRST_CLICK for chain cuts
            state = KnifeToolState.awaitingFirst();
            if (previewRenderer != null) {
                previewRenderer.clearPreview();
            }
        }

        return true; // Consume all input while active
    }

    // =========================================================================
    // KNIFE CUT EXECUTION
    // =========================================================================

    /**
     * Execute the knife cut: subdivide both edges and insert an edge between the new vertices.
     */
    private void executeKnifeCut(int firstVertA, int firstVertB, float firstT,
                                  int secondVertA, int secondVertB, float secondT) {
        logger.info("Executing knife cut: edge ({},{}) t={} -> edge ({},{}) t={}",
            firstVertA, firstVertB, firstT, secondVertA, secondVertB, secondT);

        // Step 1: Subdivide first edge
        int newVertexA = modelRenderer.subdivideEdgeAtParameter(firstVertA, firstVertB, firstT);
        if (newVertexA < 0) {
            logger.warn("Knife cut failed: first edge subdivision failed");
            return;
        }

        // After first subdivision, topology is rebuilt. Need to re-resolve the second edge vertices
        // because unique vertex indices may have shifted. However, subdivideEdgeAtParameter rebuilds
        // the unique mapper and topology. The second edge's unique vertex IDs should still be valid
        // because subdivision only adds new vertices — it doesn't renumber existing ones.

        // Step 2: Subdivide second edge
        int newVertexB = modelRenderer.subdivideEdgeAtParameter(secondVertA, secondVertB, secondT);
        if (newVertexB < 0) {
            logger.warn("Knife cut failed: second edge subdivision failed");
            return;
        }

        // Step 3: Insert edge between the two new vertices to split the face
        boolean inserted = modelRenderer.insertEdgeBetweenVertices(newVertexA, newVertexB);
        if (!inserted) {
            logger.warn("Knife cut: edge insertion between {} and {} failed (vertices may not share a face)",
                newVertexA, newVertexB);
            return;
        }

        logger.info("Knife cut complete: new vertices {} and {}, face split", newVertexA, newVertexB);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Compute the world-space position on an edge from a hit result's t parameter.
     *
     * @return Interpolated position, or null if the edge cannot be resolved
     */
    private Vector3f computePositionOnEdge(EdgeHoverDetector.EdgeHitResult hitResult) {
        MeshTopology topology = modelRenderer.getTopology();
        if (topology == null) {
            return null;
        }

        MeshEdge edge = topology.getEdge(hitResult.edgeIndex());
        if (edge == null) {
            return null;
        }

        Vector3f posA = modelRenderer.getUniqueVertexPosition(edge.vertexA());
        Vector3f posB = modelRenderer.getUniqueVertexPosition(edge.vertexB());
        if (posA == null || posB == null) {
            return null;
        }

        float t = hitResult.t();
        return new Vector3f(
            posA.x * (1f - t) + posB.x * t,
            posA.y * (1f - t) + posB.y * t,
            posA.z * (1f - t) + posB.z * t
        );
    }

    /**
     * Run edge hover detection with parameter using EdgeHoverDetector.
     */
    private EdgeHoverDetector.EdgeHitResult detectEdgeWithParameter(InputContext context) {
        float[] edgePositions = edgeRenderer.getEdgePositions();
        int edgeCount = edgeRenderer.getEdgeCount();
        float lineWidth = edgeRenderer.getLineWidth();

        if (edgePositions == null || edgeCount == 0) {
            return EdgeHoverDetector.EdgeHitResult.NONE;
        }

        Matrix4f modelMatrix = (transformState != null)
            ? transformState.getTransformMatrix()
            : new Matrix4f();

        return EdgeHoverDetector.detectHoveredEdgeWithParameter(
            context.mouseX, context.mouseY,
            context.viewportWidth, context.viewportHeight,
            context.viewMatrix, context.projectionMatrix,
            modelMatrix,
            edgePositions, edgeCount, lineWidth
        );
    }

    /**
     * Check if two face ID arrays share any common face.
     */
    private static boolean sharesAnyFace(int[] facesA, int[] facesB) {
        if (facesA == null || facesB == null) {
            return false;
        }
        for (int fA : facesA) {
            for (int fB : facesB) {
                if (fA == fB) {
                    return true;
                }
            }
        }
        return false;
    }
}
