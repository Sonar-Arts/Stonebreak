package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Generic model renderer supporting arbitrary geometry.
 * NOT locked to 8-vertex cube topology - supports any vertex count.
 * Extends BaseRenderer for consistent initialization and rendering.
 *
 * Features:
 * - Multi-part model support
 * - Dynamic vertex updates for editing
 * - Arbitrary vertex counts (not cube-locked)
 * - OMO format loading support
 */
public class GenericModelRenderer extends BaseRenderer {

    // Multi-part model data
    private final List<ModelPart> parts = new ArrayList<>();

    // Current vertex data (mutable for live editing)
    private float[] currentVertices;
    private float[] currentTexCoords;
    private int[] currentIndices;

    // Texture
    private int textureId = 0;
    private boolean useTexture = false;

    // Stride: position (3) + texCoord (2) = 5 floats
    private static final int STRIDE = 5 * Float.BYTES;
    private static final int FLOATS_PER_VERTEX = 5;

    /**
     * Create a GenericModelRenderer.
     */
    public GenericModelRenderer() {
        logger.debug("GenericModelRenderer created");
    }

    @Override
    public String getDebugName() {
        return "GenericModelRenderer";
    }

    @Override
    public RenderPass getRenderPass() {
        return RenderPass.SCENE;
    }

    /**
     * Load model from OMO format document dimensions.
     * Creates a cube based on the geometry dimensions in the document.
     *
     * @param width Width of the model
     * @param height Height of the model
     * @param depth Depth of the model
     * @param originX Origin X position
     * @param originY Origin Y position
     * @param originZ Origin Z position
     */
    public void loadFromDimensions(int width, int height, int depth,
                                   double originX, double originY, double originZ) {
        parts.clear();

        // Scale from pixel dimensions to world units
        // Convention: 16 pixels = 1 world unit (standard block size)
        // This preserves aspect ratios: 16x16x16 → 1x1x1, 8x16x8 → 0.5x1x0.5, etc.
        final float PIXELS_PER_UNIT = 16.0f;

        Vector3f origin = new Vector3f(
            (float) originX / PIXELS_PER_UNIT,
            (float) originY / PIXELS_PER_UNIT,
            (float) originZ / PIXELS_PER_UNIT
        );

        Vector3f size = new Vector3f(
            width / PIXELS_PER_UNIT,
            height / PIXELS_PER_UNIT,
            depth / PIXELS_PER_UNIT
        );

        ModelPart cubePart = ModelPart.createCube("main", origin, size);
        parts.add(cubePart);

        rebuildGeometry();
        logger.info("Created model from dimensions: {}x{}x{} pixels → {}x{}x{} units at ({}, {}, {})",
                width, height, depth, size.x, size.y, size.z, origin.x, origin.y, origin.z);
    }

    /**
     * Load model from a list of ModelParts.
     *
     * @param modelParts The parts to load
     */
    public void loadParts(List<ModelPart> modelParts) {
        parts.clear();
        if (modelParts != null) {
            parts.addAll(modelParts);
        }
        rebuildGeometry();
        logger.debug("Loaded {} model parts", parts.size());
    }

    /**
     * Add a single part to the model.
     *
     * @param part The part to add
     */
    public void addPart(ModelPart part) {
        if (part != null) {
            parts.add(part);
            rebuildGeometry();
        }
    }

    /**
     * Clear all parts.
     */
    public void clearParts() {
        parts.clear();
        currentVertices = null;
        currentTexCoords = null;
        currentIndices = null;
        vertexCount = 0;
        indexCount = 0;
    }

    /**
     * Update a vertex position by its global index.
     * Useful for live editing/dragging.
     *
     * @param globalIndex The global vertex index across all parts
     * @param position The new position
     */
    public void updateVertexPosition(int globalIndex, Vector3f position) {
        if (currentVertices == null || globalIndex < 0) {
            return;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= currentVertices.length) {
            logger.warn("Vertex index {} out of bounds", globalIndex);
            return;
        }

        // Update in current vertices array (used for interleaved data)
        // Need to update the position part of interleaved data
        int interleavedOffset = globalIndex * FLOATS_PER_VERTEX;
        float[] interleavedData = buildInterleavedData();
        if (interleavedOffset + 2 < interleavedData.length) {
            interleavedData[interleavedOffset] = position.x;
            interleavedData[interleavedOffset + 1] = position.y;
            interleavedData[interleavedOffset + 2] = position.z;
            updateVBO(interleavedData);
        }

        // Update source arrays
        currentVertices[offset] = position.x;
        currentVertices[offset + 1] = position.y;
        currentVertices[offset + 2] = position.z;

        logger.trace("Updated vertex {} to ({}, {}, {})", globalIndex, position.x, position.y, position.z);
    }

