package com.openmason.main.systems.rendering.model.gmr;

import com.openmason.main.systems.rendering.model.ModelPart;
import com.openmason.main.systems.rendering.model.UVMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default implementation of IModelStateManager.
 * Manages model parts, dimensions, and UV mode state.
 */
public class ModelStateManager implements IModelStateManager {

    private static final Logger logger = LoggerFactory.getLogger(ModelStateManager.class);

    private final List<ModelPart> parts = new ArrayList<>();
    private ModelDimensions dimensions;
    private UVMode uvMode = UVMode.FLAT;

    @Override
    public void setDimensions(int width, int height, int depth, double originX, double originY, double originZ) {
        this.dimensions = new ModelDimensions(width, height, depth, originX, originY, originZ);
        logger.debug("Set dimensions: {}x{}x{} at ({}, {}, {})", width, height, depth, originX, originY, originZ);
    }

    @Override
    public ModelDimensions getDimensions() {
        return dimensions;
    }

    @Override
    public boolean hasDimensions() {
        return dimensions != null && dimensions.width() > 0 && dimensions.height() > 0 && dimensions.depth() > 0;
    }

    @Override
    public void setUVMode(UVMode mode) {
        if (mode != null && mode != this.uvMode) {
            logger.debug("UV mode changed: {} -> {}", this.uvMode, mode);
            this.uvMode = mode;
        }
    }

    @Override
    public UVMode getUVMode() {
        return uvMode;
    }

    @Override
    public List<ModelPart> getParts() {
        return Collections.unmodifiableList(parts);
    }

    @Override
    public void clearParts() {
        parts.clear();
        logger.trace("Cleared all parts");
    }

    @Override
    public void addPart(ModelPart part) {
        if (part != null) {
            parts.add(part);
            logger.trace("Added part '{}' ({} vertices)", part.name(), part.getVertexCount());
        }
    }

    @Override
    public boolean hasParts() {
        return !parts.isEmpty();
    }

    @Override
    public int getTotalVertexCount() {
        int total = 0;
        for (ModelPart part : parts) {
            total += part.getVertexCount();
        }
        return total;
    }

    @Override
    public int getTotalIndexCount() {
        int total = 0;
        for (ModelPart part : parts) {
            total += part.getIndexCount();
        }
        return total;
    }

    @Override
    public AggregatedGeometry aggregateParts() {
        if (parts.isEmpty()) {
            return new AggregatedGeometry(null, null, null, 0, 0);
        }

        int totalVertices = getTotalVertexCount();
        int totalIndices = getTotalIndexCount();

        float[] vertices = new float[totalVertices * 3];
        float[] texCoords = new float[totalVertices * 2];
        int[] indices = totalIndices > 0 ? new int[totalIndices] : null;

        int vertexOffset = 0;
        int indexOffset = 0;
        int baseVertex = 0;

        for (ModelPart part : parts) {
            // Copy vertices
            if (part.vertices() != null) {
                System.arraycopy(part.vertices(), 0, vertices, vertexOffset * 3, part.vertices().length);
            }

            // Copy texture coordinates
            if (part.texCoords() != null) {
                System.arraycopy(part.texCoords(), 0, texCoords, vertexOffset * 2, part.texCoords().length);
            }

            // Copy and offset indices
            if (part.indices() != null && indices != null) {
                for (int i = 0; i < part.indices().length; i++) {
                    indices[indexOffset + i] = part.indices()[i] + baseVertex;
                }
                indexOffset += part.indices().length;
            }

            baseVertex += part.getVertexCount();
            vertexOffset += part.getVertexCount();
        }

        logger.trace("Aggregated {} parts: {} vertices, {} indices", parts.size(), totalVertices, totalIndices);
        return new AggregatedGeometry(vertices, texCoords, indices, totalVertices, totalIndices);
    }
}
