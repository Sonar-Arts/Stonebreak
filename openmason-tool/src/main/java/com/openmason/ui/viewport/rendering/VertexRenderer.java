package com.openmason.ui.viewport.rendering;

import com.openmason.deprecated.LegacyCowStonebreakModel;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model vertices as colored points, similar to Blender's vertex display mode.
 * Follows KISS, SOLID, DRY, and YAGNI principles.
 *
 * Single Responsibility: Extract and render model vertices as points.
 */
public class VertexRenderer {

    private static final Logger logger = LoggerFactory.getLogger(VertexRenderer.class);

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int vertexCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;
    private float pointSize = 5.0f;
    private final Vector3f vertexColor = new Vector3f(1.0f, 0.6f, 0.0f); // Blender's orange

    // Dirty flag to track when vertex data needs updating
    private boolean vertexDataDirty = true;
    private LegacyCowStonebreakModel lastModel = null;
    private Matrix4f lastTransform = null;

    /**
     * Initialize the vertex renderer.
     */
    public void initialize() {
        if (initialized) {
            logger.debug("VertexRenderer already initialized");
            return;
        }

        try {
            logger.info("Initializing VertexRenderer...");

            // Generate VAO and VBO
            vao = glGenVertexArrays();
            vbo = glGenBuffers();

            // Bind VAO
            glBindVertexArray(vao);

            // Create empty VBO (will be populated when model is set)
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

            // Configure vertex attributes
            // Position attribute (location = 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;
            logger.info("VertexRenderer initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize VertexRenderer", e);
            cleanup();
            throw new RuntimeException("VertexRenderer initialization failed", e);
        }
    }

    /**
     * Update vertex data for BlockModel (.OMO files) with transformation.
     * BlockModels are simple cubes with 24 vertices (4 per face × 6 faces).
     */
    public void updateVertexDataForBlockModel(Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        // Check if update is needed
        boolean transformChanged = (lastTransform == null || !lastTransform.equals(transformMatrix));

        if (!transformChanged && !vertexDataDirty) {
            return; // No update needed
        }

        try {
            // Extract vertices from cube mesh (1x1x1 cube centered at origin)
            // CubeNetMeshGenerator provides vertices in format: x, y, z, u, v (5 floats per vertex)
            float[] cubeVertices = com.openmason.rendering.blockmodel.CubeNetMeshGenerator.generateVertices();

            List<Float> vertices = new ArrayList<>();

            // Extract position data (skip UV coordinates)
            Vector4f vertex = new Vector4f();
            for (int i = 0; i < cubeVertices.length; i += 5) {
                // Get position (x, y, z) and skip UVs (u, v)
                vertex.set(cubeVertices[i], cubeVertices[i + 1], cubeVertices[i + 2], 1.0f);
                transformMatrix.transform(vertex);

                vertices.add(vertex.x);
                vertices.add(vertex.y);
                vertices.add(vertex.z);
            }

            // Convert to float array
            float[] vertexArray = new float[vertices.size()];
            for (int i = 0; i < vertices.size(); i++) {
                vertexArray[i] = vertices.get(i);
            }

            vertexCount = vertexArray.length / 3;

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexArray, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            // Update state
            lastModel = null; // BlockModel doesn't use model object
            lastTransform = new Matrix4f(transformMatrix);
            vertexDataDirty = false;

        } catch (Exception e) {
            logger.error("Error updating BlockModel vertex data", e);
        }
    }

