package com.openmason.ui.viewport;

import com.openmason.rendering.BufferManager;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.OpenGLValidator;
import com.openmason.rendering.PerformanceOptimizer;
import com.openmason.AsyncResourceManager;
import com.openmason.model.StonebreakModel;
import com.openmason.model.ModelManager;
import com.openmason.texture.TextureManager;
import com.openmason.camera.ArcBallCamera;
import com.openmason.texture.stonebreak.StonebreakTextureDefinition;
import com.openmason.model.stonebreak.StonebreakModelDefinition;

// Enhanced Canvas-based 3D rendering (DriftFX-free implementation)

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.joml.Matrix4f;
import org.joml.Vector3f;
// Note: LWJGL imports removed - using pure Canvas-based rendering

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Professional 3D viewport using enhanced Canvas-based rendering within JavaFX.
 * 
 * This class provides a complete 3D rendering environment that integrates seamlessly with the existing
 * Phase 2 buffer management systems while providing professional-grade viewport functionality.
 * 
 * Key Features:
 * - Advanced Canvas-based 3D-to-2D projection rendering
 * - Perfect integration with existing BufferManager and ModelRenderer
 * - Sophisticated 3D model visualization with depth simulation
 * - Comprehensive wireframe and solid rendering modes
 * - Performance monitoring and statistics collection
 * - Real-time texture variant switching
 * - Professional ArcBall camera with 3D-to-2D projection
 * 
 * Architecture:
 * - Extends StackPane with enhanced Canvas rendering
 * - Uses BufferManager.getInstance() for model data management
 * - Leverages existing ModelRenderer with "OpenMason3DViewport" debug prefix
 * - Coordinates with AsyncResourceManager for background loading
 * - Sophisticated Canvas-based 3D projection using camera matrices
 */
