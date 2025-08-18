package com.openmason.ui.viewport;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
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

// Model rendering imports
import com.openmason.model.ModelManager;
import com.openmason.model.StonebreakModel;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.TextureAtlas;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Clean and robust 3D viewport implementation using pure LWJGL/ImGui.
 * Displays 3D models loaded from stonebreak-game module.
 * 
 * This class is responsible only for rendering and viewport management.
 * All input handling is delegated to ViewportInputHandler for better architecture.
 * 
 * Features:
 * - Safe OpenGL resource management
 * - Robust error handling and validation
 * - Clean separation of rendering and input concerns
 * - Professional viewport rendering pipeline
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
    private int modelMatrixLocation = -1;
    private int colorLocation = -1;
    
    // Matrix transformation shader program (for individual part transformations)
    private int matrixShaderProgram = -1;
    private int matrixVertexShader = -1;
    private int matrixFragmentShader = -1;
    private int matrixMvpLocation = -1;
    private int matrixModelLocation = -1;
    private int matrixColorLocation = -1;
    private int matrixTextureLocation = -1;
    private int matrixUseTextureLocation = -1;
    
    // Test cube data
    private int cubeVAO = -1;
    private int cubeVBO = -1;
    
    // Viewport state
    private int viewportWidth = 800;
    private int viewportHeight = 600;
    private boolean initialized = false;
    
    // Camera system
    private Camera camera;
    
    // Input handling delegation
    private ViewportInputHandler inputHandler;
    
    // Model rendering system
    private ModelRenderer modelRenderer;
    private volatile String currentModelName = null;
    private volatile String currentTextureVariant = "default";
    private volatile StonebreakModel currentModel = null;
    private boolean modelRenderingEnabled = true;
    private volatile CompletableFuture<Void> currentModelLoadingFuture = null;
    
    // Texture system
    private TextureAtlas textureAtlas;
    
    // Diagnostic throttling
    private long lastDiagnosticLogTime = 0;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 2000; // Log every 2 seconds
    
    // Grid rendering
    private int gridVAO = -1;
    private int gridVBO = -1;
    private boolean showGrid = true;
    
    // Viewport state
    private boolean wireframeMode = false;
    private boolean axesVisible = false;
    
    public OpenMason3DViewport() {
        this.camera = new Camera();
        this.inputHandler = new ViewportInputHandler(camera);
        this.modelRenderer = new ModelRenderer("Viewport");
        // logger.info("OpenMason 3D Viewport created with input handler and model renderer");
    }
    
    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This should be called from the main application.
     */
    public void setWindowHandle(long windowHandle) {
        if (inputHandler != null) {
            inputHandler.setWindowHandle(windowHandle);
            // logger.info("Window handle set on input handler");
        } else {
            logger.warn("Cannot set window handle - input handler is null");
        }
    }
    
    
    /**
     * Initialize OpenGL resources with robust error handling.
     */
    public void initialize() {
        if (initialized) {
            // logger.debug("Viewport already initialized");
            return;
        }
        
        try {
            // logger.info("Initializing OpenMason 3D Viewport...");
            
            // Validate OpenGL context
            validateOpenGLContext();
            
            // Initialize resources in order
            createShaders();
            createMatrixTransformShaders();
            createFramebuffer();
            createTestCube();
            createGrid();
            
            // Initialize model renderer
            modelRenderer.initialize();
            
            // Enable matrix transformation mode for proper Stonebreak coordinate system support
            modelRenderer.setMatrixTransformationMode(true);
            
            // Initialize texture atlas
            textureAtlas = new TextureAtlas("Viewport_CowAtlas");
            textureAtlas.initialize();
            
            // Validate all resources were created
            validateResources();
            
            initialized = true;
            // logger.info("OpenMason 3D Viewport initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize viewport", e);
            cleanup();
            throw new RuntimeException("Viewport initialization failed", e);
        }
    }
    
    /**
     * Validate that we have a valid OpenGL context.
     */
    private void validateOpenGLContext() {
        try {
            // Try a simple OpenGL call to validate context
            int maxTextureSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
            // logger.debug("OpenGL context validated - Max texture size: {}", maxTextureSize);
        } catch (Exception e) {
            throw new RuntimeException("Invalid OpenGL context", e);
        }
    }
    
    /**
     * Validate that all required resources were created successfully.
     */
    private void validateResources() {
        if (shaderProgram == -1) {
            throw new RuntimeException("Shader program creation failed");
        }
        if (framebuffer == -1) {
            throw new RuntimeException("Framebuffer creation failed");
        }
        if (colorTexture == -1) {
            throw new RuntimeException("Color texture creation failed");
        }
        if (cubeVAO == -1 || cubeVBO == -1) {
            throw new RuntimeException("Cube geometry creation failed");
        }
        if (gridVAO == -1 || gridVBO == -1) {
            throw new RuntimeException("Grid geometry creation failed");
        }
        
        // logger.debug("All OpenGL resources validated successfully");
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
        modelMatrixLocation = glGetUniformLocation(shaderProgram, "uModelMatrix");
        colorLocation = glGetUniformLocation(shaderProgram, "uColor");
        
        // logger.info("Shaders created successfully");
    }
    
    /**
     * Create advanced shaders that support per-part transformation matrices.
     * This enables individual model parts to be positioned using transformation matrices
     * instead of baked vertex coordinates.
     */
    private void createMatrixTransformShaders() {
        // Advanced vertex shader with model matrix and texture support
        String matrixVertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            
            uniform mat4 uMVPMatrix;     // View-Projection matrix (camera)
            uniform mat4 uModelMatrix;   // Model transformation matrix (per-part positioning)
            uniform vec3 uColor;
            uniform bool uUseTexture;    // Whether to use texture or solid color
            
            out vec3 vertexColor;
            out vec2 TexCoord;
            
            void main() {
                // Apply model transformation first, then MVP
                gl_Position = uMVPMatrix * uModelMatrix * vec4(aPos, 1.0);
                vertexColor = uColor;
                TexCoord = aTexCoord;
            }
            """;
        
        // Fragment shader with texture sampling support
        String matrixFragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            in vec2 TexCoord;
            out vec4 FragColor;
            
            uniform sampler2D uTexture;
            uniform bool uUseTexture;
            
            void main() {
                if (uUseTexture) {
                    // Use texture color directly for proper cow texture display
                    FragColor = texture(uTexture, TexCoord);
                } else {
                    // Use solid color only
                    FragColor = vec4(vertexColor, 1.0);
                }
            }
            """;
        
        // Create and compile matrix vertex shader
        matrixVertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(matrixVertexShader, matrixVertexShaderSource);
        glCompileShader(matrixVertexShader);
        checkShaderCompilation(matrixVertexShader, "MATRIX_VERTEX");
        
        // Create and compile matrix fragment shader
        matrixFragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(matrixFragmentShader, matrixFragmentShaderSource);
        glCompileShader(matrixFragmentShader);
        checkShaderCompilation(matrixFragmentShader, "MATRIX_FRAGMENT");
        
        // Create and link matrix shader program
        matrixShaderProgram = glCreateProgram();
        glAttachShader(matrixShaderProgram, matrixVertexShader);
        glAttachShader(matrixShaderProgram, matrixFragmentShader);
        glLinkProgram(matrixShaderProgram);
        checkProgramLinking(matrixShaderProgram);
        
        // Get matrix shader uniform locations
        matrixMvpLocation = glGetUniformLocation(matrixShaderProgram, "uMVPMatrix");
        matrixModelLocation = glGetUniformLocation(matrixShaderProgram, "uModelMatrix");
        matrixColorLocation = glGetUniformLocation(matrixShaderProgram, "uColor");
        matrixTextureLocation = glGetUniformLocation(matrixShaderProgram, "uTexture");
        matrixUseTextureLocation = glGetUniformLocation(matrixShaderProgram, "uUseTexture");
        
        // logger.info("Matrix transformation shaders created successfully");
    }
    
    /**
     * Create framebuffer for off-screen rendering with error checking.
     */
    private void createFramebuffer() {
        try {
            // Generate framebuffer
            framebuffer = glGenFramebuffers();
            if (framebuffer == 0) {
                throw new RuntimeException("Failed to generate framebuffer");
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            
            // Create color texture
            colorTexture = glGenTextures();
            if (colorTexture == 0) {
                throw new RuntimeException("Failed to generate color texture");
            }
            glBindTexture(GL_TEXTURE_2D, colorTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, viewportWidth, viewportHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
            
            // Create depth renderbuffer
            depthRenderbuffer = glGenRenderbuffers();
            if (depthRenderbuffer == 0) {
                throw new RuntimeException("Failed to generate depth renderbuffer");
            }
            glBindRenderbuffer(GL_RENDERBUFFER, depthRenderbuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT, viewportWidth, viewportHeight);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbuffer);
            
            // Check framebuffer completeness
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                String errorMessage = getFramebufferErrorMessage(status);
                throw new RuntimeException("Framebuffer not complete: " + errorMessage);
            }
            
            // Unbind framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            
            // logger.info("Framebuffer created successfully: {}x{}", viewportWidth, viewportHeight);
            
        } catch (Exception e) {
            logger.error("Failed to create framebuffer", e);
            throw new RuntimeException("Framebuffer creation failed", e);
        }
    }
    
    /**
     * Get human-readable framebuffer error message.
     */
    private String getFramebufferErrorMessage(int status) {
        return switch (status) {
            case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "Incomplete attachment";
            case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "Missing attachment";
            case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "Incomplete draw buffer";
            case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "Incomplete read buffer";
            case GL_FRAMEBUFFER_UNSUPPORTED -> "Unsupported framebuffer format";
            default -> "Unknown error (status: " + status + ")";
        };
    }
    
    /**
     * Create test cube geometry.
     */
    private void createTestCube() {
        // Cube vertices as triangles (36 vertices = 12 triangles = 6 faces)
        float[] vertices = {
            // Front face (z = 0.5)
            -0.5f, -0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            
            // Back face (z = -0.5)
            -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f, -0.5f, -0.5f,
            
            // Left face (x = -0.5)
            -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
            
            // Right face (x = 0.5)
             0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
             0.5f, -0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,
            
            // Top face (y = 0.5)
            -0.5f,  0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,
            
            // Bottom face (y = -0.5)
            -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,
            -0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f
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
        
        // logger.info("Test cube geometry created (36 vertices, 12 triangles)");
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
        
        // logger.info("Grid geometry created");
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
        
        // logger.debug("Viewport resized to {}x{}", width, height);
    }
    
    /**
     * Recreate framebuffer with new dimensions with proper error handling.
     */
    private void recreateFramebuffer() {
        // logger.debug("Recreating framebuffer with dimensions: {}x{}", viewportWidth, viewportHeight);
        
        try {
            // Delete old resources safely
            deleteFramebufferResources();
            
            // Create new framebuffer
            createFramebuffer();
            
            // logger.debug("Framebuffer recreated successfully");
        } catch (Exception e) {
            logger.error("Failed to recreate framebuffer", e);
            throw new RuntimeException("Framebuffer recreation failed", e);
        }
    }
    
    /**
     * Safely delete framebuffer resources.
     */
    private void deleteFramebufferResources() {
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
    }
    
    /**
     * Render the 3D viewport content.
     */
    public void render() {
        // Debug logging reduced to avoid spam - uncomment if needed
        // logger.debug("Render called - initialized: {}, framebuffer: {}, viewport size: {}x{}", 
        //             initialized, framebuffer, viewportWidth, viewportHeight);
        
        if (!initialized) {
            // logger.info("Viewport not initialized, initializing now...");
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
        
        // Update camera animation (smooth interpolation) - ensure matrices are updated
        camera.update(0.016f); // Assuming ~60fps (16ms frame time)
        camera.updateMatrices(); // Force matrix update for immediate response
        
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
        
        // DIAGNOSTIC: Runtime model state logging (throttled)
        long currentTime = System.currentTimeMillis();
        boolean shouldLog = (currentTime - lastDiagnosticLogTime) >= DIAGNOSTIC_LOG_INTERVAL_MS;
        
        if (shouldLog) {
            lastDiagnosticLogTime = currentTime;
            // logger.info("RENDER DIAGNOSTIC (viewport instance: {}) - currentModel: {}, currentModelName: {}, modelRenderingEnabled: {}", 
            //            System.identityHashCode(this), (currentModel != null), currentModelName, modelRenderingEnabled);
            
            if (currentModel != null) {
                // logger.info("RENDER DIAGNOSTIC - model variant: {}, body parts: {}", 
                //            currentModel.getVariantName(), 
                //            (currentModel.getBodyParts() != null ? currentModel.getBodyParts().size() : "null"));
                
                // Check model preparation status
                boolean isPrepared = modelRenderer.isModelPrepared(currentModel);
                // logger.info("RENDER DIAGNOSTIC - model prepared: {}", isPrepared);
                
                if (!isPrepared) {
                    // Get detailed preparation status
                    var status = modelRenderer.getModelPreparationStatus(currentModel);
                    // logger.info("RENDER DIAGNOSTIC - preparation status: {}", status.toString());
                }
            }
        }
        
        // Prepare loaded model for rendering if needed (must be done on main thread with OpenGL context)
        if (currentModel != null && !modelRenderer.isModelPrepared(currentModel)) {
            try {
                // logger.info("Preparing model for rendering: {}", currentModelName);
                boolean prepared = modelRenderer.prepareModel(currentModel);
                // logger.info("RENDER DIAGNOSTIC - model preparation result: {}", prepared);
                if (!prepared) {
                    logger.error("Failed to prepare model for rendering: {}", currentModelName);
                    // Get detailed diagnostic info
                    modelRenderer.logDiagnosticInfo();
                }
            } catch (Exception e) {
                logger.error("Exception preparing model: " + e.getMessage(), e);
                e.printStackTrace();
            }
        }
        
        // DIAGNOSTIC: Check render conditions (throttled)
        boolean canRender = modelRenderingEnabled && currentModel != null && modelRenderer.isModelPrepared(currentModel);
        if (shouldLog) {
            // logger.info("RENDER DIAGNOSTIC - can render model: {} (enabled: {}, model: {}, prepared: {})", 
            //            canRender, modelRenderingEnabled, (currentModel != null), 
            //            (currentModel != null ? modelRenderer.isModelPrepared(currentModel) : "N/A"));
        }
        
        // Render current model if available, otherwise render test cube
        if (canRender) {
            // Render the loaded model with shader context
            try {
                if (shouldLog) {
                    // logger.info("RENDER DIAGNOSTIC - Attempting to render model: {}", currentModelName);
                    // logger.info("RENDER DIAGNOSTIC - Shader program: {}, MVP location: {}", shaderProgram, mvpMatrixLocation);
                }
                
                // Get MVP matrix as float array
                float[] mvpArray = new float[16];
                mvpMatrix.get(mvpArray);
                
                modelRenderer.renderModel(currentModel, currentTextureVariant, matrixShaderProgram, 
                                         matrixMvpLocation, matrixModelLocation, mvpArray,
                                         textureAtlas, matrixTextureLocation, matrixUseTextureLocation,
                                         matrixColorLocation);
                
                if (shouldLog) {
                    // logger.info("RENDER DIAGNOSTIC - Successfully rendered model: {}", currentModelName);
                }
            } catch (Exception e) {
                // logger.error("RENDER DIAGNOSTIC - Error rendering model: " + e.getMessage(), e);
                e.printStackTrace();
                // Fall back to test cube on error
                renderTestCube();
            }
        } else {
            // Render test cube as fallback
            if (shouldLog) {
                // logger.info("RENDER DIAGNOSTIC - Rendering test cube fallback");
                
                if (currentModel != null) {
                    // logger.warn("RENDER DIAGNOSTIC - Model '{}' loaded but not prepared - model: {}, renderer prepared: {}", 
                    //            currentModelName, (currentModel != null), modelRenderer.isModelPrepared(currentModel));
                } else if (currentModelName != null) {
                    // logger.warn("RENDER DIAGNOSTIC - Model name '{}' set but currentModel is null - async loading may have failed", currentModelName);
                } else {
                    // logger.info("RENDER DIAGNOSTIC - No model loaded, using test cube");
                }
            }
            renderTestCube();
        }
        
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
        
        // Begin 3D Viewport window with focus capture for proper mouse handling
        ImGui.begin("3D Viewport", ImGuiWindowFlags.None);
        
        // Ensure window can capture mouse input
        if (ImGui.isWindowFocused()) {
            // logger.debug("3D Viewport window has focus");
        }
        
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
        
        // Display the rendered texture as an image
        ImVec2 imagePos = ImGui.getCursorScreenPos();
        ImGui.image(colorTexture, contentRegion.x, contentRegion.y, 0, 1, 1, 0);
        
        // Delegate input handling to the input handler
        if (inputHandler != null) {
            inputHandler.handleInput(imagePos, contentRegion.x, contentRegion.y);
        } else {
            logger.warn("Input handler is null - input will not be processed");
        }
        
        ImGui.end();
        
        // Show controls window
        showControls();
        
        // Show model loading window
        showModelControls();
    }
    
    
    /**
     * Show viewport controls and status.
     */
    private void showControls() {
        ImGui.begin("Viewport Controls");
        
        // Grid controls
        if (ImGui.checkbox("Show Grid", showGrid)) {
            // logger.debug("Grid visibility changed to: {}", showGrid);
        }
        
        // Camera controls
        if (ImGui.button("Reset Camera")) {
            if (camera != null) {
                camera.reset();
                // logger.info("Camera reset to default position");
            }
        }
        
        ImGui.separator();
        ImGui.text("Controls:");
        ImGui.text("Left Click & Drag in viewport: Rotate camera (Endless)");
        ImGui.text("Mouse Wheel in viewport: Zoom");
        
        ImGui.separator();
        ImGui.text("Status:");
        
        // Show viewport status
        ImGui.text("Viewport: " + viewportWidth + "x" + viewportHeight);
        ImGui.text("Initialized: " + (initialized ? "Yes" : "No"));
        ImGui.text("Framebuffer: " + framebuffer);
        ImGui.text("Color Texture: " + colorTexture);
        ImGui.text("Shader Program: " + shaderProgram);
        ImGui.text("Cube VAO: " + cubeVAO);
        
        // Model rendering controls
        if (ImGui.checkbox("Enable Model Rendering", modelRenderingEnabled)) {
            // logger.debug("Model rendering toggled to: {}", modelRenderingEnabled);
        }
        
        // Show input handler status
        if (inputHandler != null) {
            ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Input Handler: Active");
            
            // Show current dragging state
            if (inputHandler.isDragging()) {
                ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Dragging camera - endless mode active!");
            }
            
            // Show mouse capture state
            if (inputHandler.isMouseCaptured()) {
                ImGui.textColored(1.0f, 1.0f, 0.0f, 1.0f, "Mouse captured - cursor hidden");
                ImGui.text("Cursor will return to: (" + (int)inputHandler.getSavedCursorX() + ", " + (int)inputHandler.getSavedCursorY() + ")");
            }
            
            // Show raw mouse motion status
            if (inputHandler.getWindowHandle() != 0L) {
                ImGui.text("Raw mouse motion: " + (inputHandler.isRawMouseMotionSupported() ? "Supported" : "Not supported"));
            } else {
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Warning: Window handle not set!");
                ImGui.text("Call setWindowHandle() to enable endless dragging");
            }
        } else {
            ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Input Handler: NULL");
        }
        
        // Show camera status
        if (camera != null) {
            ImGui.separator();
            ImGui.text("Camera:");
            ImGui.text("Mode: " + camera.getCameraMode());
            
            // Make coordinates copyable by using read-only input fields
            ImString distanceText = new ImString(String.format("%.1f", camera.getDistance()));
            ImString yawText = new ImString(String.format("%.1f°", camera.getYaw()));
            ImString pitchText = new ImString(String.format("%.1f°", camera.getPitch()));
            
            ImGui.text("Distance: ");
            ImGui.sameLine();
            ImGui.setNextItemWidth(80);
            ImGui.inputText("##distance", distanceText, ImGuiInputTextFlags.ReadOnly);
            
            ImGui.text("Yaw: ");
            ImGui.sameLine();
            ImGui.setNextItemWidth(80);
            ImGui.inputText("##yaw", yawText, ImGuiInputTextFlags.ReadOnly);
            
            ImGui.text("Pitch: ");
            ImGui.sameLine();
            ImGui.setNextItemWidth(80);
            ImGui.inputText("##pitch", pitchText, ImGuiInputTextFlags.ReadOnly);
        }
        
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
     * Cleanup OpenGL resources safely.
     */
    public void cleanup() {
        // logger.info("Cleaning up viewport resources");
        
        // Cleanup input handler
        if (inputHandler != null) {
            inputHandler.cleanup();
        }
        
        // Cleanup model renderer
        if (modelRenderer != null) {
            try {
                modelRenderer.close();
            } catch (Exception e) {
                logger.error("Error cleaning up model renderer: " + e.getMessage(), e);
            }
        }
        
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
        
        // Cleanup matrix transformation shaders
        if (matrixShaderProgram != -1) {
            glDeleteProgram(matrixShaderProgram);
            matrixShaderProgram = -1;
        }
        
        if (matrixVertexShader != -1) {
            glDeleteShader(matrixVertexShader);
            matrixVertexShader = -1;
        }
        
        if (matrixFragmentShader != -1) {
            glDeleteShader(matrixFragmentShader);
            matrixFragmentShader = -1;
        }
        
        // Cleanup texture atlas
        if (textureAtlas != null) {
            textureAtlas.close();
            textureAtlas = null;
        }
        
        initialized = false;
        // logger.info("Viewport resources cleaned up");
    }
    
    // Getters with null-safety
    public Camera getCamera() { return camera; }
    public ModelRenderer getModelRenderer() { return modelRenderer; }
    public boolean isShowGrid() { return showGrid; }
    public void setShowGrid(boolean showGrid) { 
        this.showGrid = showGrid; 
        // logger.debug("Grid visibility set to: {}", showGrid);
    }
    public int getColorTexture() { return colorTexture; }
    public boolean isInitialized() { return initialized; }
    
    // Input handler state getters
    public boolean isDragging() { 
        return inputHandler != null ? inputHandler.isDragging() : false; 
    }
    public boolean isMouseCaptured() { 
        return inputHandler != null ? inputHandler.isMouseCaptured() : false; 
    }
    public boolean isRawMouseMotionSupported() { 
        return inputHandler != null ? inputHandler.isRawMouseMotionSupported() : false; 
    }
    
    // Get input handler reference
    public ViewportInputHandler getInputHandler() {
        return inputHandler;
    }
    
    /**
     * Force release mouse capture. Useful for escape key handling.
     */
    public void forceReleaseMouse() {
        if (inputHandler != null) {
            inputHandler.forceReleaseMouse();
            // logger.info("Forced release of mouse capture via input handler");
        } else {
            logger.warn("Cannot force release mouse - input handler is null");
        }
    }
    
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
        // logger.debug("Axes visibility set to: {}", visible);
    }
    
    public void setWireframeMode(boolean wireframe) {
        this.wireframeMode = wireframe;
        // TODO: Implement wireframe mode rendering
        // logger.debug("Wireframe mode set to: {}", wireframe);
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
        // logger.debug("Render requested (handled automatically by ImGui integration)");
    }
    
    public void setModelTransform(float rotX, float rotY, float rotZ, float scale) {
        // TODO: Implement model transformation
        // logger.debug("Model transform set: rotation=({},{},{}), scale={}", rotX, rotY, rotZ, scale);
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
            
            // logger.debug("Rendered to framebuffer at {}x{}", width, height);
            
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
        // logger.debug("Current model name set to: {}", modelName);
    }
    
    /**
     * Get the current StonebreakModel being displayed.
     */
    public StonebreakModel getCurrentModel() {
        return currentModel;
    }
    
    /**
     * Handle frame update - should be called every frame.
     */
    public void update(float deltaTime) {
        if (camera != null) {
            camera.update(deltaTime);
        }
        
        // Handle keyboard input for first-person camera mode
        if (inputHandler != null) {
            inputHandler.handleKeyboardInput(deltaTime);
        }
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
            // logger.info("Current StonebreakModel set: {}", model.getClass().getSimpleName());
        } else {
            setCurrentModelName(null);
            // logger.info("Current StonebreakModel cleared");
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
     * Set the current texture variant and update model texture coordinates.
     */
    public void setCurrentTextureVariant(String variant) {
        if (variant == null || variant.equals(currentTextureVariant)) {
            return; // No change needed
        }
        
        String previousVariant = currentTextureVariant;
        this.currentTextureVariant = variant;
        
        // Force texture coordinate updates on the model renderer if model is loaded
        if (currentModel != null && modelRenderer != null) {
            System.out.println("[OpenMason3DViewport] Switching texture variant: " + 
                             previousVariant + " → " + variant);
            
            // The texture coordinates will be updated during the next render call
            // when ModelRenderer.updateTextureVariants() is called
        }
        
        System.out.println("[OpenMason3DViewport] Texture variant set to: " + variant);
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
            logger.error("LOAD MODEL DIAGNOSTIC - Cannot load model: name is null or empty");
            return;
        }
        
        // logger.error("=== LOAD MODEL DIAGNOSTIC - PUBLIC LOADMODEL CALLED: {} ===", modelName);
        // System.err.println("=== LOAD MODEL DIAGNOSTIC - PUBLIC LOADMODEL CALLED: " + modelName + " ===");
        
        // Load the model asynchronously
        loadModelAsync(modelName);
    }
    
    /**
     * Render the test cube as a fallback when no model is loaded.
     */
    private void renderTestCube() {
        // Debug logging reduced to avoid spam - uncomment if needed for debugging
        // logger.debug("Rendering test cube - shader program: {}, cubeVAO: {}, colorLocation: {}", 
        //             shaderProgram, cubeVAO, colorLocation);
        
        if (shaderProgram == -1) {
            logger.error("Cannot render test cube - shader program is invalid");
            return;
        }
        
        if (cubeVAO == -1) {
            logger.error("Cannot render test cube - cubeVAO is invalid");
            return;
        }
        
        if (colorLocation == -1) {
            logger.warn("Color uniform location is -1, cube may not be colored correctly");
        }
        
        glUniform3f(colorLocation, 1.0f, 0.5f, 0.2f); // Orange cube
        glBindVertexArray(cubeVAO);
        
        // Draw filled cube (36 vertices = 12 triangles)
        glDrawArrays(GL_TRIANGLES, 0, 36);
        
        // logger.debug("Test cube render commands issued");
    }
    
    /**
     * Show model selection and loading controls.
     */
    private void showModelControls() {
        ImGui.begin("Model Controls");
        
        // Model loading section
        ImGui.text("Model Loading:");
        ImGui.separator();
        
        // Current model status
        if (currentModel != null) {
            ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Model Loaded: " + currentModelName);
            ImGui.text("Variant: " + currentTextureVariant);
            ImGui.text("Parts: " + currentModel.getBodyParts().size());
            
            // Texture variant controls
            ImGui.separator();
            ImGui.text("Texture Variants:");
            
            String[] variants = {"default", "angus", "highland", "jersey"};
            for (String variant : variants) {
                if (ImGui.radioButton(variant, currentTextureVariant.equals(variant))) {
                    if (!currentTextureVariant.equals(variant)) {
                        setCurrentTextureVariant(variant);
                        // logger.info("Switched to texture variant: {}", variant);
                    }
                }
            }
            
            if (ImGui.button("Unload Model")) {
                unloadCurrentModel();
            }
        } else {
            if (currentModelLoadingFuture != null && !currentModelLoadingFuture.isDone()) {
                ImGui.textColored(1.0f, 1.0f, 0.0f, 1.0f, "Loading model...");
                ImGui.text("Model: " + currentModelName);
            } else {
                ImGui.text("No model loaded");
            }
            
            ImGui.separator();
            ImGui.text("Available Models:");
            
            // Model loading buttons
            if (ImGui.button("Load Cow Model")) {
                loadModelAsync("standard_cow");
            }
        }
        
        // Model renderer status
        ImGui.separator();
        ImGui.text("Renderer Status:");
        if (modelRenderer != null) {
            ModelRenderer.RenderingStatistics stats = modelRenderer.getStatistics();
            ImGui.text("Initialized: " + stats.initialized);
            ImGui.text("Model Parts: " + stats.modelPartCount);
            ImGui.text("Render Calls: " + stats.totalRenderCalls);
            ImGui.text("Last Render: " + (stats.lastRenderTime > 0 ? (System.currentTimeMillis() - stats.lastRenderTime) + "ms ago" : "Never"));
        }
        
        ImGui.end();
    }
    
    /**
     * Load a model asynchronously with progress feedback.
     */
    private void loadModelAsync(String modelName) {
        if (currentModelLoadingFuture != null && !currentModelLoadingFuture.isDone()) {
            // logger.warn("Model loading already in progress, ignoring request for: {}", modelName);
            return;
        }
        
        // logger.warn("=== STARTING ASYNC MODEL LOAD: {} ===", modelName);
        setCurrentModelName(modelName);
        
        // Create progress callback
        ModelManager.ProgressCallback progressCallback = new ModelManager.ProgressCallback() {
            @Override
            public void onProgress(String operation, int current, int total, String details) {
                // logger.debug("Model loading progress: {}% - {}", (current * 100 / total), details);
            }
            
            @Override
            public void onError(String operation, Throwable error) {
                logger.error("Model loading error in {}: {}", operation, error.getMessage());
            }
            
            @Override
            public void onComplete(String operation, Object result) {
                // logger.info("Model loading operation complete: {}", operation);
            }
        };
        
        // Load model info asynchronously
        // logger.error("=== STEP 1: Starting ModelManager.loadModelInfoAsync for: {} ===", modelName);
        currentModelLoadingFuture = ModelManager.loadModelInfoAsync(modelName, 
                ModelManager.LoadingPriority.HIGH, progressCallback)
            .thenCompose(modelInfo -> {
                // logger.error("=== STEP 2: thenCompose called with modelInfo: {} ===", 
                //            modelInfo != null ? "NOT NULL" : "NULL");
                
                if (modelInfo == null) {
                    // logger.error("=== STEP 2 ERROR: modelInfo is null, throwing exception ===");
                    throw new RuntimeException("Failed to load model info for: " + modelName);
                }
                
                // logger.error("=== STEP 2: Model info loaded successfully: {} ===", modelInfo);
                
                // Create StonebreakModel from ModelInfo
                // logger.error("=== STEP 3: Creating StonebreakModel from ModelInfo ===");
                StonebreakModel model = new StonebreakModel(modelInfo, 
                    ModelManager.getStaticModelParts(modelName));
                
                // logger.error("=== STEP 3: StonebreakModel created successfully ===");
                return CompletableFuture.completedFuture(model);
            })
            .thenAccept(model -> {
                // This runs on background thread, so we need to be careful with OpenGL calls
                // logger.error("=== STEP 4: thenAccept called with model: {} ===", 
                //            model != null ? "NOT NULL" : "NULL");
                // logger.info("Model loaded successfully: {}", modelName);
                
                // Store the model (main thread will prepare it for rendering)
                // logger.error("=== STEP 4: Setting this.currentModel (viewport instance: {}) ===", 
                //            System.identityHashCode(OpenMason3DViewport.this));
                this.currentModel = model;
                
                // logger.error("=== STEP 4: currentModelName should be: {} ===", modelName);
                // logger.error("=== STEP 4: Actual currentModelName value: {} ===", this.currentModelName);
                
                // Clear the loading future to allow new loads
                // logger.error("=== STEP 4: Clearing currentModelLoadingFuture ===");
                this.currentModelLoadingFuture = null;
                
                // logger.error("=== STEP 4: Model loading completed successfully ===");
                // Prepare model for rendering (this needs to be done on main thread)
                // We'll do this in the render loop when OpenGL context is active
            })
            .exceptionally(throwable -> {
                // logger.error("=== STEP ERROR: Exception in model loading chain for {} ===", modelName);
                logger.error("Failed to load model {}: {}", modelName, throwable.getMessage());
                throwable.printStackTrace();
                this.currentModel = null;
                this.currentModelLoadingFuture = null;
                return null;
            });
    }
    
    /**
     * Unload the current model and clean up resources.
     */
    private void unloadCurrentModel() {
        // logger.info("Unloading current model: {}", currentModelName);
        
        // Cancel any pending loading
        if (currentModelLoadingFuture != null && !currentModelLoadingFuture.isDone()) {
            currentModelLoadingFuture.cancel(true);
            currentModelLoadingFuture = null;
        }
        
        // Clear model state
        currentModel = null;
        setCurrentModelName(null);
        setCurrentTextureVariant("default");
        
        // logger.info("Model unloaded successfully");
    }
    
}