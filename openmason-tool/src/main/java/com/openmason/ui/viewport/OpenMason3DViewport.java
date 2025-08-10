package com.openmason.ui.viewport;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.openmason.model.StonebreakModel;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Clean 3D viewport implementation using pure LWJGL/ImGui.
 * Displays 3D models loaded from stonebreak-game module.
 */
public class OpenMason3DViewport {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenMason3DViewport.class);
    
    // OpenGL resources
    private int framebuffer = -1;
    private int colorTexture = -1;
    private int depthRenderbuffer = -1;
    
    // Shader resources
    private int shaderProgram = -1;
    private int vertexShader = -1;
    private int fragmentShader = -1;
    
    // Uniform locations
    private int mvpMatrixLocation = -1;
    private int colorLocation = -1;
    
    // Test cube data
    private int cubeVAO = -1;
    private int cubeVBO = -1;
    
    // Viewport state
    private int viewportWidth = 800;
    private int viewportHeight = 600;
    private boolean initialized = false;
    
    // Camera system
    private Camera camera;
    
    // Model and texture state
    private String currentModelName = null;
    private String currentTextureVariant = null;
    private StonebreakModel currentModel = null;
    
    // Grid rendering
    private int gridVAO = -1;
    private int gridVBO = -1;
    private boolean showGrid = true;
    
    // Viewport state
    private boolean wireframeMode = false;
    private boolean axesVisible = false;
    
    public OpenMason3DViewport() {
        this.camera = new Camera();
        logger.info("OpenMason 3D Viewport created");
    }
    
    /**
     * Initialize OpenGL resources.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            createShaders();
            createFramebuffer();
            createTestCube();
            createGrid();
            
            initialized = true;
            logger.info("OpenMason 3D Viewport initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize viewport", e);
            cleanup();
            throw new RuntimeException("Viewport initialization failed", e);
        }
    }
    
    /**
     * Create basic shaders for 3D rendering.
     */
    private void createShaders() {
        // Vertex shader source
        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            
            uniform mat4 uMVPMatrix;
            uniform vec3 uColor;
            
            out vec3 vertexColor;
            
            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                vertexColor = uColor;
            }
            """;
        
        // Fragment shader source
        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            out vec4 FragColor;
            
            void main() {
                FragColor = vec4(vertexColor, 1.0);
            }
            """;
        
        // Create and compile vertex shader
        vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexShaderSource);
        glCompileShader(vertexShader);
        checkShaderCompilation(vertexShader, "VERTEX");
        
        // Create and compile fragment shader
        fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        checkShaderCompilation(fragmentShader, "FRAGMENT");
        
        // Create and link shader program
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);
        checkProgramLinking(shaderProgram);
        
        // Get uniform locations
        mvpMatrixLocation = glGetUniformLocation(shaderProgram, "uMVPMatrix");
        colorLocation = glGetUniformLocation(shaderProgram, "uColor");
        
        logger.info("Shaders created successfully");
    }
    
    /**
     * Create framebuffer for off-screen rendering.
     */
    private void createFramebuffer() {
        // Generate framebuffer
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        
        // Create color texture
        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, viewportWidth, viewportHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        
        // Create depth renderbuffer
        depthRenderbuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRenderbuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT, viewportWidth, viewportHeight);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbuffer);
        
        // Check framebuffer completeness
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete");
        }
        
        // Unbind framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        
        logger.info("Framebuffer created: {}x{}", viewportWidth, viewportHeight);
    }
    
    /**
     * Create test cube geometry.
     */
    private void createTestCube() {
        // Cube vertices (positions only)
        float[] vertices = {
            // Front face
            -1.0f, -1.0f,  1.0f,
             1.0f, -1.0f,  1.0f,
             1.0f,  1.0f,  1.0f,
            -1.0f,  1.0f,  1.0f,
            
            // Back face
            -1.0f, -1.0f, -1.0f,
             1.0f, -1.0f, -1.0f,
             1.0f,  1.0f, -1.0f,
            -1.0f,  1.0f, -1.0f
        };
        
        // Create VAO and VBO
        cubeVAO = glGenVertexArrays();
        cubeVBO = glGenBuffers();
        
        glBindVertexArray(cubeVAO);
        
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        
        glBindBuffer(GL_ARRAY_BUFFER, cubeVBO);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        glBindVertexArray(0);
        
        logger.info("Test cube geometry created");
    }
    
    /**
     * Create grid geometry.
     */
    private void createGrid() {
        int gridSize = 20;
        float gridSpacing = 1.0f;
        
        // Calculate number of lines (horizontal + vertical)
        int lineCount = (gridSize + 1) * 2;
        float[] gridVertices = new float[lineCount * 2 * 3]; // 2 points per line, 3 coordinates per point
        
        int index = 0;
        float halfSize = gridSize * gridSpacing * 0.5f;
        
        // Horizontal lines
        for (int i = 0; i <= gridSize; i++) {
            float z = -halfSize + i * gridSpacing;
            
            // Start point
            gridVertices[index++] = -halfSize;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = z;
            
            // End point
            gridVertices[index++] = halfSize;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = z;
        }
        
        // Vertical lines
        for (int i = 0; i <= gridSize; i++) {
            float x = -halfSize + i * gridSpacing;
            
            // Start point
            gridVertices[index++] = x;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = -halfSize;
            
            // End point
            gridVertices[index++] = x;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = halfSize;
        }
        
        // Create VAO and VBO for grid
        gridVAO = glGenVertexArrays();
        gridVBO = glGenBuffers();
        
        glBindVertexArray(gridVAO);
        
        FloatBuffer gridBuffer = BufferUtils.createFloatBuffer(gridVertices.length);
        gridBuffer.put(gridVertices).flip();
        
        glBindBuffer(GL_ARRAY_BUFFER, gridVBO);
        glBufferData(GL_ARRAY_BUFFER, gridBuffer, GL_STATIC_DRAW);
        
        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        glBindVertexArray(0);
        
        logger.info("Grid geometry created");
    }
    
    /**
     * Resize viewport and recreate framebuffer if needed.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        
        if (width == viewportWidth && height == viewportHeight) {
            return;
        }
        
        viewportWidth = width;
        viewportHeight = height;
        
        if (initialized) {
            recreateFramebuffer();
        }
        
        camera.setAspectRatio((float) width / height);
        
        logger.debug("Viewport resized to {}x{}", width, height);
    }
    
    /**
     * Recreate framebuffer with new dimensions.
     */
    private void recreateFramebuffer() {
        // Delete old resources
        if (colorTexture != -1) {
            glDeleteTextures(colorTexture);
        }
        if (depthRenderbuffer != -1) {
            glDeleteRenderbuffers(depthRenderbuffer);
        }
        if (framebuffer != -1) {
            glDeleteFramebuffers(framebuffer);
        }
        
        // Create new framebuffer
        createFramebuffer();
    }
    
    /**
     * Render the 3D viewport content.
     */
    public void render() {
        if (!initialized) {
            initialize();
        }
        
        // Bind framebuffer for off-screen rendering
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, viewportWidth, viewportHeight);
        
        // Clear background
        glClearColor(0.2f, 0.2f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        // Enable depth testing
        glEnable(GL_DEPTH_TEST);
        
        // Use shader program
        glUseProgram(shaderProgram);
        
        // Calculate MVP matrix
        Matrix4f mvpMatrix = new Matrix4f();
        camera.getProjectionMatrix().mul(camera.getViewMatrix(), mvpMatrix);
        
        // Upload MVP matrix to shader
        FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
        mvpMatrix.get(matrixBuffer);
        glUniformMatrix4fv(mvpMatrixLocation, false, matrixBuffer);
        
        // Render grid
        if (showGrid) {
            glUniform3f(colorLocation, 0.5f, 0.5f, 0.5f); // Gray grid
            glBindVertexArray(gridVAO);
            glDrawArrays(GL_LINES, 0, 84); // 42 lines * 2 points
        }
        
        // Render test cube
        glUniform3f(colorLocation, 1.0f, 0.5f, 0.2f); // Orange cube
        glBindVertexArray(cubeVAO);
        
        // Draw wireframe cube
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glDrawArrays(GL_QUADS, 0, 8);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        
        // Unbind
        glBindVertexArray(0);
        glUseProgram(0);
        
        // Unbind framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    
    /**
     * Display the viewport in ImGui.
     */
    public void displayInImGui() {
        if (!initialized || colorTexture == -1) {
            ImGui.text("Viewport not initialized");
            return;
        }
        
        ImGui.begin("3D Viewport", ImGuiWindowFlags.None);
        
        // Get available content region
        ImVec2 contentRegion = ImGui.getContentRegionAvail();
        int newWidth = (int) contentRegion.x;
        int newHeight = (int) contentRegion.y;
        
        // Resize if needed
        if (newWidth > 0 && newHeight > 0) {
            resize(newWidth, newHeight);
        }
        
        // Render 3D content
        render();
        
        // Display the rendered texture
        ImGui.image(colorTexture, contentRegion.x, contentRegion.y, 0, 1, 1, 0);
        
        // Handle input
        if (ImGui.isItemHovered()) {
            handleInput();
        }
        
        ImGui.end();
        
        // Show controls window
        showControls();
    }
    
    /**
     * Handle input for camera controls.
     */
    private void handleInput() {
        ImVec2 mouseDelta = ImGui.getIO().getMouseDelta();
        
        if (ImGui.isMouseDown(0)) { // Left mouse button
            // Rotate camera
            camera.rotate(-mouseDelta.x * 0.01f, -mouseDelta.y * 0.01f);
        }
        
        // Handle mouse wheel for zooming
        float wheel = ImGui.getIO().getMouseWheel();
        if (wheel != 0) {
            camera.zoom(wheel * 0.5f);
        }
    }
    
    /**
     * Show viewport controls.
     */
    private void showControls() {
        ImGui.begin("Viewport Controls");
        
        if (ImGui.checkbox("Show Grid", showGrid)) {
            // Grid visibility changed
        }
        
        if (ImGui.button("Reset Camera")) {
            camera.reset();
        }
        
        ImGui.separator();
        ImGui.text("Controls:");
        ImGui.text("Left Mouse: Rotate camera");
        ImGui.text("Mouse Wheel: Zoom");
        
        ImGui.end();
    }
    
    /**
     * Check shader compilation errors.
     */
    private void checkShaderCompilation(int shader, String type) {
        int success = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetShaderInfoLog(shader);
            throw new RuntimeException("Shader compilation failed (" + type + "): " + infoLog);
        }
    }
    
    /**
     * Check program linking errors.
     */
    private void checkProgramLinking(int program) {
        int success = glGetProgrami(program, GL_LINK_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetProgramInfoLog(program);
            throw new RuntimeException("Program linking failed: " + infoLog);
        }
    }
    
    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        logger.info("Cleaning up viewport resources");
        
        if (cubeVBO != -1) {
            glDeleteBuffers(cubeVBO);
            cubeVBO = -1;
        }
        
        if (cubeVAO != -1) {
            glDeleteVertexArrays(cubeVAO);
            cubeVAO = -1;
        }
        
        if (gridVBO != -1) {
            glDeleteBuffers(gridVBO);
            gridVBO = -1;
        }
        
        if (gridVAO != -1) {
            glDeleteVertexArrays(gridVAO);
            gridVAO = -1;
        }
        
        if (colorTexture != -1) {
            glDeleteTextures(colorTexture);
            colorTexture = -1;
        }
        
        if (depthRenderbuffer != -1) {
            glDeleteRenderbuffers(depthRenderbuffer);
            depthRenderbuffer = -1;
        }
        
        if (framebuffer != -1) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = -1;
        }
        
        if (shaderProgram != -1) {
            glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }
        
        if (vertexShader != -1) {
            glDeleteShader(vertexShader);
            vertexShader = -1;
        }
        
        if (fragmentShader != -1) {
            glDeleteShader(fragmentShader);
            fragmentShader = -1;
        }
        
        initialized = false;
        logger.info("Viewport resources cleaned up");
    }
    
    // Getters
    public Camera getCamera() { return camera; }
    public boolean isShowGrid() { return showGrid; }
    public void setShowGrid(boolean showGrid) { this.showGrid = showGrid; }
    public int getColorTexture() { return colorTexture; }
    public boolean isInitialized() { return initialized; }
    
    // Legacy compatibility methods
    public void resetCamera() {
        if (camera != null) {
            camera.reset();
        }
    }
    
    public void setGridVisible(boolean visible) {
        setShowGrid(visible);
    }
    
    public void setAxesVisible(boolean visible) {
        this.axesVisible = visible;
        // TODO: Implement axes visibility rendering
        logger.debug("Axes visibility set to: {}", visible);
    }
    
    public void setWireframeMode(boolean wireframe) {
        this.wireframeMode = wireframe;
        // TODO: Implement wireframe mode rendering
        logger.debug("Wireframe mode set to: {}", wireframe);
    }
    
    public float getCurrentFPS() {
        // TODO: Implement FPS calculation
        return 60.0f; // Placeholder
    }
    
    public void dispose() {
        cleanup();
    }
    
    public void requestRender() {
        // Rendering happens automatically when displayInImGui() is called
        logger.debug("Render requested (handled automatically by ImGui integration)");
    }
    
    public void setModelTransform(float rotX, float rotY, float rotZ, float scale) {
        // TODO: Implement model transformation
        logger.debug("Model transform set: rotation=({},{},{}), scale={}", rotX, rotY, rotZ, scale);
    }
    
    /**
     * Render the viewport content to a framebuffer with specified dimensions.
     * Used for high-resolution screenshots.
     */
    public void renderToFramebuffer(int width, int height) {
        if (!initialized) {
            initialize();
        }
        
        // Store original dimensions
        int originalWidth = viewportWidth;
        int originalHeight = viewportHeight;
        
        try {
            // Temporarily resize for high-res rendering
            resize(width, height);
            
            // Perform the render
            render();
            
            logger.debug("Rendered to framebuffer at {}x{}", width, height);
            
        } finally {
            // Restore original dimensions
            resize(originalWidth, originalHeight);
        }
    }
    
    /**
     * Get the current model name being displayed.
     */
    public String getCurrentModelName() {
        return currentModelName;
    }
    
    /**
     * Set the current model name.
     */
    public void setCurrentModelName(String modelName) {
        this.currentModelName = modelName;
        logger.debug("Current model name set to: {}", modelName);
    }
    
    /**
     * Get the current StonebreakModel being displayed.
     */
    public StonebreakModel getCurrentModel() {
        return currentModel;
    }
    
    /**
     * Set the current StonebreakModel for display in the viewport.
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        // Update model name if model is provided
        if (model != null) {
            // Extract model name from the model if available
            // For now, use a generic name - in full implementation this would
            // extract the actual model name from the StonebreakModel
            setCurrentModelName("stonebreak_model");
            logger.info("Current StonebreakModel set: {}", model.getClass().getSimpleName());
        } else {
            setCurrentModelName(null);
            logger.info("Current StonebreakModel cleared");
        }
        
        // TODO: Implement actual model rendering using StonebreakModel data
        // This should:
        // 1. Extract vertex data from model.getModelDefinition()
        // 2. Create OpenGL buffers for the geometry
        // 3. Set up textures from model.getTextureDefinition()
        // 4. Update rendering pipeline to use new model data
    }
    
    /**
     * Get the current texture variant being used.
     */
    public String getCurrentTextureVariant() {
        return currentTextureVariant;
    }
    
    /**
     * Set the current texture variant.
     */
    public void setCurrentTextureVariant(String variant) {
        this.currentTextureVariant = variant;
        // TODO: Implement texture variant switching
        logger.debug("Current texture variant set to: {}", variant);
    }
    
    /**
     * Check if wireframe mode is enabled.
     */
    public boolean isWireframeMode() {
        return wireframeMode;
    }
    
    /**
     * Check if grid is visible.
     */
    public boolean isGridVisible() {
        return showGrid;
    }
    
    /**
     * Check if axes are visible.
     */
    public boolean isAxesVisible() {
        return axesVisible;
    }
    
    /**
     * Load a model by name for display in the viewport.
     */
    public void loadModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.warn("Cannot load model: name is null or empty");
            return;
        }
        
        // Set the current model name
        setCurrentModelName(modelName);
        
        // TODO: Implement actual model loading using Stonebreak model system
        // This should integrate with ModelManager to load the actual model data
        logger.info("Loading model: {} (placeholder implementation)", modelName);
        
        // For now, just log that we would load the model
        // In a full implementation, this would:
        // 1. Use ModelManager to load the model
        // 2. Create appropriate OpenGL buffers for the model geometry
        // 3. Set up textures if needed
        // 4. Update the viewport to render the new model instead of test cube
    }
}