public class OpenMason3DViewport extends StackPane {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenMason3DViewport.class);
    
    // Integration with existing Phase 2 systems
    private BufferManager bufferManager;
    private ModelRenderer modelRenderer;
    private ModelManager modelManager; // Unused - kept for compatibility
    private TextureManager textureManager; // Unused - kept for compatibility
    
    // Enhanced Canvas-based rendering components
    private Canvas renderCanvas;
    private GraphicsContext graphicsContext;
    
    // 3D-to-2D projection system
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Vector3f projectedPoint = new Vector3f();
    
    // Canvas rendering optimization
    private double canvasWidth;
    private double canvasHeight;
    private boolean canvasSizeChanged = false;
    
    // Canvas is now the primary rendering method
    private static final boolean USE_ENHANCED_CANVAS = true;
    
    // Rendering state management
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean renderingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    
    // Current model state
    private volatile StonebreakModel currentModel;
    private final StringProperty currentTextureVariant = new SimpleStringProperty("default");
    
    // Viewport settings
    private final BooleanProperty wireframeMode = new SimpleBooleanProperty(false);
    private final BooleanProperty gridVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty axesVisible = new SimpleBooleanProperty(true);
    
    // Professional ArcBall camera system
    private ArcBallCamera camera;
    private final Matrix4f modelMatrix = new Matrix4f();
    
    // Performance monitoring and optimization
    private PerformanceOptimizer performanceOptimizer;
    private final BooleanProperty performanceOverlayEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty adaptiveQualityEnabled = new SimpleBooleanProperty(true);
    
    // Mouse interaction state
    private boolean leftMousePressed = false;
    private boolean rightMousePressed = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    
    // Camera control settings
    private final BooleanProperty cameraControlsEnabled = new SimpleBooleanProperty(true);
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;
    
    // Performance monitoring
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong lastFPSUpdate = new AtomicLong(System.currentTimeMillis());
    private volatile double currentFPS = 0.0;
    private volatile long lastFrameTime = 0;
    
    // Error handling
    private volatile Throwable lastError;
    private final AtomicLong errorCount = new AtomicLong(0);
    
    /**
     * Creates a new OpenMason 3D viewport with enhanced Canvas-based rendering.
     */
    public OpenMason3DViewport() {
        super();
        logger.info("Creating OpenMason 3D Viewport with enhanced Canvas-based rendering");
        
        // Initialize Canvas-based rendering system
        setupEnhancedCanvas();
        
        // Initialize JavaFX properties
        setupJavaFXProperties();
        
        // Start initialization process
        initializeAsync();
    }
    
    /**
     * Sets up the enhanced Canvas-based rendering system.
     */
    private void setupEnhancedCanvas() {
        try {
            logger.info("Initializing enhanced Canvas-based 3D rendering system...");
            
            // Create canvas that fills the viewport
            renderCanvas = new Canvas();
            graphicsContext = renderCanvas.getGraphicsContext2D();
            
            // Make canvas resizable and bind to viewport size
            renderCanvas.widthProperty().bind(widthProperty());
            renderCanvas.heightProperty().bind(heightProperty());
            
            // Listen for size changes to update projection matrix
            renderCanvas.widthProperty().addListener((obs, oldVal, newVal) -> {
                canvasWidth = newVal.doubleValue();
                canvasSizeChanged = true;
                updateProjectionMatrix();
                requestRender();
            });
            
            renderCanvas.heightProperty().addListener((obs, oldVal, newVal) -> {
                canvasHeight = newVal.doubleValue();
                canvasSizeChanged = true;
                updateProjectionMatrix();
                requestRender();
            });
            
            // Add canvas to this StackPane
            getChildren().add(renderCanvas);
            
            // Set up enhanced canvas event handlers
            setupCanvasEventHandlers();
            
            logger.info("Enhanced Canvas setup completed successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize enhanced Canvas rendering", e);
            throw new RuntimeException("Canvas rendering initialization failed", e);
        }
    }
    
    /**
     * Updates the projection matrix based on current canvas size and camera settings.
     */
    private void updateProjectionMatrix() {
        if (camera != null) {
            double width = Math.max(canvasWidth, 1);
            double height = Math.max(canvasHeight, 1);
            
            // Create perspective projection matrix for 3D-to-2D conversion
            float aspectRatio = (float) (width / height);
            projectionMatrix.setPerspective(
                (float) Math.toRadians(45.0f), // 45-degree field of view
                aspectRatio,
                NEAR_PLANE,
                FAR_PLANE
            );
            
            logger.debug("Updated projection matrix for canvas size: {}x{}", width, height);
        }
    }
    
    /**
     * Projects a 3D point to 2D screen coordinates using the camera's view and projection matrices.
     * 
     * @param worldPoint The 3D point in world coordinates
     * @param screenPoint Output 2D screen coordinates (x, y) with depth in z
     * @return True if the point is visible (in front of camera), false otherwise
     */
    private boolean project3DTo2D(Vector3f worldPoint, Vector3f screenPoint) {
        if (camera == null) {
            return false;
        }
        
        // Get view matrix from camera
        viewMatrix.set(camera.getViewMatrix());
        
        // Transform point: world -> view -> clip -> screen
        Vector3f viewPoint = new Vector3f();
        viewMatrix.transformPosition(worldPoint, viewPoint);
        
        // Check if point is behind camera
        if (viewPoint.z > -NEAR_PLANE) {
            return false;
        }
        
        // Apply projection matrix
        Vector3f clipPoint = new Vector3f();
        projectionMatrix.transformPosition(viewPoint, clipPoint);
        
        // For 3D to 2D projection, we need to handle the perspective divide
        // Since we're using a simple approach, we'll use the z-component for depth
        float w = 1.0f; // Simplified projection
        if (clipPoint.z != 0) {
            w = Math.abs(clipPoint.z);
        }
        
        float ndcX = clipPoint.x / w;
        float ndcY = clipPoint.y / w;
        float ndcZ = clipPoint.z;
        
        // Convert to screen coordinates
        screenPoint.x = (ndcX + 1.0f) * 0.5f * (float) canvasWidth;
        screenPoint.y = (1.0f - ndcY) * 0.5f * (float) canvasHeight; // Flip Y for screen coordinates
        screenPoint.z = ndcZ; // Keep depth for sorting
        
        return ndcX >= -1.0f && ndcX <= 1.0f && ndcY >= -1.0f && ndcY <= 1.0f;
    }
    
    /**
     * Sets up event handlers for Canvas fallback.
     */
    private void setupCanvasEventHandlers() {
        if (renderCanvas == null) {
            return;
        }
        
        // Mouse events
        renderCanvas.setOnMousePressed(this::handleMousePressed);
        renderCanvas.setOnMouseDragged(this::handleMouseDragged);
        renderCanvas.setOnMouseReleased(this::handleMouseReleased);
        renderCanvas.setOnScroll(this::handleMouseScroll);
        
        // Keyboard events
        renderCanvas.setOnKeyPressed(this::handleKeyPressed);
        
        // Make canvas focusable
        renderCanvas.setFocusTraversable(true);
        
        logger.debug("Canvas fallback event handlers configured");
    }
    
    /**
     * Sets up JavaFX property bindings and listeners.
     */
    private void setupJavaFXProperties() {
        // Listen for texture variant changes
        currentTextureVariant.addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                requestRender();
            }
        });
        
        // Listen for rendering mode changes
        wireframeMode.addListener((obs, oldVal, newVal) -> requestRender());
        gridVisible.addListener((obs, oldVal, newVal) -> requestRender());
        axesVisible.addListener((obs, oldVal, newVal) -> requestRender());
    }
    
    /**
     * Initializes the viewport asynchronously to avoid blocking the JavaFX thread.
     */
    private void initializeAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting viewport initialization...");
                
                // Initialize Phase 2 systems integration
                initializePhase2Integration();
                
                // Initialize enhanced Canvas rendering system
                initializeEnhancedCanvasRendering();
                
                // Set up professional camera system
                initializeCamera();
                
                // Set up performance monitoring system
                initializePerformanceMonitoring();
                
                // Set up input handling
                initializeInputHandling();
                
                // Mark as initialized
                initialized.set(true);
                renderingEnabled.set(true);
                
                logger.info("Viewport initialization completed successfully");
                
                // Request initial render
                Platform.runLater(this::requestRender);
                
            } catch (Exception e) {
                logger.error("Failed to initialize viewport", e);
                lastError = e;
                errorCount.incrementAndGet();
                handleInitializationError(e);
            }
        });
    }
    
    /**
     * Initializes integration with existing Phase 2 systems.
     */
    private void initializePhase2Integration() {
        logger.debug("Initializing Phase 2 systems integration...");
        
        // Get BufferManager singleton
        bufferManager = BufferManager.getInstance();
        
        // Configure BufferManager for 3D viewport usage
        bufferManager.setMemoryTrackingEnabled(true);
        bufferManager.setLeakDetectionEnabled(true);
        bufferManager.setMemoryWarningThreshold(150 * 1024 * 1024); // 150MB for 3D viewport
        
        // Initialize ModelRenderer with viewport-specific debug prefix
        modelRenderer = new ModelRenderer("OpenMason3DViewport");
        modelRenderer.initialize();
        
        // Initialize managers (static access)
        ModelManager.initialize();
        TextureManager.initialize();
        
        // Store references for type checking (managers are static)
        modelManager = null; // Not used - ModelManager uses static methods
        textureManager = null; // Not used - TextureManager uses static methods
        
        logger.debug("Phase 2 integration initialized successfully");
    }
    
    /**
     * Initializes the enhanced Canvas-based rendering system.
     */
    private void initializeEnhancedCanvasRendering() {
        logger.debug("Initializing enhanced Canvas rendering system...");
        
        try {
            if (renderCanvas == null || graphicsContext == null) {
                throw new RuntimeException("Canvas not properly initialized");
            }
            
            // Initialize canvas properties
            canvasWidth = renderCanvas.getWidth();
            canvasHeight = renderCanvas.getHeight();
            
            // Set up initial projection matrix
            updateProjectionMatrix();
            
            // Set up graphics context properties for enhanced rendering
            setupCanvasRenderingProperties();
            
            logger.debug("Enhanced Canvas rendering initialization completed");
            
        } catch (Exception e) {
            logger.error("Failed to initialize enhanced Canvas rendering", e);
            throw new RuntimeException("Enhanced Canvas rendering initialization failed", e);
        }
    }
    
    /**
     * Sets up graphics context properties for enhanced rendering.
     */
    private void setupCanvasRenderingProperties() {
        if (graphicsContext != null) {
            // Enable smooth rendering
            graphicsContext.setImageSmoothing(true);
            
            // Set default line properties for crisp wireframes
            graphicsContext.setLineWidth(1.0);
            
            logger.debug("Canvas rendering properties configured");
        }
    }
    
    /**
     * Draws a 3D line in screen space using 3D-to-2D projection.
     * 
     * @param start 3D start point
     * @param end 3D end point
     * @param color Line color
     */
    private void drawLine3D(Vector3f start, Vector3f end, Color color) {
        Vector3f screenStart = new Vector3f();
        Vector3f screenEnd = new Vector3f();
        
        boolean startVisible = project3DTo2D(start, screenStart);
        boolean endVisible = project3DTo2D(end, screenEnd);
        
        // Draw line if at least one point is visible
        if (startVisible || endVisible) {
            graphicsContext.setStroke(color);
            graphicsContext.strokeLine(
                screenStart.x, screenStart.y,
                screenEnd.x, screenEnd.y
            );
        }
    }
    
    /**
     * Draws a 3D wireframe cube using enhanced Canvas rendering.
     * 
     * @param center Center point of the cube
     * @param size Size of the cube
     * @param color Color of the wireframe
     */
    private void drawWireframeCube3D(Vector3f center, float size, Color color) {
        float halfSize = size / 2.0f;
        
        // Define cube vertices
        Vector3f[] vertices = {
            new Vector3f(center.x - halfSize, center.y - halfSize, center.z - halfSize), // 0: left-bottom-back
            new Vector3f(center.x + halfSize, center.y - halfSize, center.z - halfSize), // 1: right-bottom-back
            new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize), // 2: right-top-back
            new Vector3f(center.x - halfSize, center.y + halfSize, center.z - halfSize), // 3: left-top-back
            new Vector3f(center.x - halfSize, center.y - halfSize, center.z + halfSize), // 4: left-bottom-front
            new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize), // 5: right-bottom-front
            new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize), // 6: right-top-front
            new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize)  // 7: left-top-front
        };
        
        // Define cube edges
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // Back face
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // Front face
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // Connecting edges
        };
        
        // Draw all edges
        for (int[] edge : edges) {
            drawLine3D(vertices[edge[0]], vertices[edge[1]], color);
        }
    }
    
    /**
     * Draws a 3D solid cube with depth-based shading using enhanced Canvas rendering.
     * 
     * @param center Center point of the cube
     * @param size Size of the cube
     * @param baseColor Base color of the cube
     */
    private void drawSolidCube3D(Vector3f center, float size, Color baseColor) {
        float halfSize = size / 2.0f;
        
        // Define cube faces with vertices (in counter-clockwise order for outward normals)
        Vector3f[][][] faces = {
            // Front face
            {{new Vector3f(center.x - halfSize, center.y - halfSize, center.z + halfSize),
              new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize),
              new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize),
              new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize)}},
            // Right face
            {{new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize),
              new Vector3f(center.x + halfSize, center.y - halfSize, center.z - halfSize),
              new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize),
              new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize)}},
            // Top face
            {{new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize),
              new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize),
              new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize),
              new Vector3f(center.x - halfSize, center.y + halfSize, center.z - halfSize)}}
        };
        
        // Draw faces with depth-based shading
        drawCubeFace(faces[0][0], baseColor.brighter()); // Front face (brightest)
        drawCubeFace(faces[1][0], baseColor.darker());   // Right face (darker)
        drawCubeFace(faces[2][0], baseColor);            // Top face (medium)
    }
    
    /**
     * Draws a single face of a cube using 2D projection.
     * 
     * @param vertices Array of 4 vertices defining the face
     * @param color Color to fill the face
     */
    private void drawCubeFace(Vector3f[] vertices, Color color) {
        if (vertices.length != 4) return;
        
        // Project all vertices to screen space
        Vector3f[] screenVertices = new Vector3f[4];
        boolean[] visible = new boolean[4];
        boolean anyVisible = false;
        
        for (int i = 0; i < 4; i++) {
            screenVertices[i] = new Vector3f();
            visible[i] = project3DTo2D(vertices[i], screenVertices[i]);
            if (visible[i]) anyVisible = true;
        }
        
        // Only draw if at least one vertex is visible
        if (anyVisible) {
            graphicsContext.setFill(color);
            graphicsContext.beginPath();
            
            // Move to first vertex
            graphicsContext.moveTo(screenVertices[0].x, screenVertices[0].y);
            
            // Draw lines to other vertices
            for (int i = 1; i < 4; i++) {
                graphicsContext.lineTo(screenVertices[i].x, screenVertices[i].y);
            }
            
            graphicsContext.closePath();
            graphicsContext.fill();
        }
    }
    
    /**
     * Renders an enhanced 3D model representation on Canvas.
     * Uses sophisticated 3D-to-2D projection for realistic appearance.
     * 
     * @param model The model to render
     * @param showWireframe Whether to show wireframe overlay
     */
    private void renderModel3DOnCanvas(StonebreakModel model, boolean showWireframe) {
        if (model == null) return;
        
        // Render the actual cow model parts instead of a cube
        renderActualCowModel(model, showWireframe);
    }
    
    /**
     * Render the actual cow model with all its parts (body, head, legs, etc.)
     */
    private void renderActualCowModel(StonebreakModel model, boolean showWireframe) {
        // Debug logging
        String modelName = model.getModelDefinition().getModelName();
        System.out.println("[OpenMason3DViewport] Attempting to load model parts for: " + modelName);
        System.out.println("[OpenMason3DViewport] Available models: " + ModelManager.getAvailableModels());
        System.out.println("[OpenMason3DViewport] ModelManager initialized: " + !ModelManager.isInitializing());
        
        // Get model parts from the loaded definition
        var modelParts = ModelManager.getStaticModelParts(modelName);
        System.out.println("[OpenMason3DViewport] Model parts result: " + (modelParts != null ? modelParts.length + " parts" : "null"));
        
        if (modelParts == null || modelParts.length == 0) {
            System.out.println("[OpenMason3DViewport] No model parts found, using fallback cube");
            // Fallback to simple representation if no parts available
            renderFallbackCube(showWireframe);
            return;
        }
        
        // Render each model part individually
        for (int i = 0; i < modelParts.length; i++) {
            var part = modelParts[i];
            if (part != null) {
                renderModelPart(part, showWireframe);
            }
        }
        
        // Show model information
        Vector3f modelCenter = new Vector3f(0, 0, 0);
        float modelSize = 2.0f;
        renderModelInfo(model, modelCenter, modelSize);
    }
    
    /**
     * Render a single model part (body, head, leg, etc.)
     */
    private void renderModelPart(StonebreakModelDefinition.ModelPart part, boolean showWireframe) {
        // Convert part position and size to Vector3f
        Vector3f position = new Vector3f(part.getPosition().getX(), part.getPosition().getY(), part.getPosition().getZ());
        Vector3f size = new Vector3f(part.getSize().getX(), part.getSize().getY(), part.getSize().getZ());
        
        // Get color based on part type and current texture variant
        Color partColor = getPartColor(part.getName(), currentTextureVariant.get());
        
        if (!showWireframe) {
            // Draw solid part with proper dimensions
            drawSolidCuboid3D(position, size, partColor);
        }
        
        if (showWireframe || wireframeMode.get()) {
            // Draw wireframe overlay
            Color wireColor = showWireframe ? Color.WHITE : partColor.brighter();
            drawWireframeCuboid3D(position, size, wireColor);
        }
    }
    
    /**
     * Fallback rendering method when model parts aren't available
     */
    private void renderFallbackCube(boolean showWireframe) {
        Vector3f modelCenter = new Vector3f(0, 0, 0);
        float modelSize = 2.0f;
        Color baseColor = getModelColorForVariant(currentTextureVariant.get());
        
        if (!showWireframe) {
            drawSolidCube3D(modelCenter, modelSize, baseColor);
        }
        
        if (showWireframe || wireframeMode.get()) {
            Color wireColor = showWireframe ? Color.WHITE : baseColor.brighter();
            drawWireframeCube3D(modelCenter, modelSize, wireColor);
        }
        
        // Add model label with 3D positioning
        Vector3f labelPos = new Vector3f(modelCenter.x, modelCenter.y - modelSize * 0.7f, modelCenter.z);
        Vector3f screenLabelPos = new Vector3f();
        if (project3DTo2D(labelPos, screenLabelPos)) {
            graphicsContext.setFill(Color.WHITE);
            graphicsContext.fillText("Model: Fallback Cube", screenLabelPos.x - 30, screenLabelPos.y);
        }
    }
    
    /**
     * Draw a solid cuboid with specified position and size (for individual model parts)
     */
    private void drawSolidCuboid3D(Vector3f position, Vector3f size, Color baseColor) {
        Vector3f halfSize = new Vector3f(size.x / 2.0f, size.y / 2.0f, size.z / 2.0f);
        
        // Define cuboid faces with vertices
        Vector3f[][][] faces = {
            // Front face
            {{new Vector3f(position.x - halfSize.x, position.y - halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y - halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x - halfSize.x, position.y + halfSize.y, position.z + halfSize.z)}},
            // Right face
            {{new Vector3f(position.x + halfSize.x, position.y - halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y - halfSize.y, position.z - halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z - halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z + halfSize.z)}},
            // Top face
            {{new Vector3f(position.x - halfSize.x, position.y + halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z + halfSize.z),
              new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z - halfSize.z),
              new Vector3f(position.x - halfSize.x, position.y + halfSize.y, position.z - halfSize.z)}}
        };
        
        // Draw faces with depth-based shading
        drawCubeFace(faces[0][0], baseColor.brighter()); // Front face (brightest)
        drawCubeFace(faces[1][0], baseColor.darker());   // Right face (darker)
        drawCubeFace(faces[2][0], baseColor);            // Top face (medium)
    }
    
    /**
     * Draw wireframe cuboid with specified position and size
     */
    private void drawWireframeCuboid3D(Vector3f position, Vector3f size, Color wireColor) {
        Vector3f halfSize = new Vector3f(size.x / 2.0f, size.y / 2.0f, size.z / 2.0f);
        
        // Define the 8 vertices of the cuboid
        Vector3f[] vertices = {
            new Vector3f(position.x - halfSize.x, position.y - halfSize.y, position.z - halfSize.z), // 0: back-bottom-left
            new Vector3f(position.x + halfSize.x, position.y - halfSize.y, position.z - halfSize.z), // 1: back-bottom-right
            new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z - halfSize.z), // 2: back-top-right
            new Vector3f(position.x - halfSize.x, position.y + halfSize.y, position.z - halfSize.z), // 3: back-top-left
            new Vector3f(position.x - halfSize.x, position.y - halfSize.y, position.z + halfSize.z), // 4: front-bottom-left
            new Vector3f(position.x + halfSize.x, position.y - halfSize.y, position.z + halfSize.z), // 5: front-bottom-right
            new Vector3f(position.x + halfSize.x, position.y + halfSize.y, position.z + halfSize.z), // 6: front-top-right
            new Vector3f(position.x - halfSize.x, position.y + halfSize.y, position.z + halfSize.z)  // 7: front-top-left
        };
        
        graphicsContext.setStroke(wireColor);
        graphicsContext.setLineWidth(1.0);
        
        // Draw 12 edges of the cuboid
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // Back face edges
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // Front face edges
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // Connecting edges
        };
        
        for (int[] edge : edges) {
            drawLine3D(vertices[edge[0]], vertices[edge[1]], wireColor);
        }
    }
    
    /**
     * Get appropriate color for a model part based on its name and texture variant
     */
    private Color getPartColor(String partName, String variant) {
        // Define color mappings for different cow parts
        Map<String, Color> partColors = new HashMap<>();
        
        // Base colors for different parts
        partColors.put("body", Color.SADDLEBROWN);
        partColors.put("head", Color.PERU);
        partColors.put("front_left", Color.DARKGOLDENROD);
        partColors.put("front_right", Color.DARKGOLDENROD);
        partColors.put("back_left", Color.DARKGOLDENROD);
        partColors.put("back_right", Color.DARKGOLDENROD);
        partColors.put("left_horn", Color.LIGHTGRAY);
        partColors.put("right_horn", Color.LIGHTGRAY);
        partColors.put("udder", Color.PINK);
        partColors.put("tail", Color.BROWN);
        
        // Get base color for the part
        Color baseColor = partColors.getOrDefault(partName, Color.LIGHTBLUE);
        
        // Modify color based on texture variant
        if (variant != null) {
            switch (variant.toLowerCase()) {
                case "angus":
                    return baseColor.darker();
                case "highland":
                    return baseColor.brighter();
                case "jersey":
                    return Color.color(
                        Math.min(1.0, baseColor.getRed() + 0.1),
                        Math.min(1.0, baseColor.getGreen() + 0.1),
                        baseColor.getBlue()
                    );
                default:
                    return baseColor;
            }
        }
        
        return baseColor;
    }

    /**
     * Gets the appropriate color for a model based on its texture variant.
     * 
     * @param variant The texture variant name
     * @return Color representing the variant
     */
    private Color getModelColorForVariant(String variant) {
        if (variant == null) {
            return Color.LIGHTBLUE;
        }
        
        switch (variant.toLowerCase()) {
            case "default":
                return Color.LIGHTBLUE;
            case "angus":
                return Color.DARKSLATEGRAY;
            case "highland":
                return Color.BROWN;
            case "jersey":
                return Color.LIGHTYELLOW;
            default:
                return Color.LIGHTGRAY;
        }
    }
    
    /**
     * Renders enhanced grid with 3D depth perception.
     */
    private void renderEnhancedGrid() {
        if (!gridVisible.get()) return;
        
        int gridSize = 10;
        float gridSpacing = 1.0f;
        Color gridColor = Color.rgb(76, 76, 76, 0.6); // Semi-transparent grid
        
        // Draw grid lines with 3D projection
        for (int i = -gridSize; i <= gridSize; i++) {
            float x = i * gridSpacing;
            float z1 = -gridSize * gridSpacing;
            float z2 = gridSize * gridSpacing;
            
            // Vertical grid lines
            Vector3f start1 = new Vector3f(x, 0, z1);
            Vector3f end1 = new Vector3f(x, 0, z2);
            drawLine3D(start1, end1, gridColor);
            
            // Horizontal grid lines
            float z = i * gridSpacing;
            float x1 = -gridSize * gridSpacing;
            float x2 = gridSize * gridSpacing;
            
            Vector3f start2 = new Vector3f(x1, 0, z);
            Vector3f end2 = new Vector3f(x2, 0, z);
            drawLine3D(start2, end2, gridColor);
        }
    }
    
    /**
     * Initializes the professional ArcBall camera system.
     */
    private void initializeCamera() {
        logger.debug("Initializing ArcBall camera system...");
        
        // Create new ArcBall camera instance
        camera = new ArcBallCamera();
        
        // Set up default camera position for model viewing
        camera.setOrientation(45.0f, 20.0f);  // Nice default angle
        camera.setDistance(5.0f);              // Reasonable starting distance
        
        logger.debug("ArcBall camera system initialized");
    }
    
    /**
     * Initializes the performance monitoring and optimization system.
     */
    private void initializePerformanceMonitoring() {
        logger.debug("Initializing performance monitoring system...");
        
        // Create performance optimizer instance
        performanceOptimizer = new PerformanceOptimizer();
        performanceOptimizer.setDebugPrefix("OpenMason3DViewport");
        performanceOptimizer.setDebugMode(false); // Can be enabled for debugging
        
        // Bind adaptive quality setting
        adaptiveQualityEnabled.addListener((obs, oldVal, newVal) -> {
            if (performanceOptimizer != null) {
                performanceOptimizer.setAdaptiveQualityEnabled(newVal);
                logger.debug("Adaptive quality {}", newVal ? "enabled" : "disabled");
            }
        });
        
        // Listen for performance overlay changes
        performanceOverlayEnabled.addListener((obs, oldVal, newVal) -> requestRender());
        
        logger.debug("Performance monitoring system initialized");
    }
    
    /**
     * Initializes mouse and keyboard input handling for camera controls.
     */
    private void initializeInputHandling() {
        logger.debug("Initializing camera input handling...");
        
        // Note: Event handlers are now set up in setupCanvasEventHandlers()
        // which is called from setupCanvas()
        
        logger.debug("Input handling setup completed (via Canvas)");
        
        logger.debug("Camera input handling initialized");
    }
    
    /**
     * Updates camera system and calculates matrices for current frame.
     */
    private void updateCameraSystem(float deltaTime) {
        if (camera != null) {
            // Update camera interpolation and state
            camera.update(deltaTime);
            
            // Model matrix (identity for now)
            modelMatrix.identity();
        }
    }
    
    /**
     * Gets the current view matrix from the camera system.
     * 
     * @return Current view matrix
     */
    private Matrix4f getCurrentViewMatrix() {
        return camera != null ? camera.getViewMatrix() : new Matrix4f().identity();
    }
    
    /**
     * Gets the current projection matrix from the camera system.
     * 
     * @return Current projection matrix
     */
    private Matrix4f getCurrentProjectionMatrix() {
        if (camera == null) {
            return new Matrix4f().identity();
        }
        
        int width = (int) Math.max(canvasWidth, 1);
        int height = (int) Math.max(canvasHeight, 1);
        
        return camera.getProjectionMatrix(width, height, NEAR_PLANE, FAR_PLANE);
    }
    
    /**
     * Main enhanced Canvas rendering method with sophisticated 3D-to-2D projection.
     */
    private void renderEnhancedCanvasContent() {
        try {
            // Begin performance monitoring
            if (performanceOptimizer != null) {
                performanceOptimizer.beginFrame();
            }
            
            // Update frame timing
            updateFrameTiming();
            
            // Update camera system
            updateCameraSystem(0.016f);
            
            // Validate rendering state
            validateCanvasRenderingState();
            
            // Update canvas size if changed
            if (canvasSizeChanged) {
                canvasWidth = renderCanvas.getWidth();
                canvasHeight = renderCanvas.getHeight();
                updateProjectionMatrix();
                canvasSizeChanged = false;
            }
            
            // Clear canvas with professional background
            clearEnhancedCanvas();
            
            // Render viewport helpers first (behind model)
            renderEnhancedGrid();
            renderEnhanced3DAxes();
            
            // Render the current model with sophisticated 3D rendering
            if (currentModel != null) {
                renderSophisticatedModel3D(currentModel);
            } else {
                renderPlaceholderModel();
            }
            
            // Render performance overlay if enabled
            if (performanceOverlayEnabled.get()) {
                renderPerformanceOverlayCanvas();
            }
            
            // End performance monitoring
            if (performanceOptimizer != null) {
                performanceOptimizer.endFrame();
            }
            
        } catch (Exception e) {
            // End performance monitoring even on error
            if (performanceOptimizer != null) {
                performanceOptimizer.endFrame();
            }
            
            logger.error("Error during enhanced Canvas rendering", e);
            lastError = e;
            errorCount.incrementAndGet();
            handleRenderingError(e);
        }
    }
    
    /**
     * Renders a placeholder model when no model is loaded.
     */
    private void renderPlaceholderModel() {
        Vector3f center = new Vector3f(0, 0, 0);
        float size = 1.5f;
        Color placeholderColor = Color.LIGHTGRAY;
        
        // Draw wireframe cube as placeholder
        drawWireframeCube3D(center, size, placeholderColor);
        
        // Add "No Model" text
        Vector3f labelPos = new Vector3f(center.x, center.y - size * 0.7f, center.z);
        Vector3f screenLabelPos = new Vector3f();
        if (project3DTo2D(labelPos, screenLabelPos)) {
            graphicsContext.setFill(Color.LIGHTGRAY);
            graphicsContext.setFont(javafx.scene.text.Font.font("Arial", 12));
            graphicsContext.fillText("No Model Loaded", screenLabelPos.x - 40, screenLabelPos.y);
        }
    }
    
    /**
     * Renders enhanced 3D axes with proper depth and perspective.
     */
    private void renderEnhanced3DAxes() {
        if (!axesVisible.get()) return;
        
        float axisLength = 2.0f;
        Vector3f origin = new Vector3f(0, 0, 0);
        
        // X axis - Red
        Vector3f xEnd = new Vector3f(axisLength, 0, 0);
        drawLine3D(origin, xEnd, Color.RED);
        
        // Y axis - Green  
        Vector3f yEnd = new Vector3f(0, axisLength, 0);
        drawLine3D(origin, yEnd, Color.GREEN);
        
        // Z axis - Blue
        Vector3f zEnd = new Vector3f(0, 0, axisLength);
        drawLine3D(origin, zEnd, Color.BLUE);
        
        // Add axis labels with 3D positioning
        renderAxisLabel(xEnd, "X", Color.RED);
        renderAxisLabel(yEnd, "Y", Color.GREEN);
        renderAxisLabel(zEnd, "Z", Color.BLUE);
    }
    
    /**
     * Renders a 3D axis label at the specified position.
     * 
     * @param position 3D position for the label
     * @param text Label text
     * @param color Label color
     */
    private void renderAxisLabel(Vector3f position, String text, Color color) {
        Vector3f screenPos = new Vector3f();
        if (project3DTo2D(position, screenPos)) {
            graphicsContext.setFill(color);
            graphicsContext.setFont(javafx.scene.text.Font.font("Arial", 12));
            graphicsContext.fillText(text, screenPos.x + 5, screenPos.y + 5);
        }
    }
    
    /**
     * Clears the canvas.
     */
    private void clearCanvas() {
        if (graphicsContext != null) {
            // Set clear color (dark gray background)
            graphicsContext.setFill(Color.rgb(51, 51, 51)); // Dark gray (0.2 * 255)
            
            // Clear the entire canvas
            double width = renderCanvas.getWidth();
            double height = renderCanvas.getHeight();
            graphicsContext.fillRect(0, 0, width, height);
        }
    }
    
    /**
     * Renders a sophisticated 3D model with multiple rendering modes.
     * Supports wireframe, solid, and combined rendering with proper depth perception.
     * 
     * @param model The model to render
     */
    private void renderSophisticatedModel3D(StonebreakModel model) {
        if (model == null) return;
        
        // Use the new actual cow model rendering instead of the old cube approach
        renderActualCowModel(model, wireframeMode.get());
    }
    
    /**
     * Renders a solid model with simulated lighting effects.
     * 
     * @param center Center position of the model
     * @param size Size of the model
     * @param baseColor Base color of the model
     */
    private void renderSolidModelWithLighting(Vector3f center, float size, Color baseColor) {
        // Simulate lighting by varying face brightness based on orientation
        // Light source is assumed to be at (2, 2, 2)
        Vector3f lightDir = new Vector3f(2, 2, 2).normalize();
        
        // Front face (brightest - facing light)
        Color frontColor = adjustColorBrightness(baseColor, 1.0);
        drawModelFace(center, size, "front", frontColor);
        
        // Right face (medium brightness)
        Color rightColor = adjustColorBrightness(baseColor, 0.7);
        drawModelFace(center, size, "right", rightColor);
        
        // Top face (medium-high brightness)
        Color topColor = adjustColorBrightness(baseColor, 0.85);
        drawModelFace(center, size, "top", topColor);
        
        // Left face (darker)
        Color leftColor = adjustColorBrightness(baseColor, 0.5);
        drawModelFace(center, size, "left", leftColor);
        
        // Bottom face (darkest)
        Color bottomColor = adjustColorBrightness(baseColor, 0.3);
        drawModelFace(center, size, "bottom", bottomColor);
        
        // Back face (medium-dark)
        Color backColor = adjustColorBrightness(baseColor, 0.6);
        drawModelFace(center, size, "back", backColor);
    }
    
    /**
     * Adjusts color brightness for lighting simulation.
     * 
     * @param baseColor Base color to adjust
     * @param factor Brightness factor (0.0 to 1.0+)
     * @return Adjusted color
     */
    private Color adjustColorBrightness(Color baseColor, double factor) {
        double red = Math.min(1.0, baseColor.getRed() * factor);
        double green = Math.min(1.0, baseColor.getGreen() * factor);
        double blue = Math.min(1.0, baseColor.getBlue() * factor);
        return new Color(red, green, blue, baseColor.getOpacity());
    }
    
    /**
     * Draws a specific face of a model cube.
     * 
     * @param center Center of the model
     * @param size Size of the model
     * @param faceName Name of the face ("front", "back", "left", "right", "top", "bottom")
     * @param color Color for the face
     */
    private void drawModelFace(Vector3f center, float size, String faceName, Color color) {
        float halfSize = size / 2.0f;
        Vector3f[] vertices = new Vector3f[4];
        
        switch (faceName) {
            case "front":
                vertices[0] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z + halfSize);
                vertices[1] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize);
                vertices[2] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize);
                vertices[3] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize);
                break;
            case "back":
                vertices[0] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z - halfSize);
                vertices[1] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z - halfSize);
                vertices[2] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z - halfSize);
                vertices[3] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize);
                break;
            case "left":
                vertices[0] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z - halfSize);
                vertices[1] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z + halfSize);
                vertices[2] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize);
                vertices[3] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z - halfSize);
                break;
            case "right":
                vertices[0] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize);
                vertices[1] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z - halfSize);
                vertices[2] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize);
                vertices[3] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize);
                break;
            case "top":
                vertices[0] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z + halfSize);
                vertices[1] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z + halfSize);
                vertices[2] = new Vector3f(center.x + halfSize, center.y + halfSize, center.z - halfSize);
                vertices[3] = new Vector3f(center.x - halfSize, center.y + halfSize, center.z - halfSize);
                break;
            case "bottom":
                vertices[0] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z - halfSize);
                vertices[1] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z - halfSize);
                vertices[2] = new Vector3f(center.x + halfSize, center.y - halfSize, center.z + halfSize);
                vertices[3] = new Vector3f(center.x - halfSize, center.y - halfSize, center.z + halfSize);
                break;
        }
        
        drawCubeFace(vertices, color);
    }
    
    /**
     * Renders a wireframe model representation.
     * 
     * @param center Center of the model
     * @param size Size of the model
     * @param color Wireframe color
     */
    private void renderWireframeModel(Vector3f center, float size, Color color) {
        drawWireframeCube3D(center, size, color);
    }
    
    /**
     * Renders model information and metadata.
     * 
     * @param model The model being rendered
     * @param center Center position of the model
     * @param size Size of the model
     */
    private void renderModelInfo(StonebreakModel model, Vector3f center, float size) {
        // Position info label below the model
        Vector3f infoPos = new Vector3f(center.x, center.y - size * 0.8f, center.z);
        Vector3f screenInfoPos = new Vector3f();
        
        if (project3DTo2D(infoPos, screenInfoPos)) {
            graphicsContext.setFill(Color.WHITE);
            graphicsContext.setFont(javafx.scene.text.Font.font("Arial", 11));
            
            String modelName = model.getVariantName() != null ? model.getVariantName() : "Unknown Model";
            String variant = getCurrentTextureVariant() != null ? getCurrentTextureVariant() : "default";
            
            graphicsContext.fillText("Model: " + modelName, screenInfoPos.x - 40, screenInfoPos.y);
            graphicsContext.fillText("Variant: " + variant, screenInfoPos.x - 40, screenInfoPos.y + 15);
        }
    }
    
    /**
     * Clears the enhanced Canvas with a professional background.
     */
    private void clearEnhancedCanvas() {
        if (graphicsContext != null) {
            // Create gradient background for professional appearance
            javafx.scene.paint.Stop[] stops = {
                new javafx.scene.paint.Stop(0, Color.rgb(45, 45, 45)),
                new javafx.scene.paint.Stop(1, Color.rgb(25, 25, 25))
            };
            
            javafx.scene.paint.LinearGradient gradient = new javafx.scene.paint.LinearGradient(
                0, 0, 0, 1, true, 
                javafx.scene.paint.CycleMethod.NO_CYCLE, 
                stops
            );
            
            graphicsContext.setFill(gradient);
            graphicsContext.fillRect(0, 0, canvasWidth, canvasHeight);
        }
    }
    
    /**
     * Validates Canvas rendering state and reports any issues.
     */
    private void validateCanvasRenderingState() {
        if (graphicsContext == null || renderCanvas == null) {
            logger.warn("Canvas components not properly initialized");
            return;
        }
        
        if (camera == null) {
            logger.warn("Camera not initialized for 3D projection");
            return;
        }
        
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            logger.warn("Invalid canvas dimensions: {}x{}", canvasWidth, canvasHeight);
            return;
        }
        
        // Validation successful - no logging needed for normal operation
    }

    /**
     * Gets the camera's current view matrix with proper formatting.
     * 
     * @return Formatted view matrix
     */
    private Matrix4f getFormattedViewMatrix() {
        return camera != null ? new Matrix4f(camera.getViewMatrix()) : new Matrix4f().identity();
    }
    
    /**
     * Gets the camera's current projection matrix with proper formatting.
     * 
     * @return Formatted projection matrix
     */
    private Matrix4f getFormattedProjectionMatrix() {
        return new Matrix4f(projectionMatrix);
    }
    
    /**
     * Applies optimized rendering settings based on performance requirements.
     */
    private void applyRenderingOptimizations() {
        if (performanceOptimizer != null) {
            performanceOptimizer.applyQualitySettings();
        }
        
        // Optimize graphics context settings
        if (graphicsContext != null) {
            // Enable antialiasing for smoother lines
            graphicsContext.setImageSmoothing(true);
            
            // Set line cap for better appearance
            graphicsContext.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            graphicsContext.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        }
    }
    
    /**
     * Renders enhanced status information in the viewport.
     */
    private void renderEnhancedStatusInfo() {
        if (graphicsContext == null) return;
        
        // Render viewport status in bottom-left corner
        double x = 10;
        double y = canvasHeight - 60;
        
        graphicsContext.setFill(Color.LIGHTGRAY);
        graphicsContext.setFont(javafx.scene.text.Font.font("Arial", 10));
        
        // Camera info
        if (camera != null) {
            graphicsContext.fillText(String.format("Camera: %.1f°, %.1f°, dist=%.1f", 
                camera.getAzimuth(), camera.getElevation(), camera.getDistance()), x, y);
            y += 12;
        }
        
        // Rendering mode
        String mode = wireframeMode.get() ? "Wireframe" : "Solid";
        graphicsContext.fillText("Mode: " + mode, x, y);
        y += 12;
        
        // Canvas info
        graphicsContext.fillText(String.format("Canvas: %.0fx%.0f", canvasWidth, canvasHeight), x, y);
    }
    
    /**
     * Updates the enhanced Canvas rendering with latest viewport state.
     */
    private void updateEnhancedCanvasRendering() {
        // Apply latest rendering optimizations
        applyRenderingOptimizations();
        
        // Update projection matrix if needed
        if (camera != null && canvasSizeChanged) {
            updateProjectionMatrix();
            canvasSizeChanged = false;
        }
        
        // Request a new render frame
        requestRender();
    }
    
    /**
     * Renders performance overlay using Canvas 2D API.
     */
    private void renderPerformanceOverlayCanvas() {
        if (graphicsContext == null) {
            return;
        }
        
        try {
            // Set up overlay rendering
            graphicsContext.setFill(Color.YELLOW);
            graphicsContext.setStroke(Color.BLACK);
            graphicsContext.setLineWidth(1.0);
            
            double x = 10;
            double y = 20;
            double lineHeight = 15;
            
            // Render basic performance info
            graphicsContext.fillText(String.format("FPS: %.1f", currentFPS), x, y);
            y += lineHeight;
            
            graphicsContext.fillText(String.format("Frames: %d", frameCount.get()), x, y);
            y += lineHeight;
            
            graphicsContext.fillText(String.format("Errors: %d", errorCount.get()), x, y);
            y += lineHeight;
            
            // Add performance optimizer info if available
            if (performanceOptimizer != null) {
                List<String> overlayLines = performanceOptimizer.getOverlayText();
                for (String line : overlayLines) {
                    if (y > renderCanvas.getHeight() - 20) break; // Don't go off screen
                    graphicsContext.fillText(line, x, y);
                    y += lineHeight;
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error rendering performance overlay on canvas", e);
        }
    }
    
    /**
     * Provides detailed Canvas rendering statistics.
     * 
     * @return Canvas rendering statistics
     */
    private Map<String, Object> getCanvasRenderingStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("canvasWidth", canvasWidth);
        stats.put("canvasHeight", canvasHeight);
        stats.put("cameraInitialized", camera != null);
        stats.put("modelLoaded", currentModel != null);
        stats.put("wireframeMode", wireframeMode.get());
        stats.put("gridVisible", gridVisible.get());
        stats.put("axesVisible", axesVisible.get());
        stats.put("textureVariant", getCurrentTextureVariant());
        return stats;
    }
    
    // ===== INPUT HANDLING METHODS =====
    
    /**
     * Handles mouse press events for camera control.
     */
    private void handleMousePressed(MouseEvent event) {
        if (!cameraControlsEnabled.get() || camera == null) {
            return;
        }
        
        // Request focus for keyboard events
        requestFocus();
        
        if (event.getButton() == MouseButton.PRIMARY) {
            leftMousePressed = true;
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            event.consume();
        } else if (event.getButton() == MouseButton.SECONDARY) {
            rightMousePressed = true;
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            event.consume();
        }
    }
    
    /**
     * Handles mouse drag events for camera rotation and panning.
     */
    private void handleMouseDragged(MouseEvent event) {
        if (!cameraControlsEnabled.get() || camera == null) {
            return;
        }
        
        double deltaX = event.getX() - lastMouseX;
        double deltaY = event.getY() - lastMouseY;
        
        if (leftMousePressed && !rightMousePressed) {
            // Left mouse button: camera rotation
            camera.rotate((float) deltaX, (float) deltaY);
            requestRender();
        } else if (rightMousePressed && !leftMousePressed) {
            // Right mouse button: camera panning
            camera.pan((float) deltaX, (float) deltaY);
            requestRender();
        }
        
        lastMouseX = event.getX();
        lastMouseY = event.getY();
        event.consume();
    }
    
    /**
     * Handles mouse release events.
     */
    private void handleMouseReleased(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            leftMousePressed = false;
        } else if (event.getButton() == MouseButton.SECONDARY) {
            rightMousePressed = false;
        }
        event.consume();
    }
    
    /**
     * Handles mouse scroll events for camera zooming.
     */
    private void handleMouseScroll(ScrollEvent event) {
        if (!cameraControlsEnabled.get() || camera == null) {
            return;
        }
        
        double scrollDelta = event.getDeltaY() / 120.0; // Normalize scroll wheel input
        camera.zoom((float) scrollDelta);
        requestRender();
        event.consume();
    }
    
    /**
     * Handles keyboard events for camera presets and controls.
     */
    private void handleKeyPressed(KeyEvent event) {
        if (!cameraControlsEnabled.get() || camera == null) {
            return;
        }
        
        KeyCode code = event.getCode();
        
        // Camera preset shortcuts (1-7 for standard views)
        switch (code) {
            case DIGIT1:
                camera.applyPreset(ArcBallCamera.CameraPreset.FRONT);
                requestRender();
                event.consume();
                break;
            case DIGIT2:
                camera.applyPreset(ArcBallCamera.CameraPreset.BACK);
                requestRender();
                event.consume();
                break;
            case DIGIT3:
                camera.applyPreset(ArcBallCamera.CameraPreset.LEFT);
                requestRender();
                event.consume();
                break;
            case DIGIT4:
                camera.applyPreset(ArcBallCamera.CameraPreset.RIGHT);
                requestRender();
                event.consume();
                break;
            case DIGIT5:
                camera.applyPreset(ArcBallCamera.CameraPreset.TOP);
                requestRender();
                event.consume();
                break;
            case DIGIT6:
                camera.applyPreset(ArcBallCamera.CameraPreset.BOTTOM);
                requestRender();
                event.consume();
                break;
            case DIGIT7:
                camera.applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);
                requestRender();
                event.consume();
                break;
            case R:
                // Reset camera to default position
                camera.reset();
                requestRender();
                event.consume();
                break;
            case F:
                // Fit camera to current model (if available)
                if (currentModel != null) {
                    fitCameraToModel();
                    requestRender();
                }
                event.consume();
                break;
        }
    }
    
    // ===== CAMERA UTILITY METHODS =====
    
    /**
     * Fits the camera to optimally view the current model.
     */
    private void fitCameraToModel() {
        if (camera == null || currentModel == null) {
            return;
        }
        
        // For now, use a simple bounding box calculation
        // In a real implementation, this would get the actual model bounds
        Vector3f min = new Vector3f(-1, -1, -1);
        Vector3f max = new Vector3f(1, 1, 1);
        
        camera.frameObject(min, max);
        logger.debug("Camera fitted to model bounds");
    }
    
    /**
     * Gets the current camera instance.
     * 
     * @return Current ArcBall camera, or null if not initialized
     */
    public ArcBallCamera getCamera() {
        return camera;
    }
    
    /**
     * Applies a camera preset programmatically.
     * 
     * @param preset The camera preset to apply
     */
    public void applyCameraPreset(ArcBallCamera.CameraPreset preset) {
        if (camera != null) {
            camera.applyPreset(preset);
            requestRender();
        }
    }
    
    /**
     * Resets the camera to its default position.
     */
    public void resetCamera() {
        if (camera != null) {
            camera.reset();
            requestRender();
        }
    }
    
    /**
     * Enables or disables camera controls.
     * 
     * @param enabled True to enable camera controls, false to disable
     */
    public void setCameraControlsEnabled(boolean enabled) {
        cameraControlsEnabled.set(enabled);
    }
    
    /**
     * Gets whether camera controls are currently enabled.
     * 
     * @return True if camera controls are enabled
     */
    public boolean areCameraControlsEnabled() {
        return cameraControlsEnabled.get();
    }
    
    /**
     * Gets the camera controls enabled property for binding.
     * 
     * @return Camera controls enabled property
     */
    public BooleanProperty cameraControlsEnabledProperty() {
        return cameraControlsEnabled;
    }
    
    /**
     * Updates frame timing statistics.
     */
    private void updateFrameTiming() {
        long currentTime = System.currentTimeMillis();
        frameCount.incrementAndGet();
        
        // Update FPS every second
        if (currentTime - lastFPSUpdate.get() >= 1000) {
            long frames = frameCount.getAndSet(0);
            currentFPS = frames; // Simple FPS calculation
            lastFPSUpdate.set(currentTime);
        }
        
        lastFrameTime = currentTime;
    }
    
    /**
     * Handles initialization errors with graceful degradation.
     */
    private void handleInitializationError(Throwable error) {
        Platform.runLater(() -> {
            logger.error("Viewport initialization failed - entering degraded mode", error);
            // Could show error overlay or fallback UI here
        });
    }
    
    /**
     * Handles rendering errors with recovery attempts.
     */
    private void handleRenderingError(Throwable error) {
        // For now, just log the error
        // In production, could implement error recovery or fallback rendering
        logger.warn("Rendering error occurred, continuing with next frame", error);
    }
    
    /**
     * Requests a render update using enhanced Canvas rendering.
     */
    public void requestRender() {
        if (renderingEnabled.get() && !disposed.get()) {
            Platform.runLater(() -> {
                if (!disposed.get() && graphicsContext != null) {
                    renderEnhancedCanvasContent();
                }
            });
        }
    }
    
    /**
     * Sets the current model to display in the viewport.
     * 
     * @param model The model to display
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        // Prepare the model for rendering if model renderer is available
        if (model != null && modelRenderer != null) {
            try {
                boolean prepared = modelRenderer.prepareModel(model);
                if (!prepared) {
                    System.err.println("Failed to prepare model for rendering: OpenGL context not available");
                    // Don't continue with rendering if model preparation failed
                    return;
                }
            } catch (Exception e) {
                System.err.println("Failed to prepare model for rendering: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }
        
        requestRender();
    }
    
    /**
     * Gets the current model being displayed.
     * 
     * @return The current model, or null if none is set
     */
    public StonebreakModel getCurrentModel() {
        return currentModel;
    }
    
    /**
     * Gets rendering statistics for monitoring and debugging.
     * 
     * @return Current rendering statistics
     */
    public RenderingStatistics getStatistics() {
        return new RenderingStatistics(
            frameCount.get(),
            currentFPS,
            lastFrameTime,
            errorCount.get(),
            initialized.get(),
            renderingEnabled.get(),
            disposed.get(),
            bufferManager != null ? bufferManager.getStatistics() : null
        );
    }
    
    /**
     * Gets detailed performance statistics from the performance optimizer.
     * 
     * @return Performance statistics, or null if optimizer not available
     */
    public PerformanceOptimizer.PerformanceStatistics getPerformanceStatistics() {
        return performanceOptimizer != null ? performanceOptimizer.getStatistics() : null;
    }
    
    /**
     * Gets a compact performance summary suitable for UI display.
     * 
     * @return Performance summary, or null if optimizer not available
     */
    public PerformanceOptimizer.PerformanceSummary getPerformanceSummary() {
        return performanceOptimizer != null ? performanceOptimizer.getSummary() : null;
    }
    
    /**
     * Gets the performance optimizer instance for advanced configuration.
     * 
     * @return Performance optimizer instance, or null if not initialized
     */
    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceOptimizer;
    }
    
    /**
     * Disposes of all resources and shuts down the viewport.
     */
    public void dispose() {
        if (disposed.getAndSet(true)) {
            return; // Already disposed
        }
        
        logger.info("Disposing OpenMason 3D Viewport");
        
        renderingEnabled.set(false);
        
        // Enhanced Canvas cleanup
        if (renderCanvas != null) {
            try {
                // Clear canvas
                if (graphicsContext != null) {
                    graphicsContext.clearRect(0, 0, canvasWidth, canvasHeight);
                }
                renderCanvas = null;
                graphicsContext = null;
            } catch (Exception e) {
                logger.error("Error disposing Canvas resources", e);
            }
        }
        
        // Cleanup ModelRenderer
        if (modelRenderer != null) {
            try {
                modelRenderer.close();
            } catch (Exception e) {
                logger.error("Error disposing ModelRenderer", e);
            }
        }
        
        // Cleanup performance optimizer
        if (performanceOptimizer != null) {
            performanceOptimizer.setEnabled(false);
            performanceOptimizer = null;
        }
        
        // BufferManager cleanup is handled automatically via shutdown hook
        
        logger.info("Enhanced Canvas-based OpenMason 3D Viewport disposed");
    }
    
    // Property accessors
    public StringProperty currentTextureVariantProperty() { return currentTextureVariant; }
    public String getCurrentTextureVariant() { return currentTextureVariant.get(); }
    public void setCurrentTextureVariant(String variant) { currentTextureVariant.set(variant); }
    
    public BooleanProperty wireframeModeProperty() { return wireframeMode; }
    public boolean isWireframeMode() { return wireframeMode.get(); }
    public void setWireframeMode(boolean enabled) { wireframeMode.set(enabled); }
    
    public BooleanProperty gridVisibleProperty() { return gridVisible; }
    public boolean isGridVisible() { return gridVisible.get(); }
    public void setGridVisible(boolean visible) { gridVisible.set(visible); }
    
    public BooleanProperty axesVisibleProperty() { return axesVisible; }
    public boolean isAxesVisible() { return axesVisible.get(); }
    public void setAxesVisible(boolean visible) { axesVisible.set(visible); }
    
    // Status accessors
    public boolean isInitialized() { return initialized.get(); }
    public boolean isRenderingEnabled() { return renderingEnabled.get(); }
    public boolean isDisposed() { return disposed.get(); }
    public Throwable getLastError() { return lastError; }
    public long getErrorCount() { return errorCount.get(); }
    
    // Performance monitoring accessors
    public BooleanProperty performanceOverlayEnabledProperty() { return performanceOverlayEnabled; }
    public boolean isPerformanceOverlayEnabled() { return performanceOverlayEnabled.get(); }
    public void setPerformanceOverlayEnabled(boolean enabled) { performanceOverlayEnabled.set(enabled); }
    
    public BooleanProperty adaptiveQualityEnabledProperty() { return adaptiveQualityEnabled; }
    public boolean isAdaptiveQualityEnabled() { return adaptiveQualityEnabled.get(); }
    public void setAdaptiveQualityEnabled(boolean enabled) { adaptiveQualityEnabled.set(enabled); }
    
    /**
     * Manually sets the MSAA level, disabling adaptive quality.
     * 
     * @param level MSAA level (0=off, 1=2x, 2=4x, 3=8x)
     */
    public void setMSAALevel(int level) {
        if (performanceOptimizer != null) {
            performanceOptimizer.setMSAALevel(level);
            requestRender();
        }
    }
    
    /**
     * Manually sets the render scale, disabling adaptive quality.
     * 
     * @param scale Render scale (0.5 to 2.0)
     */
    public void setRenderScale(float scale) {
        if (performanceOptimizer != null) {
            performanceOptimizer.setRenderScale(scale);
            requestRender();
        }
    }
    
    /**
     * Gets the current MSAA level.
     * 
     * @return Current MSAA level, or -1 if optimizer not available
     */
    public int getCurrentMSAALevel() {
        return performanceOptimizer != null ? performanceOptimizer.getCurrentMSAALevel() : -1;
    }
    
    /**
     * Gets the current render scale.
     * 
     * @return Current render scale, or 1.0f if optimizer not available
     */
    public float getCurrentRenderScale() {
        return performanceOptimizer != null ? performanceOptimizer.getCurrentRenderScale() : 1.0f;
    }
    
    /**
     * Enables or disables performance debug mode.
     * 
     * @param debug True to enable debug mode
     */
    public void setPerformanceDebugMode(boolean debug) {
        if (performanceOptimizer != null) {
            performanceOptimizer.setDebugMode(debug);
        }
    }
    
    /**
     * Statistics class for rendering performance monitoring.
     */
    public static class RenderingStatistics {
        private final long frameCount;
        private final double currentFPS;
        private final long lastFrameTime;
        private final long errorCount;
        private final boolean initialized;
        private final boolean renderingEnabled;
        private final boolean disposed;
        private final Object bufferManagerStats;
        
        public RenderingStatistics(long frameCount, double currentFPS, long lastFrameTime,
                                 long errorCount, boolean initialized, boolean renderingEnabled,
                                 boolean disposed, Object bufferManagerStats) {
            this.frameCount = frameCount;
            this.currentFPS = currentFPS;
            this.lastFrameTime = lastFrameTime;
            this.errorCount = errorCount;
            this.initialized = initialized;
            this.renderingEnabled = renderingEnabled;
            this.disposed = disposed;
            this.bufferManagerStats = bufferManagerStats;
        }
        
        public long getFrameCount() { return frameCount; }
        public double getCurrentFPS() { return currentFPS; }
        public long getLastFrameTime() { return lastFrameTime; }
        public long getErrorCount() { return errorCount; }
        public boolean isInitialized() { return initialized; }
        public boolean isRenderingEnabled() { return renderingEnabled; }
        public boolean isDisposed() { return disposed; }
        public Object getBufferManagerStats() { return bufferManagerStats; }
        
        @Override
        public String toString() {
            return String.format("RenderingStatistics{fps=%.1f, frames=%d, errors=%d, initialized=%s, rendering=%s, disposed=%s}",
                    currentFPS, frameCount, errorCount, initialized, renderingEnabled, disposed);
        }
    }
}