    /**
     * Update all vertex positions at once.
     * Compatible with CubeModelRenderer API - expects positions in format [x0,y0,z0, x1,y1,z1, ...].
     * More efficient than multiple individual updates as it only uploads to GPU once.
     *
     * @param positions Array of vertex positions (must match current vertex count * 3)
     */
    public void updateVertexPositions(float[] positions) {
        if (!initialized) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        if (positions == null) {
            logger.error("Cannot update vertex positions: positions array is null");
            return;
        }

        if (currentVertices == null) {
            logger.warn("Cannot update vertex positions: no vertices loaded");
            return;
        }

        int expectedLength = currentVertices.length;
        if (positions.length != expectedLength) {
            logger.error("Invalid positions array: expected {} floats, got {}", expectedLength, positions.length);
            return;
        }

        try {
            // Update current vertices array
            System.arraycopy(positions, 0, currentVertices, 0, positions.length);

            // Build interleaved data and upload to GPU
            float[] interleavedData = buildInterleavedData();
            updateVBO(interleavedData);

            logger.trace("Updated all {} vertex positions", positions.length / 3);

        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    /**
     * Get vertex position by global index.
     *
     * @param globalIndex The global vertex index
     * @return The vertex position, or null if invalid
     */
    public Vector3f getVertexPosition(int globalIndex) {
        if (currentVertices == null || globalIndex < 0) {
            return null;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= currentVertices.length) {
            return null;
        }

        return new Vector3f(
                currentVertices[offset],
                currentVertices[offset + 1],
                currentVertices[offset + 2]
        );
    }

    /**
     * Get total vertex count across all parts.
     *
     * @return Total vertex count
     */
    public int getTotalVertexCount() {
        return currentVertices != null ? currentVertices.length / 3 : 0;
    }

    /**
     * Set the texture for rendering.
     *
     * @param textureId OpenGL texture ID
     */
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.useTexture = textureId > 0;
    }

    /**
     * Rebuild geometry from current parts.
     * Call after adding/removing parts or updating part data.
     */
    private void rebuildGeometry() {
        if (parts.isEmpty()) {
            currentVertices = null;
            currentTexCoords = null;
            currentIndices = null;
            vertexCount = 0;
            indexCount = 0;
            return;
        }

        // Calculate total sizes
        int totalVertices = 0;
        int totalIndices = 0;
        for (ModelPart part : parts) {
            totalVertices += part.getVertexCount();
            totalIndices += part.getIndexCount();
        }

        // Allocate arrays
        currentVertices = new float[totalVertices * 3];
        currentTexCoords = new float[totalVertices * 2];
        currentIndices = totalIndices > 0 ? new int[totalIndices] : null;

        // Copy data from parts
        int vertexOffset = 0;
        int indexOffset = 0;
        int baseVertex = 0;

        for (ModelPart part : parts) {
            // Copy vertices
            if (part.vertices() != null) {
                System.arraycopy(part.vertices(), 0, currentVertices, vertexOffset * 3, part.vertices().length);
            }

            // Copy tex coords
            if (part.texCoords() != null) {
                System.arraycopy(part.texCoords(), 0, currentTexCoords, vertexOffset * 2, part.texCoords().length);
            }

            // Copy indices (offset by base vertex)
            if (part.indices() != null && currentIndices != null) {
                for (int i = 0; i < part.indices().length; i++) {
                    currentIndices[indexOffset + i] = part.indices()[i] + baseVertex;
                }
                indexOffset += part.indices().length;
            }

            baseVertex += part.getVertexCount();
            vertexOffset += part.getVertexCount();
        }

        vertexCount = totalVertices;
        indexCount = totalIndices;

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = buildInterleavedData();
            updateVBO(interleavedData);
            if (currentIndices != null) {
                updateEBO(currentIndices);
            }
        }

        logger.debug("Rebuilt geometry: {} vertices, {} indices", vertexCount, indexCount);
    }

    /**
     * Build interleaved vertex data (position + texCoord).
     */
    private float[] buildInterleavedData() {
        if (currentVertices == null) {
            return new float[0];
        }

        int count = currentVertices.length / 3;
        float[] interleaved = new float[count * FLOATS_PER_VERTEX];

        for (int i = 0; i < count; i++) {
            int srcPos = i * 3;
            int srcTex = i * 2;
            int dst = i * FLOATS_PER_VERTEX;

            // Position
            interleaved[dst] = currentVertices[srcPos];
            interleaved[dst + 1] = currentVertices[srcPos + 1];
            interleaved[dst + 2] = currentVertices[srcPos + 2];

            // TexCoord
            if (currentTexCoords != null && srcTex + 1 < currentTexCoords.length) {
                interleaved[dst + 3] = currentTexCoords[srcTex];
                interleaved[dst + 4] = currentTexCoords[srcTex + 1];
            } else {
                interleaved[dst + 3] = 0.0f;
                interleaved[dst + 4] = 0.0f;
            }
        }

        return interleaved;
    }

    @Override
    protected GeometryData createGeometry() {
        float[] interleaved = buildInterleavedData();
        if (interleaved.length == 0) {
            // Return minimal valid geometry for empty state
            return GeometryData.nonIndexed(new float[0], 0, STRIDE);
        }

        if (currentIndices != null && currentIndices.length > 0) {
            return GeometryData.indexed(interleaved, currentIndices, vertexCount, STRIDE);
        } else {
            return GeometryData.nonIndexed(interleaved, vertexCount, STRIDE);
        }
    }

    @Override
    protected void configureVertexAttributes() {
        // Position attribute (location = 0): 3 floats
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);

        // TexCoord attribute (location = 1): 2 floats
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
    }

    @Override
    protected void doRender(ShaderProgram shader, RenderContext context) {
        if (vertexCount == 0) {
            return;
        }

        // Bind texture if available
        if (useTexture && textureId > 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
            shader.setBool("uUseTexture", true);
            shader.setInt("uTexture", 0);
        } else {
            shader.setBool("uUseTexture", false);
        }

        // Draw
        if (indexCount > 0 && ebo != 0) {
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        }
    }

    @Override
    protected void setUniforms(ShaderProgram shader, RenderContext context, org.joml.Matrix4f modelMatrix) {
        // Set model matrix for per-part transforms
        shader.setMat4("uModelMatrix", modelMatrix);
    }
}
