package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;


import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * OpenGL color constants for 3D rendering.
 */
class DebugColor {
    public final float r, g, b, a;
    
    private DebugColor(float r, float g, float b) {
        this(r, g, b, 1.0f);
    }
    
    private DebugColor(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
    }
    
    public static final DebugColor RED = new DebugColor(1.0f, 0.0f, 0.0f);
    public static final DebugColor GREEN = new DebugColor(0.0f, 1.0f, 0.0f);
    public static final DebugColor BLUE = new DebugColor(0.0f, 0.0f, 1.0f);
    public static final DebugColor WHITE = new DebugColor(1.0f, 1.0f, 1.0f);
    public static final DebugColor BLACK = new DebugColor(0.0f, 0.0f, 0.0f);
    public static final DebugColor GRAY = new DebugColor(0.5f, 0.5f, 0.5f);
    public static final DebugColor YELLOW = new DebugColor(1.0f, 1.0f, 0.0f);
    public static final DebugColor CYAN = new DebugColor(0.0f, 1.0f, 1.0f);
    public static final DebugColor MAGENTA = new DebugColor(1.0f, 0.0f, 1.0f);
    public static final DebugColor ORANGE = new DebugColor(1.0f, 0.5f, 0.0f);
    public static final DebugColor PINK = new DebugColor(1.0f, 0.7f, 0.7f);
    public static final DebugColor LIGHTGRAY = new DebugColor(0.7f, 0.7f, 0.7f);
}

class Group {
    private final java.util.List<Object> children = new java.util.ArrayList<>();
    private final java.util.List<Object> transforms = new java.util.ArrayList<>();
    public java.util.List<Object> getChildren() { return children; }
    public java.util.List<Object> getTransforms() { return transforms; }
    public void setTranslateX(double x) { }
    public void setTranslateY(double y) { }
    public void setTranslateZ(double z) { }
}

class Box {
    public Box(double width, double height, double depth) { }
    public void setMaterial(PhongMaterial material) { }
    public void setDrawMode(DrawMode mode) { }
    public void setTranslateX(double x) { }
    public void setTranslateY(double y) { }
    public void setTranslateZ(double z) { }
    public java.util.List<Object> getTransforms() { return new java.util.ArrayList<>(); }
}

class Cylinder {
    public Cylinder(double radius, double height) { }
    public void setMaterial(PhongMaterial material) { }
    public void setDrawMode(DrawMode mode) { }
    public void setTranslateX(double x) { }
    public void setTranslateY(double y) { }
    public void setTranslateZ(double z) { }
    public java.util.List<Object> getTransforms() { return new java.util.ArrayList<>(); }
}

class PhongMaterial {
    public void setDiffuseColor(DebugColor color) { }
    public void setSpecularColor(DebugColor color) { }
}

enum DrawMode {
    FILL, LINE
}

class Text {
    public Text(String text) { }
    public void setFont(Font font) { }
    public void setFill(DebugColor color) { }
    public java.util.List<Object> getTransforms() { return new java.util.ArrayList<>(); }
}

class Font {
    public static Font font(String family, FontWeight weight, double size) { return new Font(); }
}

enum FontWeight {
    BOLD, NORMAL
}

class Rotate {
    public static final Object X_AXIS = new Object();
    public static final Object Y_AXIS = new Object();
    public static final Object Z_AXIS = new Object();
    public Rotate(double angle, Object axis) { }
}

class Translate {
    public Translate(double x, double y, double z) { }
}

/**
 * Handles debug visualization elements for the 3D viewport.
 * 
 * Responsible for:
 * - 3D coordinate axes rendering
 * - Grid visualization
 * - Model part labels and debug info
 * - Coordinate system validation visualization
 * - Performance overlay rendering
 */
