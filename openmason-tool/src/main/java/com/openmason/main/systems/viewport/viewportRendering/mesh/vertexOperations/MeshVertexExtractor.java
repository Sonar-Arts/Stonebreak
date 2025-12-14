package com.openmason.main.systems.viewport.viewportRendering.mesh.vertexOperations;

import com.openmason.main.systems.viewport.viewportRendering.common.GeometryExtractionUtils;
import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Single Responsibility: Extracts vertex geometry from model data with transformations.
 * This class extracts vertices from model parts and applies global/local transformations,
 * with support for both all vertices and unique vertex extraction.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles vertex extraction from model data
 * - Open/Closed: Can be extended for additional extraction strategies (e.g., unique vertices)
 * - Liskov Substitution: Implements IGeometryExtractor contract
 * - Interface Segregation: Focused interface for geometry extraction
 * - Dependency Inversion: Depends on abstractions (IGeometryExtractor, ModelDefinition)
 *
 * KISS Principle: Straightforward vertex extraction with transformation application and deduplication.
 * DRY Principle: Uses GeometryExtractionUtils for shared validation and transformation logic.
 * YAGNI Principle: Implements vertex extraction with useful unique vertex filtering, no unnecessary features.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 *
 * Architecture Note: Supports mesh operations instead of directly feeding the renderer.
 * This class provides mesh data that can be used by vertex operation classes like
 * MeshVertexMerger, MeshVertexPositionUpdater, etc.
 */
public class MeshVertexExtractor implements IGeometryExtractor {

  private static final Logger logger = LoggerFactory.getLogger(MeshVertexExtractor.class);

  /** Epsilon tolerance for vertex position matching during deduplication. */
  private static final float VERTEX_EPSILON = 0.0001f;

  /**
   * Extracts vertices from model parts with transformation applied.
   * Interface implementation that validates inputs using common utilities.
   *
   * @param parts Model parts to extract vertices from
   * @param globalTransform Global transformation matrix
   * @return Array of vertex positions [x1,y1,z1, x2,y2,z2, ...]
   */
  @Override
  public float[] extractGeometry(
      Collection<ModelDefinition.ModelPart> parts,
      Matrix4f globalTransform) {
    GeometryExtractionUtils.validateExtractionParams(parts, globalTransform);

    if (parts.isEmpty()) {
      return new float[0];
    }

    return extractVertices(parts, globalTransform);
  }

  /**
   * Extracts vertices from model parts with transformation applied.
   * Note: Callers should use extractGeometry() for validation, or ensure inputs are valid.
   *
   * @param parts Model parts to extract vertices from
   * @param globalTransform Global transformation matrix
   * @return Array of vertex positions [x1,y1,z1, x2,y2,z2, ...]
   */
  public float[] extractVertices(
      Collection<ModelDefinition.ModelPart> parts,
      Matrix4f globalTransform) {
    if (parts == null || parts.isEmpty()) {
      return new float[0];
    }

    int totalVertices = GeometryExtractionUtils.countTotalVertices(parts);

    if (totalVertices == 0) {
      return new float[0];
    }

    float[] result = new float[totalVertices];
    int offset = 0;

    Vector4f vertex = new Vector4f();
    for (ModelDefinition.ModelPart part : parts) {
      if (part == null) {
        continue;
      }

      try {
        float[] localVertices = part.getVerticesAtOrigin();
        Matrix4f partTransform = part.getTransformationMatrix();
        Matrix4f finalTransform = GeometryExtractionUtils.createFinalTransform(
            globalTransform, partTransform);

        for (int i = 0; i < localVertices.length; i += 3) {
          GeometryExtractionUtils.transformVertex(
              localVertices, i, finalTransform, vertex);

          result[offset++] = vertex.x;
          result[offset++] = vertex.y;
          result[offset++] = vertex.z;
        }

      } catch (Exception e) {
        logger.error("Error extracting vertices from part: {}", part.getName(), e);
      }
    }

    return result;
  }

  /**
   * Extracts unique vertices from model parts with transformation applied.
   * Deduplicates vertices at the same position using epsilon comparison.
   * This is useful for operations that need to work with the actual mesh topology
   * rather than the duplicated vertices used for rendering.
   *
   * @param parts Model parts to extract vertices from
   * @param globalTransform Global transformation matrix
   * @return Array of unique vertex positions [x1,y1,z1, x2,y2,z2, ...]
   */
  public float[] extractUniqueVertices(
      Collection<ModelDefinition.ModelPart> parts,
      Matrix4f globalTransform) {
    if (parts == null || parts.isEmpty()) {
      return new float[0];
    }

    float[] allVertices = extractVertices(parts, globalTransform);

    if (allVertices.length == 0) {
      return new float[0];
    }

    List<Vector3f> uniqueVertices = new ArrayList<>();

    for (int i = 0; i < allVertices.length; i += 3) {
      Vector3f vertex = new Vector3f(
          allVertices[i], allVertices[i + 1], allVertices[i + 2]);

      boolean isDuplicate = false;
      for (Vector3f unique : uniqueVertices) {
        if (vertex.distance(unique) < VERTEX_EPSILON) {
          isDuplicate = true;
          break;
        }
      }

      if (!isDuplicate) {
        uniqueVertices.add(vertex);
      }
    }

    float[] result = new float[uniqueVertices.size() * 3];
    int offset = 0;
    for (Vector3f vertex : uniqueVertices) {
      result[offset++] = vertex.x;
      result[offset++] = vertex.y;
      result[offset++] = vertex.z;
    }

    logger.debug("Extracted {} unique vertices from {} total vertices",
        uniqueVertices.size(), allVertices.length / 3);

    return result;
  }
}
