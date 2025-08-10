package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;
import com.openmason.model.StonebreakModel;
import com.openmason.rendering.ModelRenderer;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;

/**
 * Dear ImGui integration manager for 3D viewport rendering.
 * 
 * This class replaces the JavaFX-based OpenMason3DViewport with a Dear ImGui-based
 * viewport that renders OpenGL content to a framebuffer and displays it as a texture
 * in an ImGui window with full docking support.
 * 
 * Key features:
 * - Off-screen OpenGL rendering to framebuffer
 * - Dear ImGui window with texture display
 * - Docking system integration
 * - Mouse and keyboard input handling via GLFW
 * - Viewport resizing and aspect ratio management
 * - Debug overlays and information panels
 * - Integration with existing camera and model systems
 */
public class ImGuiViewportManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiViewportManager.class);
    
    // Core rendering components
    private OpenGLFrameBuffer framebuffer;
    private ModelRenderer modelRenderer;
    private ArcBallCamera camera;
    
    // Viewport state
    private int viewportWidth = 800;
    private int viewportHeight = 600;
    private boolean viewportOpen = true;
    private boolean viewportFocused = false;
    private boolean viewportHovered = false;
    
    // Model and rendering state
    private StonebreakModel currentModel;
    private String currentTextureVariant = "default";
    private boolean wireframeMode = false;
    private boolean showGrid = true;
    private boolean showAxes = true;
    private boolean showDebugInfo = false;
    
    // Performance monitoring
    private long frameCount = 0;
    private double lastFPS = 0.0;
    private long lastFrameTime = System.nanoTime();
    
    // Input handling
    private GLFWInputHandler inputHandler;
    
    // UI state
    private final ImBoolean viewportOpenFlag = new ImBoolean(true);
    private final ImVec2 contentRegionAvail = new ImVec2();
    
    /**
     * Initialize the Dear ImGui viewport manager.
     */
    public ImGuiViewportManager() {
        logger.info("Initializing Dear ImGui viewport manager");
        
        try {
            // Initialize camera
            camera = new ArcBallCamera();
            camera.setDistance(10.0f);
            camera.setTarget(new Vector3f(0, 0, 0));
            
            // Initialize framebuffer
            framebuffer = new OpenGLFrameBuffer(viewportWidth, viewportHeight);
            
            // Initialize model renderer
            modelRenderer = new ModelRenderer("ImGuiViewport");
            
            // Initialize input handler
            inputHandler = new GLFWInputHandler(camera);
            
            logger.info("Dear ImGui viewport manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize Dear ImGui viewport manager", e);
            throw new RuntimeException("ImGui viewport initialization failed", e);
        }
    }
    
    /**
     * Render the Dear ImGui viewport window.
     * This should be called during the ImGui render loop.
     */
    public void render() {
        if (!viewportOpenFlag.get()) {
            viewportOpen = false;
            return;
        }
        
        // Begin viewport window with docking support
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 0, 0);
        
        int windowFlags = ImGuiWindowFlags.NoCollapse;
        if (ImGui.begin("3D Viewport", viewportOpenFlag, windowFlags)) {
            
            // Get available content region
            ImGui.getContentRegionAvail(contentRegionAvail);
            int newWidth = (int) contentRegionAvail.x;
            int newHeight = (int) contentRegionAvail.y;
            
            // Update viewport state
            viewportFocused = ImGui.isWindowFocused();
            viewportHovered = ImGui.isWindowHovered();
            
            // Handle viewport resizing
            if (newWidth > 0 && newHeight > 0 && (newWidth != viewportWidth || newHeight != viewportHeight)) {
                resizeViewport(newWidth, newHeight);
            }
            
            // Render 3D content to framebuffer
            render3DContent();
            
            // Display framebuffer texture in ImGui
            if (framebuffer != null && framebuffer.isInitialized()) {
                int textureID = framebuffer.getColorTextureID();
                
                // Flip texture vertically for ImGui display (OpenGL uses bottom-left origin)
                ImGui.image(textureID, viewportWidth, viewportHeight, 0, 1, 1, 0);
                
                // Handle input if viewport is hovered
                if (viewportHovered) {
                    handleViewportInput();
                }
            }
            
            // Render debug overlays
            if (showDebugInfo) {
                renderDebugOverlay();
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
        
        // Update performance metrics
        updatePerformanceMetrics();
    }
    
    /**
     * Render 3D content to the framebuffer.
     */
    private void render3DContent() {
        if (framebuffer == null || !framebuffer.isInitialized()) {
            return;
        }
        
        try {
            // Bind framebuffer for off-screen rendering
            framebuffer.bind();
            
            // Clear with dark gray background
            framebuffer.clear(0.2f, 0.2f, 0.2f, 1.0f, 1.0f);
            
            // Enable depth testing
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            
            // Enable blending for transparency
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            
            // Set up matrices
            Matrix4f projectionMatrix = new Matrix4f();
            float aspect = (float) viewportWidth / viewportHeight;
            projectionMatrix.perspective((float) Math.toRadians(60.0f), aspect, 0.1f, 1000.0f);
            
            Matrix4f viewMatrix = camera.getViewMatrix();
            
            // Set wireframe mode if enabled
            if (wireframeMode) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            } else {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }
            
            // Render grid if enabled
            if (showGrid) {
                renderGrid(projectionMatrix, viewMatrix);
            }
            
            // Render axes if enabled
            if (showAxes) {
                renderAxes(projectionMatrix, viewMatrix);
            }
            
            // Render current model if loaded
            if (currentModel != null && modelRenderer != null) {
                renderModel(projectionMatrix, viewMatrix);
            }
            
            // Restore fill mode
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            
            // Unbind framebuffer
            framebuffer.unbind();
            
            frameCount++;
            
        } catch (Exception e) {
            logger.error("Error rendering 3D content", e);
            framebuffer.unbind();
        }
    }
    
    /**
     * Render grid lines in the 3D scene.
     */
    private void renderGrid(Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        try {
            // Enable basic OpenGL line rendering
            glLineWidth(1.0f);
            glColor3f(0.3f, 0.3f, 0.3f); // Dark gray grid lines
            
            // Set up matrices
            glMatrixMode(GL_PROJECTION);
            glPushMatrix();
            glLoadMatrixf(projectionMatrix.get(new float[16]));
            
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadMatrixf(viewMatrix.get(new float[16]));
            
            // Render grid lines
            glBegin(GL_LINES);
            
            // Grid parameters
            int gridSize = 20;
            float spacing = 1.0f;
            float extent = gridSize * spacing;
            
            // Vertical lines (parallel to Z-axis)
            for (int i = -gridSize; i <= gridSize; i++) {
                float x = i * spacing;
                glVertex3f(x, 0, -extent);
                glVertex3f(x, 0, extent);
            }
            
            // Horizontal lines (parallel to X-axis)
            for (int i = -gridSize; i <= gridSize; i++) {
                float z = i * spacing;
                glVertex3f(-extent, 0, z);
                glVertex3f(extent, 0, z);
            }
            
            glEnd();
            
            // Restore matrices
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            
        } catch (Exception e) {
            logger.error("Error rendering grid", e);
        }
    }
    
    /**
     * Render coordinate axes in the 3D scene.
     */
    private void renderAxes(Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        try {
            // Enable line rendering with thicker lines for axes
            glLineWidth(3.0f);
            
            // Set up matrices
            glMatrixMode(GL_PROJECTION);
            glPushMatrix();
            glLoadMatrixf(projectionMatrix.get(new float[16]));
            
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadMatrixf(viewMatrix.get(new float[16]));
            
            glBegin(GL_LINES);
            
            // X-axis (Red)
            glColor3f(1.0f, 0.0f, 0.0f);
            glVertex3f(0, 0, 0);
            glVertex3f(3.0f, 0, 0);
            
            // Y-axis (Green)
            glColor3f(0.0f, 1.0f, 0.0f);
            glVertex3f(0, 0, 0);
            glVertex3f(0, 3.0f, 0);
            
            // Z-axis (Blue)
            glColor3f(0.0f, 0.0f, 1.0f);
            glVertex3f(0, 0, 0);
            glVertex3f(0, 0, 3.0f);
            
            glEnd();
            
            // Add axis end caps (small spheres)
            float radius = 0.1f;
            int segments = 8;
            
            // X-axis cap (Red)
            glColor3f(1.0f, 0.0f, 0.0f);
            glPushMatrix();
            glTranslatef(3.0f, 0, 0);
            renderSphere(radius, segments);
            glPopMatrix();
            
            // Y-axis cap (Green)
            glColor3f(0.0f, 1.0f, 0.0f);
            glPushMatrix();
            glTranslatef(0, 3.0f, 0);
            renderSphere(radius, segments);
            glPopMatrix();
            
            // Z-axis cap (Blue)
            glColor3f(0.0f, 0.0f, 1.0f);
            glPushMatrix();
            glTranslatef(0, 0, 3.0f);
            renderSphere(radius, segments);
            glPopMatrix();
            
            // Restore matrices
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            
        } catch (Exception e) {
            logger.error("Error rendering axes", e);
        }
    }
    
    /**
     * Render the current model.
     */
    private void renderModel(Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        try {
            if (currentModel == null) {
                return;
            }
            
            // Set up matrices
            glMatrixMode(GL_PROJECTION);
            glPushMatrix();
            glLoadMatrixf(projectionMatrix.get(new float[16]));
            
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadMatrixf(viewMatrix.get(new float[16]));
            
            // For now, render a placeholder cube to represent the model
            // TODO: Integrate with actual Stonebreak model vertex data
            renderPlaceholderCube();
            
            // Restore matrices
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            
            logger.trace("Rendered model: {}", currentModel.getVariantName());
            
        } catch (Exception e) {
            logger.error("Error rendering model", e);
        }
    }
    
    /**
     * Handle viewport input events.
     */
    private void handleViewportInput() {
        try {
            if (inputHandler != null && viewportFocused) {
                // Update input handler with current viewport state
                inputHandler.setViewportBounds(0, 0, viewportWidth, viewportHeight);
                
                // Input handling is done via GLFW callbacks in GLFWInputHandler
            }
        } catch (Exception e) {
            logger.error("Error handling viewport input", e);
        }
    }
    
    /**
     * Render debug information overlay.
     */
    private void renderDebugOverlay() {
        try {
            ImGui.setNextWindowPos(10, 30);
            ImGui.setNextWindowSize(250, 150);
            
            int overlayFlags = ImGuiWindowFlags.NoTitleBar | 
                              ImGuiWindowFlags.NoResize | 
                              ImGuiWindowFlags.NoMove | 
                              ImGuiWindowFlags.NoCollapse |
                              ImGuiWindowFlags.AlwaysAutoResize;
            
            if (ImGui.begin("Viewport Debug", overlayFlags)) {
                ImGui.text(String.format("Viewport: %dx%d", viewportWidth, viewportHeight));
                ImGui.text(String.format("FPS: %.1f", lastFPS));
                ImGui.text(String.format("Frames: %d", frameCount));
                ImGui.text(String.format("Focused: %s", viewportFocused ? "Yes" : "No"));
                ImGui.text(String.format("Hovered: %s", viewportHovered ? "Yes" : "No"));
                
                if (currentModel != null) {
                    ImGui.separator();
                    try {
                        String modelName = currentModel.getVariantName();
                        ImGui.text(String.format("Model: %s", modelName != null ? modelName : "Unknown"));
                        ImGui.text(String.format("Texture: %s", currentTextureVariant));
                    } catch (Exception e) {
                        ImGui.text("Model: Error loading info");
                        logger.warn("Error getting model info for debug overlay", e);
                    }
                }
                
                if (camera != null) {
                    ImGui.separator();
                    try {
                        Vector3f target = camera.getTarget();
                        ImGui.text(String.format("Camera Target: (%.1f, %.1f, %.1f)", target.x, target.y, target.z));
                        ImGui.text(String.format("Camera Distance: %.1f", camera.getDistance()));
                    } catch (Exception e) {
                        ImGui.text("Camera: Error loading info");
                        logger.warn("Error getting camera info for debug overlay", e);
                    }
                }
            }
            ImGui.end();
        } catch (Exception e) {
            logger.error("Error rendering debug overlay", e);
        }
    }
    
    /**
     * Resize the viewport to new dimensions.
     */
    private void resizeViewport(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            return;
        }
        
        logger.debug("Resizing viewport from {}x{} to {}x{}", viewportWidth, viewportHeight, newWidth, newHeight);
        
        viewportWidth = newWidth;
        viewportHeight = newHeight;
        
        // Resize framebuffer
        if (framebuffer != null) {
            framebuffer.resize(newWidth, newHeight);
        }
        
        // Update camera aspect ratio
        if (camera != null) {
            float aspect = (float) newWidth / newHeight;
            // Camera will use this aspect ratio in the next render
        }
    }
    
    /**
     * Update performance metrics.
     */
    private void updatePerformanceMetrics() {
        long currentTime = System.nanoTime();
        if (lastFrameTime > 0) {
            long frameTime = currentTime - lastFrameTime;
            double fps = 1_000_000_000.0 / frameTime;
            // Smooth FPS calculation
            lastFPS = lastFPS * 0.9 + fps * 0.1;
        }
        lastFrameTime = currentTime;
    }
    
    // ========== Public API ==========
    
    /**
     * Set the current model to display.
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        logger.info("Current model set: {}", model != null ? model.getVariantName() : "null");
    }
    
    /**
     * Get the current model.
     */
    public StonebreakModel getCurrentModel() {
        return currentModel;
    }
    
    /**
     * Set the current texture variant.
     */
    public void setTextureVariant(String variant) {
        this.currentTextureVariant = variant != null ? variant : "default";
        logger.debug("Texture variant set: {}", currentTextureVariant);
    }
    
    /**
     * Get the current texture variant.
     */
    public String getTextureVariant() {
        return currentTextureVariant;
    }
    
    /**
     * Set wireframe rendering mode.
     */
    public void setWireframeMode(boolean enabled) {
        this.wireframeMode = enabled;
        logger.debug("Wireframe mode: {}", enabled);
    }
    
    /**
     * Check if wireframe mode is enabled.
     */
    public boolean isWireframeMode() {
        return wireframeMode;
    }
    
    /**
     * Set grid visibility.
     */
    public void setShowGrid(boolean show) {
        this.showGrid = show;
        logger.debug("Show grid: {}", show);
    }
    
    /**
     * Check if grid is visible.
     */
    public boolean isShowGrid() {
        return showGrid;
    }
    
    /**
     * Set axes visibility.
     */
    public void setShowAxes(boolean show) {
        this.showAxes = show;
        logger.debug("Show axes: {}", show);
    }
    
    /**
     * Check if axes are visible.
     */
    public boolean isShowAxes() {
        return showAxes;
    }
    
    /**
     * Set debug information visibility.
     */
    public void setShowDebugInfo(boolean show) {
        this.showDebugInfo = show;
        logger.debug("Show debug info: {}", show);
    }
    
    /**
     * Check if debug information is visible.
     */
    public boolean isShowDebugInfo() {
        return showDebugInfo;
    }
    
    /**
     * Get the camera instance.
     */
    public ArcBallCamera getCamera() {
        return camera;
    }
    
    /**
     * Fit camera to current model.
     */
    public void fitCameraToModel() {
        try {
            if (camera != null && currentModel != null) {
                // TODO: Calculate model bounds and fit camera
                // For now, use default positioning that works well for most models
                camera.setDistance(15.0f);
                camera.setTarget(new Vector3f(0, 0, 0));
                logger.debug("Camera fitted to model: {}", currentModel.getVariantName());
            } else {
                logger.warn("Cannot fit camera to model - camera or model is null");
            }
        } catch (Exception e) {
            logger.error("Error fitting camera to model", e);
        }
    }
    
    /**
     * Reset camera to default position.
     */
    public void resetCamera() {
        try {
            if (camera != null) {
                camera.setDistance(10.0f);
                camera.setTarget(new Vector3f(0, 0, 0));
                camera.setOrientation(0.0f, 0.0f);
                logger.debug("Camera reset to default position");
            } else {
                logger.warn("Cannot reset camera - camera is null");
            }
        } catch (Exception e) {
            logger.error("Error resetting camera", e);
        }
    }
    
    /**
     * Check if viewport is open.
     */
    public boolean isViewportOpen() {
        return viewportOpen && viewportOpenFlag.get();
    }
    
    /**
     * Get viewport width.
     */
    public int getViewportWidth() {
        return viewportWidth;
    }
    
    /**
     * Get viewport height.
     */
    public int getViewportHeight() {
        return viewportHeight;
    }
    
    /**
     * Get current FPS.
     */
    public double getFPS() {
        return lastFPS;
    }
    
    /**
     * Get frame count.
     */
    public long getFrameCount() {
        return frameCount;
    }
    
    /**
     * Clean up resources.
     */
    public void dispose() {
        logger.info("Disposing Dear ImGui viewport manager");
        
        try {
            if (framebuffer != null) {
                framebuffer.cleanup();
                framebuffer = null;
            }
        } catch (Exception e) {
            logger.warn("Error disposing framebuffer", e);
        }
        
        try {
            if (inputHandler != null) {
                inputHandler.dispose();
                inputHandler = null;
            }
        } catch (Exception e) {
            logger.warn("Error disposing input handler", e);
        }
        
        try {
            if (modelRenderer != null) {
                // ModelRenderer might have its own cleanup method in the future
                modelRenderer = null;
            }
        } catch (Exception e) {
            logger.warn("Error disposing model renderer", e);
        }
        
        // Clear references
        currentModel = null;
        camera = null;
        
        logger.info("Dear ImGui viewport manager disposed");
    }
    
    // ========== Helper Rendering Methods ==========
    
    /**
     * Render a simple sphere using triangle strips.
     */
    private void renderSphere(float radius, int segments) {
        try {
            // Simple sphere rendering using triangle strips
            for (int i = 0; i < segments; i++) {
                float theta1 = (float) (i * Math.PI / segments);
                float theta2 = (float) ((i + 1) * Math.PI / segments);
                
                glBegin(GL_TRIANGLE_STRIP);
                for (int j = 0; j <= segments; j++) {
                    float phi = (float) (j * 2 * Math.PI / segments);
                    
                    // First vertex
                    float x1 = (float) (radius * Math.sin(theta1) * Math.cos(phi));
                    float y1 = (float) (radius * Math.cos(theta1));
                    float z1 = (float) (radius * Math.sin(theta1) * Math.sin(phi));
                    glVertex3f(x1, y1, z1);
                    
                    // Second vertex
                    float x2 = (float) (radius * Math.sin(theta2) * Math.cos(phi));
                    float y2 = (float) (radius * Math.cos(theta2));
                    float z2 = (float) (radius * Math.sin(theta2) * Math.sin(phi));
                    glVertex3f(x2, y2, z2);
                }
                glEnd();
            }
        } catch (Exception e) {
            logger.warn("Error rendering sphere", e);
        }
    }
    
    /**
     * Render a placeholder cube to represent a model.
     */
    private void renderPlaceholderCube() {
        try {
            glColor4f(0.6f, 0.8f, 1.0f, 0.8f); // Light blue, semi-transparent
            float size = 1.0f;
            
            glBegin(GL_QUADS);
            
            // Front face
            glVertex3f(-size, -size, size);
            glVertex3f(size, -size, size);
            glVertex3f(size, size, size);
            glVertex3f(-size, size, size);
            
            // Back face
            glVertex3f(-size, -size, -size);
            glVertex3f(-size, size, -size);
            glVertex3f(size, size, -size);
            glVertex3f(size, -size, -size);
            
            // Top face
            glVertex3f(-size, size, -size);
            glVertex3f(-size, size, size);
            glVertex3f(size, size, size);
            glVertex3f(size, size, -size);
            
            // Bottom face
            glVertex3f(-size, -size, -size);
            glVertex3f(size, -size, -size);
            glVertex3f(size, -size, size);
            glVertex3f(-size, -size, size);
            
            // Right face
            glVertex3f(size, -size, -size);
            glVertex3f(size, size, -size);
            glVertex3f(size, size, size);
            glVertex3f(size, -size, size);
            
            // Left face
            glVertex3f(-size, -size, -size);
            glVertex3f(-size, -size, size);
            glVertex3f(-size, size, size);
            glVertex3f(-size, size, -size);
            
            glEnd();
            
            // Render cube wireframe outline
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glColor3f(0.2f, 0.4f, 0.6f); // Darker blue outline
            glLineWidth(2.0f);
            
            glBegin(GL_QUADS);
            
            // Repeat all faces for wireframe
            glVertex3f(-size, -size, size);
            glVertex3f(size, -size, size);
            glVertex3f(size, size, size);
            glVertex3f(-size, size, size);
            
            glVertex3f(-size, -size, -size);
            glVertex3f(-size, size, -size);
            glVertex3f(size, size, -size);
            glVertex3f(size, -size, -size);
            
            glVertex3f(-size, size, -size);
            glVertex3f(-size, size, size);
            glVertex3f(size, size, size);
            glVertex3f(size, size, -size);
            
            glVertex3f(-size, -size, -size);
            glVertex3f(size, -size, -size);
            glVertex3f(size, -size, size);
            glVertex3f(-size, -size, size);
            
            glVertex3f(size, -size, -size);
            glVertex3f(size, size, -size);
            glVertex3f(size, size, size);
            glVertex3f(size, -size, size);
            
            glVertex3f(-size, -size, -size);
            glVertex3f(-size, -size, size);
            glVertex3f(-size, size, size);
            glVertex3f(-size, size, -size);
            
            glEnd();
            
            // Restore fill mode
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            
        } catch (Exception e) {
            logger.warn("Error rendering placeholder cube", e);
        }
    }
}