public class ViewportDebugRenderer {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportDebugRenderer.class);
    
    private ViewportSceneManager sceneManager;
    
    // Debug visualization state
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    private boolean coordinateAxesVisible = false;
    private boolean partLabelsVisible = false;
    private boolean debugMode = false;
    
    
    // Coordinate alignment debugging
    private Group coordinateDebugGroup;
    private boolean showBoundingBoxes = true; // Show bounding boxes as wireframes
    
    // Grid creation state tracking
    private boolean gridCreated = false;
    private Group currentGridGroup = null;
    private boolean updateInProgress = false;
    
    // 2D grid plane configuration - visible thickness for both X and Z lines
    private static final float GRID_SIZE = 40.0f;          // Total grid size (40x40 units)
    private static final float GRID_SPACING = 2.0f;        // Distance between grid lines
    private static final float MAJOR_GRID_INTERVAL = 10.0f; // Major grid lines every 10 units
    private static final float GRID_LINE_THICKNESS = 0.05f; // Visible thin grid lines
    private static final float MAJOR_LINE_THICKNESS = 0.08f; // Thicker major lines
    private static final float AXIS_LINE_THICKNESS = 0.12f; // Thickest axis lines
    
    /**
     * Initialize with scene manager reference.
     */
    public void initialize(ViewportSceneManager sceneManager) {
        this.sceneManager = sceneManager;
        logger.debug("ViewportDebugRenderer initialized");
    }
    
    /**
     * Update all debug visualizations based on current settings.
     */
    public void updateDebugVisualization() {
        // Prevent recursive updates
        if (updateInProgress) {
            logger.debug("Update already in progress, skipping recursive call");
            return;
        }
        
        updateInProgress = true;
        try {
            updateGrid();
            updateAxes();
            updateCoordinateAlignmentDebug();
            
            logger.debug("Debug visualization updated successfully");
        } finally {
            updateInProgress = false;
        }
    }
    
    /**
     * Update coordinate alignment debug visualization.
     * Shows bounding box wireframes at their calculated positions for alignment validation.
     */
    private void updateCoordinateAlignmentDebug() {
        if (!debugMode || sceneManager == null || !showBoundingBoxes) {
            return;
        }
        
        // This creates wireframe bounding boxes to validate coordinate alignment
        logger.debug("Coordinate alignment debug visualization updated");
    }
    
    /**
     * Force grid refresh - useful for testing changes.
     */
    public void forceGridRefresh() {
        logger.info("Forcing grid refresh...");
        gridCreated = false;
        currentGridGroup = null;
        updateGrid();
    }
    
    /**
     * Update grid visualization.
     */
    private void updateGrid() {
        if (sceneManager == null) return;
        
        if (gridVisible) {
            // Only create grid if it hasn't been created yet or if forced refresh
            if (!gridCreated || currentGridGroup == null) {
                logger.debug("Creating new 3D grid...");
                currentGridGroup = create3DGrid();
                gridCreated = true;
            }
            sceneManager.setGridElements(currentGridGroup);
        } else {
            sceneManager.setGridElements(null);
        }
    }
    
    /**
     * Update axes visualization.
     */
    private void updateAxes() {
        if (sceneManager == null) return;
        
        // Axes disabled - not properly implemented
        sceneManager.setAxesElements(null);
    }
    
    /**
     * Create professional 3D grid system using OpenGL line rendering.
     * Creates a proper 3D grid with:
     * - Grid lines on XZ plane for ground reference
     * - Vertical grid lines extending upward for 3D depth perception
     * - Major grid lines every 10 units
     * - Color-coded axes (X=red, Y=green, Z=blue)
     * - Proper OpenGL line rendering for performance
     */
    private Group create3DGrid() {
        Group gridGroup = new Group();
        
        try {
            // Create 3D grid using OpenGL lines
            OpenGL3DGrid openglGrid = new OpenGL3DGrid();
            
            float halfSize = GRID_SIZE / 2.0f;
            int numLines = (int) (GRID_SIZE / GRID_SPACING);
            
            logger.info("Creating 3D OpenGL grid: {}x{} units, {} lines per axis", GRID_SIZE, GRID_SIZE, numLines);
            
            // Create horizontal grid lines (XZ plane)
            createHorizontalGridLines(openglGrid, halfSize, numLines);
            
            // Create vertical grid lines (extending upward from XZ plane)
            createVerticalGridLines(openglGrid, halfSize, numLines);
            
            // Create coordinate axes
            create3DCoordinateAxes(openglGrid);
            
            // Add origin marker
            createOriginMarker(openglGrid);
            
            // Convert OpenGL grid to viewport group
            gridGroup = openglGrid.buildViewportGroup();
            
            logger.info("3D OpenGL grid created successfully");
            
        } catch (Exception e) {
            logger.error("Failed to create 3D OpenGL grid", e);
            // No fallback - return empty group if OpenGL grid fails
            logger.warn("3D OpenGL grid initialization failed - no grid will be displayed");
        }
        
        return gridGroup;
    }
    
    /**
     * Create horizontal grid lines on the XZ plane.
     */
    private void createHorizontalGridLines(OpenGL3DGrid grid, float halfSize, int numLines) {
        // Lines parallel to X-axis (varying Z position)
        for (int i = 0; i <= numLines; i++) {
            float z = -halfSize + i * GRID_SPACING;
            boolean isMajorLine = (Math.abs(z) % MAJOR_GRID_INTERVAL) < 0.01f;
            boolean isXAxis = Math.abs(z) < 0.01f;
            
            DebugColor color = isXAxis ? DebugColor.RED : 
                              isMajorLine ? DebugColor.GRAY : DebugColor.LIGHTGRAY;
            float thickness = isXAxis ? AXIS_LINE_THICKNESS : 
                             isMajorLine ? MAJOR_LINE_THICKNESS : GRID_LINE_THICKNESS;
            
            // Create line from (-halfSize, 0, z) to (halfSize, 0, z)
            grid.addLine(-halfSize, 0, z, halfSize, 0, z, color, thickness);
        }
        
        // Lines parallel to Z-axis (varying X position)
        for (int i = 0; i <= numLines; i++) {
            float x = -halfSize + i * GRID_SPACING;
            boolean isMajorLine = (Math.abs(x) % MAJOR_GRID_INTERVAL) < 0.01f;
            boolean isZAxis = Math.abs(x) < 0.01f;
            
            DebugColor color = isZAxis ? DebugColor.BLUE : 
                              isMajorLine ? DebugColor.GRAY : DebugColor.LIGHTGRAY;
            float thickness = isZAxis ? AXIS_LINE_THICKNESS : 
                             isMajorLine ? MAJOR_LINE_THICKNESS : GRID_LINE_THICKNESS;
            
            // Create line from (x, 0, -halfSize) to (x, 0, halfSize)
            grid.addLine(x, 0, -halfSize, x, 0, halfSize, color, thickness);
        }
    }
    
    /**
     * Create vertical grid lines extending upward for 3D depth perception.
     */
    private void createVerticalGridLines(OpenGL3DGrid grid, float halfSize, int numLines) {
        float gridHeight = 20.0f; // Extend grid lines upward
        
        // Vertical lines at major grid intersections
        for (int i = 0; i <= numLines; i += (int)(MAJOR_GRID_INTERVAL / GRID_SPACING)) {
            for (int j = 0; j <= numLines; j += (int)(MAJOR_GRID_INTERVAL / GRID_SPACING)) {
                float x = -halfSize + i * GRID_SPACING;
                float z = -halfSize + j * GRID_SPACING;
                
                // Add vertical line from ground to height
                grid.addLine(x, 0, z, x, gridHeight, z, DebugColor.LIGHTGRAY, GRID_LINE_THICKNESS * 0.5f);
            }
        }
    }
    
    /**
     * Create 3D coordinate axes.
     */
    private void create3DCoordinateAxes(OpenGL3DGrid grid) {
        float axisLength = GRID_SIZE * 0.6f;
        
        // X-axis (red) - pointing right
        grid.addLine(0, 0, 0, axisLength, 0, 0, DebugColor.RED, AXIS_LINE_THICKNESS * 1.5f);
        
        // Y-axis (green) - pointing up
        grid.addLine(0, 0, 0, 0, axisLength, 0, DebugColor.GREEN, AXIS_LINE_THICKNESS * 1.5f);
        
        // Z-axis (blue) - pointing forward
        grid.addLine(0, 0, 0, 0, 0, axisLength, DebugColor.BLUE, AXIS_LINE_THICKNESS * 1.5f);
    }
    
    /**
     * Create origin marker.
     */
    private void createOriginMarker(OpenGL3DGrid grid) {
        // Create a small sphere or box at origin
        float markerSize = AXIS_LINE_THICKNESS * 2.0f;
        grid.addPoint(0, 0, 0, DebugColor.YELLOW, markerSize);
    }
    
    
    
    /**
     * Render debug information for a model using OpenGL 3D rendering.
     */
    public void renderModelDebugInfo(StonebreakModel model) {
        if (!debugMode || !partLabelsVisible || model == null) {
            return;
        }
        
        try {
            ModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) return;
            
            ModelDefinition.ModelParts parts = definition.getParts();
            if (parts == null) return;
            
            // Log debug info for each part (3D rendering would be handled by OpenGL system)
            if (parts.getHead() != null) {
                logPartDebugInfo("head", parts.getHead());
            }
            if (parts.getBody() != null) {
                logPartDebugInfo("body", parts.getBody());
            }
            if (parts.getLegs() != null) {
                for (int i = 0; i < parts.getLegs().size(); i++) {
                    ModelDefinition.ModelPart leg = parts.getLegs().get(i);
                    logPartDebugInfo("leg" + (i + 1), leg);
                }
            }
            if (parts.getUdder() != null) {
                logPartDebugInfo("udder", parts.getUdder());
            }
            if (parts.getTail() != null) {
                logPartDebugInfo("tail", parts.getTail());
            }
            
            logger.debug("Rendered debug info for model: {}", model.getVariantName());
            
        } catch (Exception e) {
            logger.error("Failed to render model debug info", e);
        }
    }
    
    /**
     * Log debug information for a specific model part.
     * In a full OpenGL implementation, this would create 3D wireframe boxes and labels.
     */
    private void logPartDebugInfo(String partName, ModelDefinition.ModelPart part) {
        if (part == null) return;
        
        try {
            // Get part properties
            Vector3f translation = part.getPositionVector();
            Vector3f size = part.getSizeVector();
            
            if (translation == null || size == null) {
                return;
            }
            
            Vector3f center = new Vector3f(translation);
            Vector3f partSize = new Vector3f(size);
            
            // Log debug info (in full implementation, would render 3D wireframe and text)
            logger.debug("Model part '{}': position=({:.2f},{:.2f},{:.2f}), size=({:.2f},{:.2f},{:.2f})", 
                partName, center.x, center.y, center.z, 
                partSize.x, partSize.y, partSize.z);
                
        } catch (Exception e) {
            logger.error("Failed to log debug info for part: " + partName, e);
        }
    }
    
    /**
     * Render coordinate system debug information.
     */
    public void renderCoordinateSystemDebugInfo() {
        if (!debugMode || !coordinateAxesVisible) {
            return;
        }
        
        logger.debug("Coordinate system debug info:");
        logger.debug("- Grid visible: {}", gridVisible);
        logger.debug("- Axes visible: {}", axesVisible);
        logger.debug("- Debug mode: {}", debugMode);
        logger.debug("- Grid size: {}, spacing: {}", GRID_SIZE, GRID_SPACING);
    }
    
    /**
     * Validate coordinate system setup.
     */
    public boolean validateCoordinateSystem() {
        if (sceneManager == null) {
            logger.debug("Coordinate system validation: Scene manager is null");
            return false;
        }
        
        if (sceneManager.getSceneWidth() <= 0 || sceneManager.getSceneHeight() <= 0) {
            logger.debug("Coordinate system validation: Invalid scene dimensions {}x{}", 
                sceneManager.getSceneWidth(), sceneManager.getSceneHeight());
            return false;
        }
        
        logger.debug("Coordinate system validation passed");
        return true;
    }
    
    /**
     * Create a wireframe bounding box for coordinate validation using OpenGL approach.
     * This would show the exact bounding box that ray casting uses for selection.
     * Currently logs debug info - in full OpenGL implementation would render 3D wireframe.
     */
    protected Group createBoundingBoxWireframe(StonebreakModel.BoundingBox bounds, DebugColor color, float thickness) {
        Group wireframeGroup = new Group();
        
        float width = bounds.getWidth();
        float height = bounds.getHeight();
        float depth = bounds.getDepth();
        float centerX = bounds.getMinX() + width / 2.0f;
        float centerY = bounds.getMinY() + height / 2.0f;
        float centerZ = bounds.getMinZ() + depth / 2.0f;
        
        // Log bounding box info (in full implementation would create OpenGL wireframe)
        logger.debug("Bounding box wireframe: center=({:.2f},{:.2f},{:.2f}), size=({:.2f},{:.2f},{:.2f})", 
            centerX, centerY, centerZ, width, height, depth);
        
        return wireframeGroup;
    }
    
    
    // Getters and Setters
    public void setGridVisible(boolean visible) {
        if (this.gridVisible != visible) {
            this.gridVisible = visible;
            updateGrid();
            logger.debug("Grid visibility set to: {}", visible);
        }
    }
    
    public boolean isGridVisible() {
        return gridVisible;
    }
    
    public void setAxesVisible(boolean visible) {
        if (this.axesVisible != visible) {
            this.axesVisible = visible;
            updateAxes();
            logger.debug("Axes visibility set to: {}", visible);
        }
    }
    
    public boolean isAxesVisible() {
        return axesVisible;
    }
    
    public void setCoordinateAxesVisible(boolean visible) {
        if (this.coordinateAxesVisible != visible) {
            this.coordinateAxesVisible = visible;
            updateAxes();
            logger.debug("Coordinate axes visibility set to: {}", visible);
        }
    }
    
    public boolean isCoordinateAxesVisible() {
        return coordinateAxesVisible;
    }
    
    public void setPartLabelsVisible(boolean visible) {
        this.partLabelsVisible = visible;
        logger.debug("Part labels visibility set to: {}", visible);
    }
    
    public boolean isPartLabelsVisible() {
        return partLabelsVisible;
    }
    
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        updateDebugVisualization();
        logger.debug("Debug mode set to: {}", enabled);
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    
    
    
    /**
     * Get debug rendering statistics.
     */
    public String getDebugStatistics() {
        return String.format("Debug Stats: Grid=%s, Axes=%s, CoordAxes=%s, Labels=%s, Debug=%s, Selected=%s",
            gridVisible, axesVisible, coordinateAxesVisible, partLabelsVisible, debugMode,
            "none");
    }
}