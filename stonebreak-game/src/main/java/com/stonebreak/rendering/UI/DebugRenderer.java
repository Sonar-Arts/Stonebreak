package com.stonebreak.rendering.UI;

// Standard Library Imports
import java.nio.FloatBuffer;
import java.util.List;

// JOML Math Library
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

// LWJGL Core
import org.lwjgl.BufferUtils;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports (GL11)
import static org.lwjgl.opengl.GL11.*;

// Stonebreak Game Components
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.ShaderProgram;

/**
 * Specialized renderer for debug visualizations including wireframe bounding boxes and paths.
 * This renderer handles all debug-related rendering tasks that are separate from the main game rendering pipeline.
 */
public class DebugRenderer {
    private ShaderProgram shaderProgram;
    private Matrix4f projectionMatrix;
    private int wireframeVao;

    public DebugRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        createWireframe();
    }
    
    /**
     * Creates the wireframe VAO for debug bounding box rendering.
     */
    private void createWireframe() {
        // Create vertices for a unit cube wireframe (12 edges, 24 vertices)
        float[] vertices = {
            // Bottom face edges
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f, // Front edge
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f, // Right edge  
             0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f, // Back edge
            -0.5f, -0.5f,  0.5f, -0.5f, -0.5f, -0.5f, // Left edge
            
            // Top face edges
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f, // Front edge
             0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f, // Right edge
             0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f, // Back edge
            -0.5f,  0.5f,  0.5f, -0.5f,  0.5f, -0.5f, // Left edge
            
            // Vertical edges
            -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, // Front-left
             0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, // Front-right
             0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, // Back-right
            -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f  // Back-left
        };
        
        // Create VAO
        wireframeVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(wireframeVao);
        
        // Create VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, buffer, GL20.GL_STATIC_DRAW);
        
        // Define vertex attributes
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Unbind
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders a wireframe bounding box for debug purposes.
     * @param boundingBox The bounding box to render
     * @param color The color of the wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframeBoundingBox(Entity.BoundingBox boundingBox, Vector3f color) {
        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        
        // Set up OpenGL state for wireframe rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set view and projection matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }
        
        // Calculate model matrix for the bounding box
        Matrix4f modelMatrix = new Matrix4f();
        float centerX = (boundingBox.minX + boundingBox.maxX) / 2.0f;
        float centerY = (boundingBox.minY + boundingBox.maxY) / 2.0f;
        float centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2.0f;
        float scaleX = boundingBox.maxX - boundingBox.minX;
        float scaleY = boundingBox.maxY - boundingBox.minY;
        float scaleZ = boundingBox.maxZ - boundingBox.minZ;
        
        modelMatrix.translation(centerX, centerY, centerZ);
        modelMatrix.scale(scaleX, scaleY, scaleZ);
        
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        
        // Set shader uniforms for solid color rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(color.x, color.y, color.z, 1.0f));
        
        // Render the wireframe
        GL30.glBindVertexArray(wireframeVao);
        glDrawArrays(GL_LINES, 0, 24); // 24 vertices for 12 edges
        GL30.glBindVertexArray(0);
        
        // Restore OpenGL state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders a wireframe path as connected line segments.
     * @param pathPoints The list of points forming the path
     * @param color The color of the path wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframePath(List<Vector3f> pathPoints, Vector3f color) {
        if (pathPoints == null || pathPoints.size() < 2) {
            return; // Need at least 2 points to draw a line
        }
        
        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        
        // Set up OpenGL state for wireframe rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set view and projection matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }
        
        // Set identity model matrix
        Matrix4f modelMatrix = new Matrix4f().identity();
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        
        // Set shader uniforms for solid color rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(color.x, color.y, color.z, 1.0f));
        
        // Create vertices for the path lines
        int vertexCount = (pathPoints.size() - 1) * 2; // Each line segment needs 2 vertices
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertexCount * 3);
        
        for (int i = 0; i < pathPoints.size() - 1; i++) {
            Vector3f start = pathPoints.get(i);
            Vector3f end = pathPoints.get(i + 1);
            
            // Add start point
            vertexBuffer.put(start.x).put(start.y).put(start.z);
            // Add end point
            vertexBuffer.put(end.x).put(end.y).put(end.z);
        }
        vertexBuffer.flip();
        
        // Create temporary VAO and VBO for path rendering
        int vao = GL30.glGenVertexArrays();
        int vbo = GL30.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, vertexBuffer, GL30.GL_DYNAMIC_DRAW);
        
        // Set vertex attributes (position)
        GL30.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL30.glEnableVertexAttribArray(0);
        
        // Render the path lines
        glDrawArrays(GL_LINES, 0, vertexCount);
        
        // Cleanup
        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
        GL30.glDeleteBuffers(vbo);
        
        // Restore OpenGL state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
}