package com.openmason.main.systems.viewport.viewportRendering.vertex;

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
 * Extracts vertices from model data and applies transformations.
 * Implements IGeometryExtractor for consistency with EdgeExtractor.
 * Vertices are stored in local space and rendered in world space.
 */
public class VertexExtractor implements IGeometryExtractor {

  private static final Logger logger = LoggerFactory.getLogger(VertexExtractor.class);
  private static final float VERTEX_EPSILON = 0.0001f;

  /**
   * Extracts vertices from model parts with transformation applied.
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
