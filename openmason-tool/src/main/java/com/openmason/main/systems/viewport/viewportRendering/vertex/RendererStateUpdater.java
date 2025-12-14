package com.openmason.main.systems.viewport.viewportRendering.vertex;

import com.openmason.main.systems.viewport.viewportRendering.mesh.operations.VertexMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Single Responsibility: Updates VertexRenderer state after merge operations.
 * Handles all the post-merge state synchronization including mesh mapping,
 * selection management, and VBO rebuilding.
 *
 * SOLID Principles:
 * - Single Responsibility: Only updates renderer state after merges
 * - Open/Closed: Can be extended for different state update strategies
 * - Interface Segregation: Focused interface for state updates
 * - Dependency Inversion: Operates on provided data, doesn't own renderer state
 *
 * This class acts as a coordinator between merge results and renderer state,
 * ensuring all necessary updates happen in the correct order.
 */
public class RendererStateUpdater {

  private static final Logger logger = LoggerFactory.getLogger(
      RendererStateUpdater.class);

  /**
   * Context for state update operations.
   * Contains all the mutable state that needs to be updated.
   */
  public static class UpdateContext {
    // Mutable state fields
    public float[] vertexPositions;
    public int vertexCount;
    public Map<Integer, Integer> originalToCurrentMapping;
    public int selectedVertexIndex;

    // Immutable references
    public final float[] allMeshVertices;
    public final Map<Integer, List<Integer>> uniqueToMeshMapping;

    public UpdateContext(
        float[] allMeshVertices,
        Map<Integer, List<Integer>> uniqueToMeshMapping) {
      this.allMeshVertices = allMeshVertices;
      this.uniqueToMeshMapping = uniqueToMeshMapping;
    }
  }

  /**
   * Functional interface for VBO rebuilding.
   * Allows the renderer to provide its VBO rebuild logic without exposing internals.
   */
  @FunctionalInterface
  public interface VBORebuilder {
    void rebuild();
  }

  /**
   * Functional interface for mesh mapping rebuild.
   * Allows the renderer to provide its mapping rebuild logic.
   */
  @FunctionalInterface
  public interface MeshMappingRebuilder {
    void rebuild(float[] newVertexPositions, float[] allMeshVertices);
  }

  private final UpdateContext context;
  private final VBORebuilder vboRebuilder;
  private final MeshMappingRebuilder meshMappingRebuilder;

  /**
   * Create a renderer state updater.
   *
   * @param context The update context with mutable state
   * @param vboRebuilder Callback to rebuild VBO
   * @param meshMappingRebuilder Callback to rebuild mesh mapping
   */
  public RendererStateUpdater(
      UpdateContext context,
      VBORebuilder vboRebuilder,
      MeshMappingRebuilder meshMappingRebuilder) {
    this.context = context;
    this.vboRebuilder = vboRebuilder;
    this.meshMappingRebuilder = meshMappingRebuilder;
  }

  /**
   * Update renderer state with merge results.
   * Performs all necessary state updates in the correct order:
   * 1. Update vertex positions and count
   * 2. Update persistent mapping
   * 3. Rebuild mesh mapping
   * 4. Clear invalid selection
   * 5. Rebuild VBO
   *
   * @param mergeResult The result from VertexMerger
   * @return Updated context with new state
   */
  public UpdateContext applyMergeResult(VertexMerger.MergeResult mergeResult) {
    logger.debug("Applying merge result to renderer state");

    // Step 1: Update vertex data
    context.vertexPositions = mergeResult.newVertexPositions;
    context.vertexCount = mergeResult.newVertexCount;

    logger.trace("Updated vertex count: {} -> {}",
        context.vertexPositions.length / 3, context.vertexCount);

    // Step 2: Update persistent mapping
    context.originalToCurrentMapping = mergeResult.updatedOriginalMapping;

    logger.debug("Updated persistent mapping after merge: {}",
        context.originalToCurrentMapping);

    // Step 3: Rebuild unique-to-mesh mapping if mesh vertices exist
    if (context.allMeshVertices != null) {
      meshMappingRebuilder.rebuild(
          mergeResult.newVertexPositions, context.allMeshVertices);
      logger.trace("Rebuilt unique-to-mesh mapping");
    }

    // Step 4: Clear selection if it referenced a deleted vertex
    if (context.selectedVertexIndex >= mergeResult.newVertexCount) {
      int oldSelection = context.selectedVertexIndex;
      context.selectedVertexIndex = -1;
      logger.debug("Cleared selection {} (vertex was merged)", oldSelection);
    }

    // Step 5: Rebuild VBO with new vertex data
    vboRebuilder.rebuild();
    logger.trace("Rebuilt VBO with new vertex data");

    logger.info("Renderer state update complete: {} vertices, selection: {}",
        context.vertexCount, context.selectedVertexIndex);

    return context;
  }

  /**
   * Get the updated context after state changes.
   *
   * @return The update context
   */
  public UpdateContext getContext() {
    return context;
  }
}
