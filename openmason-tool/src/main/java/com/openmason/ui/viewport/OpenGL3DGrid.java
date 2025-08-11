package com.openmason.ui.viewport;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenGL-based 3D grid rendering system for the viewport.
 * 
 * This class provides efficient 3D grid rendering using OpenGL vertex buffers
 * and modern rendering techniques. Supports:
 * - 3D grid lines with proper depth perception
 * - Color-coded coordinate axes
 * - Performance-optimized line rendering
 * - Configurable grid density and appearance
 */
public class OpenGL3DGrid {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenGL3DGrid.class);
    
    // OpenGL resources
    private int gridVAO = 0;
    private int gridVBO = 0;
    private int gridShaderProgram = 0;
    
    // Grid data
    private final List<GridLine> gridLines = new ArrayList<>();
    private final List<GridPoint> gridPoints = new ArrayList<>();
    
    // Shader source code
    private static final String GRID_VERTEX_SHADER = """
        #version 330 core
        
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec3 aColor;
        
        uniform mat4 mvpMatrix;
        
        out vec3 vertexColor;
        
        void main() {
            gl_Position = mvpMatrix * vec4(aPos, 1.0);
            vertexColor = aColor;
        }
        """;
    
    private static final String GRID_FRAGMENT_SHADER = """
        #version 330 core
        
        in vec3 vertexColor;
        out vec4 fragColor;
        
        void main() {
            fragColor = vec4(vertexColor, 1.0);
        }
        """;
    
    // Grid data structures
    private static class GridLine {
        final Vector3f start, end;
        final Vector3f color;
        final float thickness;
        
        GridLine(float x1, float y1, float z1, float x2, float y2, float z2, Vector3f color, float thickness) {
            this.start = new Vector3f(x1, y1, z1);
            this.end = new Vector3f(x2, y2, z2);
            this.color = color;
            this.thickness = thickness;
        }
    }
    
    private static class GridPoint {
        final Vector3f position;
        final Vector3f color;
        final float size;
        
        GridPoint(float x, float y, float z, Vector3f color, float size) {
            this.position = new Vector3f(x, y, z);
            this.color = color;
            this.size = size;
        }
    }
    
    /**
     * Initialize the OpenGL 3D grid system.
     */
    public OpenGL3DGrid() {
        logger.debug("Initializing OpenGL 3D grid system");
    }
    
    /**
     * Add a line to the grid.
     */
    public void addLine(float x1, float y1, float z1, float x2, float y2, float z2, Vector3f color, float thickness) {
        gridLines.add(new GridLine(x1, y1, z1, x2, y2, z2, color, thickness));
    }
    
    /**
     * Add a point to the grid.
     */
    public void addPoint(float x, float y, float z, Vector3f color, float size) {
        gridPoints.add(new GridPoint(x, y, z, color, size));
    }
    
    /**
     * Add a line to the grid with RGB color values.
     */
    public void addLine(float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float thickness) {
        addLine(x1, y1, z1, x2, y2, z2, new Vector3f(r, g, b), thickness);
    }
    
    /**
     * Add a point to the grid with RGB color values.
     */
    public void addPoint(float x, float y, float z, float r, float g, float b, float size) {
        addPoint(x, y, z, new Vector3f(r, g, b), size);
    }
    
    /**
     * Create a standard 3D grid with the specified parameters.
     */
    public void createGrid(int gridSize, float gridSpacing, Vector3f gridColor, float lineThickness) {
        clear();
        
        float halfSize = gridSize * gridSpacing * 0.5f;
        
        // Horizontal lines (along X-axis)
        for (int i = 0; i <= gridSize; i++) {
            float z = -halfSize + i * gridSpacing;
            addLine(-halfSize, 0.0f, z, halfSize, 0.0f, z, gridColor, lineThickness);
        }
        
        // Vertical lines (along Z-axis)  
        for (int i = 0; i <= gridSize; i++) {
            float x = -halfSize + i * gridSpacing;
            addLine(x, 0.0f, -halfSize, x, 0.0f, halfSize, gridColor, lineThickness);
        }
        
        logger.debug("Created {}x{} grid with {} total lines", gridSize, gridSize, gridLines.size());
    }
    
    /**
     * Create coordinate axes (X=red, Y=green, Z=blue).
     */
    public void createAxes(float axisLength, float axisThickness) {
        // X-axis (red)
        addLine(0, 0, 0, axisLength, 0, 0, new Vector3f(1.0f, 0.0f, 0.0f), axisThickness);
        
        // Y-axis (green) 
        addLine(0, 0, 0, 0, axisLength, 0, new Vector3f(0.0f, 1.0f, 0.0f), axisThickness);
        
        // Z-axis (blue)
        addLine(0, 0, 0, 0, 0, axisLength, new Vector3f(0.0f, 0.0f, 1.0f), axisThickness);
        
        logger.debug("Created coordinate axes with length {}", axisLength);
    }
    
    /**
     * Clear all grid lines and points.
     */
    public void clear() {
        gridLines.clear();
        gridPoints.clear();
    }
    
    /**
     * Initialize OpenGL resources (called when OpenGL context is available).
     */
    public void initializeOpenGL() {
        try {
            // Create and compile shaders
            gridShaderProgram = createShaderProgram();
            
            // Create vertex array and buffer objects
            gridVAO = GL30.glGenVertexArrays();
            gridVBO = GL15.glGenBuffers();
            
            // Set up vertex buffer
            setupVertexBuffer();
            
            logger.info("OpenGL 3D grid initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize OpenGL 3D grid", e);
        }
    }
    
    /**
     * Render the 3D grid using OpenGL.
     */
    public void render(Matrix4f mvpMatrix) {
        if (gridShaderProgram == 0 || gridVAO == 0) {
            return; // Not initialized
        }
        
        try {
            // Enable shader program
            GL20.glUseProgram(gridShaderProgram);
            
            // Set MVP matrix uniform
            int mvpLocation = GL20.glGetUniformLocation(gridShaderProgram, "mvpMatrix");
            if (mvpLocation >= 0) {
                FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
                mvpMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(mvpLocation, false, matrixBuffer);
            }
            
            // Bind vertex array and render
            GL30.glBindVertexArray(gridVAO);
            GL11.glDrawArrays(GL11.GL_LINES, 0, gridLines.size() * 2);
            GL30.glBindVertexArray(0);
            
            // Render points
            GL11.glPointSize(3.0f);
            GL11.glDrawArrays(GL11.GL_POINTS, gridLines.size() * 2, gridPoints.size());
            
            GL20.glUseProgram(0);
            
        } catch (Exception e) {
            logger.error("Failed to render OpenGL 3D grid", e);
        }
    }
    
    /**
     * Create and compile the shader program.
     */
    private int createShaderProgram() {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, GRID_VERTEX_SHADER);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, GRID_FRAGMENT_SHADER);
        
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);
        
        // Check linking
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            logger.error("Shader program linking failed: {}", log);
            throw new RuntimeException("Shader program linking failed: " + log);
        }
        
        // Clean up shaders
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        
        return program;
    }
    
    /**
     * Compile a shader.
     */
    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            logger.error("Shader compilation failed: {}", log);
            throw new RuntimeException("Shader compilation failed: " + log);
        }
        
        return shader;
    }
    
    /**
     * Set up vertex buffer with grid data.
     */
    private void setupVertexBuffer() {
        // Calculate total vertices (lines = 2 vertices each, points = 1 vertex each)
        int totalVertices = gridLines.size() * 2 + gridPoints.size();
        FloatBuffer vertexData = BufferUtils.createFloatBuffer(totalVertices * 6); // 3 pos + 3 color
        
        // Add line vertices
        for (GridLine line : gridLines) {
            // Start vertex
            vertexData.put(line.start.x).put(line.start.y).put(line.start.z);
            vertexData.put(line.color.x).put(line.color.y).put(line.color.z);
            
            // End vertex
            vertexData.put(line.end.x).put(line.end.y).put(line.end.z);
            vertexData.put(line.color.x).put(line.color.y).put(line.color.z);
        }
        
        // Add point vertices
        for (GridPoint point : gridPoints) {
            vertexData.put(point.position.x).put(point.position.y).put(point.position.z);
            vertexData.put(point.color.x).put(point.color.y).put(point.color.z);
        }
        
        vertexData.flip();
        
        // Bind and upload data
        GL30.glBindVertexArray(gridVAO);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gridVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexData, GL15.GL_STATIC_DRAW);
        
        // Set up vertex attributes
        // Position attribute
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Color attribute
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Clean up OpenGL resources.
     */
    public void dispose() {
        if (gridVAO != 0) {
            GL30.glDeleteVertexArrays(gridVAO);
            gridVAO = 0;
        }
        
        if (gridVBO != 0) {
            GL15.glDeleteBuffers(gridVBO);
            gridVBO = 0;
        }
        
        if (gridShaderProgram != 0) {
            GL20.glDeleteProgram(gridShaderProgram);
            gridShaderProgram = 0;
        }
        
        gridLines.clear();
        gridPoints.clear();
        
        logger.debug("OpenGL 3D grid disposed");
    }
    
    /**
     * Get the number of grid lines.
     */
    public int getLineCount() {
        return gridLines.size();
    }
    
    /**
     * Get the number of grid points.
     */
    public int getPointCount() {
        return gridPoints.size();
    }
}