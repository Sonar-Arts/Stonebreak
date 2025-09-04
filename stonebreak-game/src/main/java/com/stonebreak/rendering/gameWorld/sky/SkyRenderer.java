package com.stonebreak.rendering.gameWorld.sky;

// Standard Library Imports
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// JOML Math Library
import org.joml.Matrix4f;
import org.joml.Vector3f;

// LWJGL OpenGL Static Imports
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

// Project Imports
import com.stonebreak.rendering.shaders.ShaderProgram;

/**
 * Renderer for procedural sky with sun and clouds.
 * Renders a full-screen sky dome with atmospheric effects.
 */
public class SkyRenderer {
    
    // Sky dome geometry
    private int skyVAO;
    private int skyVBO;
    private int skyEBO;
    
    // Shader program for sky rendering
    private ShaderProgram skyShaderProgram;
    
    // Sun position (normalized direction vector) - positioned at horizon
    private final Vector3f sunDirection = new Vector3f(0.7f, 0.1f, 0.5f).normalize();
    
    // Sky dome vertices (cube that will be expanded in vertex shader)
    private static final float[] SKY_VERTICES = {
        // Positions for a simple cube (will be expanded to sky dome in shader)
        -1.0f, -1.0f, -1.0f,
         1.0f, -1.0f, -1.0f,
         1.0f,  1.0f, -1.0f,
        -1.0f,  1.0f, -1.0f,
        -1.0f, -1.0f,  1.0f,
         1.0f, -1.0f,  1.0f,
         1.0f,  1.0f,  1.0f,
        -1.0f,  1.0f,  1.0f
    };
    
    private static final int[] SKY_INDICES = {
        // Front face
        0, 1, 2, 2, 3, 0,
        // Back face
        4, 5, 6, 6, 7, 4,
        // Left face
        7, 3, 0, 0, 4, 7,
        // Right face
        1, 5, 6, 6, 2, 1,
        // Top face
        3, 2, 6, 6, 7, 3,
        // Bottom face
        0, 1, 5, 5, 4, 0
    };
    
    /**
     * Creates a new sky renderer and initializes OpenGL resources.
     */
    public SkyRenderer() {
        initializeOpenGLResources();
        initializeShaders();
    }
    
    /**
     * Initialize OpenGL buffers and vertex arrays for sky dome.
     */
    private void initializeOpenGLResources() {
        // Generate VAO
        skyVAO = glGenVertexArrays();
        glBindVertexArray(skyVAO);
        
        // Generate and bind VBO
        skyVBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, skyVBO);
        glBufferData(GL_ARRAY_BUFFER, SKY_VERTICES, GL_STATIC_DRAW);
        
        // Generate and bind EBO
        skyEBO = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, skyEBO);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, SKY_INDICES, GL_STATIC_DRAW);
        
        // Configure vertex attributes (position only)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        // Unbind VAO
        glBindVertexArray(0);
    }
    
    /**
     * Initialize sky shaders using the ShaderProgram class.
     */
    private void initializeShaders() {
        skyShaderProgram = new ShaderProgram();
        
        try {
            // Load vertex shader
            String vertexShaderSource = loadShaderSource("/shaders/sky/sky.vert");
            skyShaderProgram.createVertexShader(vertexShaderSource);
            
            // Load fragment shader
            String fragmentShaderSource = loadShaderSource("/shaders/sky/sky.frag");
            skyShaderProgram.createFragmentShader(fragmentShaderSource);
            
            // Link shader program
            skyShaderProgram.link();
            
            // Create uniforms
            skyShaderProgram.createUniform("projectionMatrix");
            skyShaderProgram.createUniform("viewMatrix");
            skyShaderProgram.createUniform("cameraPosition");
            skyShaderProgram.createUniform("time");
            skyShaderProgram.createUniform("sunDirection");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize sky shaders", e);
        }
    }
    
    /**
     * Load shader source code from resources.
     */
    private String loadShaderSource(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Render the sky dome.
     * Should be called first in the rendering pipeline, before world geometry.
     */
    public void renderSky(Matrix4f projectionMatrix, Matrix4f viewMatrix, Vector3f cameraPosition, float totalTime) {
        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendEnabled = glIsEnabled(GL_BLEND);
        boolean depthMaskEnabled = glGetBoolean(GL_DEPTH_WRITEMASK);
        int currentDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        int currentBlendSrc = glGetInteger(GL_BLEND_SRC);
        int currentBlendDst = glGetInteger(GL_BLEND_DST);
        
        // Configure OpenGL state for sky rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL); // Sky should be rendered at maximum depth
        glDisable(GL_CULL_FACE); // Render both sides of sky dome
        glDisable(GL_BLEND); // Sky doesn't need blending
        glDepthMask(true); // Enable depth writing for sky
        
        // Use sky shader
        skyShaderProgram.bind();
        
        // Set uniforms
        skyShaderProgram.setUniform("projectionMatrix", projectionMatrix);
        skyShaderProgram.setUniform("viewMatrix", viewMatrix);
        skyShaderProgram.setUniform("cameraPosition", cameraPosition);
        skyShaderProgram.setUniform("time", totalTime);
        skyShaderProgram.setUniform("sunDirection", sunDirection);
        
        // Bind sky VAO and render
        glBindVertexArray(skyVAO);
        glDrawElements(GL_TRIANGLES, SKY_INDICES.length, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
        
        // Unbind shader
        skyShaderProgram.unbind();
        
        // Restore previous OpenGL state
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
        
        if (blendEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(currentBlendSrc, currentBlendDst);
        } else {
            glDisable(GL_BLEND);
        }
        
        glDepthMask(depthMaskEnabled);
        glDepthFunc(currentDepthFunc);
    }
    
    /**
     * Get the current sun direction.
     */
    public Vector3f getSunDirection() {
        return new Vector3f(sunDirection);
    }
    
    /**
     * Set the sun direction (should be normalized).
     */
    public void setSunDirection(Vector3f direction) {
        this.sunDirection.set(direction.normalize());
    }
    
    /**
     * Set the sun direction using elevation and azimuth angles.
     * 
     * @param elevation Elevation angle in degrees (0 = horizon, 90 = zenith)
     * @param azimuth Azimuth angle in degrees (0 = north, 90 = east)
     */
    public void setSunDirection(float elevation, float azimuth) {
        float elevRad = (float) Math.toRadians(elevation);
        float azimRad = (float) Math.toRadians(azimuth);
        
        float y = (float) Math.sin(elevRad);
        float horizontalDistance = (float) Math.cos(elevRad);
        float x = horizontalDistance * (float) Math.sin(azimRad);
        float z = horizontalDistance * (float) Math.cos(azimRad);
        
        sunDirection.set(x, y, z).normalize();
    }
    
    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (skyShaderProgram != null) {
            skyShaderProgram.cleanup();
        }
        
        if (skyVAO != 0) {
            glDeleteVertexArrays(skyVAO);
        }
        if (skyVBO != 0) {
            glDeleteBuffers(skyVBO);
        }
        if (skyEBO != 0) {
            glDeleteBuffers(skyEBO);
        }
    }
}