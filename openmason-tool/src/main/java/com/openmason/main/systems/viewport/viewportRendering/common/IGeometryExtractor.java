package com.openmason.main.systems.viewport.viewportRendering.common;

import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;

import java.util.Collection;

/**
 * Common interface for geometry extraction from model data.
 * Extractors transform local-space model vertices into world-space geometry
 * using model transformation matrices.
 *
 * Implementations include VertexExtractor and EdgeExtractor.
 * Follows SOLID principles with Single Responsibility pattern.
 */
public interface IGeometryExtractor {

    /**
     * Extract geometry from a collection of model parts with transformation applied.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of geometry data in format specific to the extractor type
     */
    float[] extractGeometry(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform);
}