    /**
     * Update vertex data from model with transformation.
     * Only updates GPU buffer when model or transform changes (dirty flag pattern).
     */
    public void updateVertexData(LegacyCowStonebreakModel model, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (model == null) {
            logger.warn("Model is null, clearing vertex data");
            vertexCount = 0;
            vertexDataDirty = false;
            return;
        }

        // Check if update is needed
        boolean modelChanged = (lastModel != model);
        boolean transformChanged = (lastTransform == null || !lastTransform.equals(transformMatrix));

        if (!modelChanged && !transformChanged && !vertexDataDirty) {
            return; // No update needed
        }

        try {
            // Extract vertices from all model parts
            List<Float> vertices = new ArrayList<>();
            ModelDefinition.CowModelDefinition cowModel = model.getModelDefinition();

            if (cowModel != null && cowModel.getParts() != null) {
                ModelDefinition.ModelParts parts = cowModel.getParts();

                // Extract vertices from each part
                extractPartVertices(parts.getBody(), transformMatrix, vertices);
                extractPartVertices(parts.getHead(), transformMatrix, vertices);
                extractPartVertices(parts.getUdder(), transformMatrix, vertices);
                extractPartVertices(parts.getTail(), transformMatrix, vertices);

                if (parts.getLegs() != null) {
                    for (ModelDefinition.ModelPart leg : parts.getLegs()) {
                        extractPartVertices(leg, transformMatrix, vertices);
                    }
                }

                if (parts.getHorns() != null) {
                    for (ModelDefinition.ModelPart horn : parts.getHorns()) {
                        extractPartVertices(horn, transformMatrix, vertices);
                    }
                }
            }

            // Convert to float array
            float[] vertexArray = new float[vertices.size()];
            for (int i = 0; i < vertices.size(); i++) {
                vertexArray[i] = vertices.get(i);
            }

            vertexCount = vertexArray.length / 3;

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexArray, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            // Update state
            lastModel = model;
            lastTransform = new Matrix4f(transformMatrix);
            vertexDataDirty = false;

        } catch (Exception e) {
            logger.error("Error updating vertex data", e);
        }
    }

    /**
     * Extract vertices from a model part and apply transformations.
     */
    private void extractPartVertices(ModelDefinition.ModelPart part, Matrix4f globalTransform, List<Float> outVertices) {
        if (part == null) {
            return;
        }

        try {
            // Get vertices at origin
            float[] partVertices = part.getVerticesAtOrigin();

            // Get part's local transformation matrix
            Matrix4f partTransform = part.getTransformationMatrix();

            // Combine global and local transforms
            Matrix4f finalTransform = new Matrix4f(globalTransform).mul(partTransform);

            // Transform each vertex
            Vector4f vertex = new Vector4f();
            for (int i = 0; i < partVertices.length; i += 3) {
                vertex.set(partVertices[i], partVertices[i + 1], partVertices[i + 2], 1.0f);
                finalTransform.transform(vertex);

                outVertices.add(vertex.x);
                outVertices.add(vertex.y);
                outVertices.add(vertex.z);
            }

        } catch (Exception e) {
            logger.error("Error extracting vertices from part: {}", part.getName(), e);
        }
    }

    /**
     * Render vertices as points.
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized");
            return;
        }

        if (!enabled) {
            logger.debug("VertexRenderer disabled");
            return;
        }

        if (vertexCount == 0) {
            logger.debug("No vertices to render");
            return;
        }

        try {
            // Use shader
            shader.use();

            // Calculate MVP matrix (model is identity since vertices are already transformed)
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

            // Upload uniforms
            shader.setMat4("uMVPMatrix", mvpMatrix);
            shader.setVec3("uColor", vertexColor);

            // Set point size (fixed function)
            glPointSize(pointSize);

            // Save and modify depth function to ensure points are visible on surfaces
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL); // Use LEQUAL to allow points on surfaces

            // Render vertices
            glBindVertexArray(vao);
            glDrawArrays(GL_POINTS, 0, vertexCount);
            glBindVertexArray(0);

            // Restore previous depth function
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering vertices", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Mark vertex data as dirty (needs update).
     */
    public void markDirty() {
        this.vertexDataDirty = true;
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        vertexCount = 0;
        initialized = false;
        lastModel = null;
        lastTransform = null;
        logger.debug("VertexRenderer cleanup complete");
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        logger.debug("Vertex rendering {}", enabled ? "enabled" : "disabled");
    }

    public float getPointSize() {
        return pointSize;
    }

    public void setPointSize(float pointSize) {
        this.pointSize = Math.max(1.0f, Math.min(15.0f, pointSize));
        logger.trace("Point size set to: {}", this.pointSize);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Vector3f getVertexColor() {
        return new Vector3f(vertexColor);
    }

    public void setVertexColor(float r, float g, float b) {
        this.vertexColor.set(r, g, b);
    }
}
