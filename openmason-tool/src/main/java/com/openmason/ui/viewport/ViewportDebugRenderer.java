package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.DrawMode;
import javafx.scene.text.Text;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
        updateGrid();
        updateAxes();
        
        // Debug visualization updated silently
    }
    
    /**
     * Force grid refresh - useful for testing changes.
     */
    public void forceGridRefresh() {
        logger.info("Forcing grid refresh...");
        updateGrid();
    }
    
    /**
     * Update grid visualization.
     */
    private void updateGrid() {
        if (sceneManager == null) return;
        
        if (gridVisible) {
            Group gridElements = create3DGrid();
            sceneManager.setGridElements(gridElements);
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
     * Create professional 2D grid plane system.
     * Creates a flat grid on the XZ plane (Y=0) suitable for 2D visualization with:
     * - Regular grid lines every 2 units in both X and Z directions
     * - Major grid lines every 10 units 
     * - Enhanced X-axis (red) and Z-axis (blue) visibility
     * - Thin lines for clean 2D grid plane appearance
     */
    private Group create3DGrid() {
        Group gridGroup = new Group();
        
        try {
            float halfSize = GRID_SIZE / 2.0f;
            int numLines = (int) (GRID_SIZE / GRID_SPACING);
            
            // Creating 2D grid plane
            
            int xLinesCreated = 0;
            int zLinesCreated = 0;
            
            // Create lines parallel to X-axis (horizontal lines, varying Z position)
            for (int i = 0; i <= numLines; i++) {
                float z = -halfSize + i * GRID_SPACING;
                boolean isMajorLine = (Math.abs(z) % MAJOR_GRID_INTERVAL) < 0.01f;
                boolean isXAxis = Math.abs(z) < 0.01f; // Z=0 line (X-axis)
                
                Color lineColor;
                float thickness;
                
                if (isXAxis) {
                    // Highlight the X-axis (Z=0 line) with red
                    lineColor = Color.RED;
                    thickness = AXIS_LINE_THICKNESS;
                } else if (isMajorLine) {
                    lineColor = Color.DARKGRAY;
                    thickness = MAJOR_LINE_THICKNESS;
                } else {
                    lineColor = Color.LIGHTGRAY;
                    thickness = GRID_LINE_THICKNESS;
                }
                
                // Create flat X-direction line (horizontal line on 2D plane)
                Box xLine = createFlatGridLine(GRID_SIZE, 0.001f, thickness, lineColor); // width, height, depth
                xLine.setTranslateX(0);
                xLine.setTranslateY(0);
                xLine.setTranslateZ(z);
                gridGroup.getChildren().add(xLine);
                xLinesCreated++;
            }
            
            // Create lines parallel to Z-axis (vertical lines, varying X position)
            for (int i = 0; i <= numLines; i++) {
                float x = -halfSize + i * GRID_SPACING;
                boolean isMajorLine = (Math.abs(x) % MAJOR_GRID_INTERVAL) < 0.01f;
                boolean isZAxis = Math.abs(x) < 0.01f; // X=0 line (Z-axis)
                
                Color lineColor;
                float thickness;
                
                if (isZAxis) {
                    // Highlight the Z-axis (X=0 line) with blue
                    lineColor = Color.BLUE;
                    thickness = AXIS_LINE_THICKNESS;
                } else if (isMajorLine) {
                    lineColor = Color.DARKGRAY;
                    thickness = MAJOR_LINE_THICKNESS;
                } else {
                    lineColor = Color.LIGHTGRAY;
                    thickness = GRID_LINE_THICKNESS;
                }
                
                // Create flat Z-direction line (vertical line on 2D plane)
                Box zLine = createFlatGridLine(thickness, 0.001f, GRID_SIZE, lineColor); // width, height, depth
                zLine.setTranslateX(x);
                zLine.setTranslateY(0);
                zLine.setTranslateZ(0);
                gridGroup.getChildren().add(zLine);
                zLinesCreated++;
            }
            
            // Add visible origin marker
            Box originMarker = new Box(AXIS_LINE_THICKNESS, 0.004f, AXIS_LINE_THICKNESS);
            PhongMaterial originMaterial = new PhongMaterial();
            originMaterial.setDiffuseColor(Color.YELLOW);
            originMaterial.setSpecularColor(Color.GOLD);
            originMarker.setMaterial(originMaterial);
            originMarker.setTranslateX(0);
            originMarker.setTranslateY(-0.0005f); // Very close to axis lines
            originMarker.setTranslateZ(0);
            gridGroup.getChildren().add(originMarker);
            
            // Grid plane created successfully
            
        } catch (Exception e) {
            logger.error("Failed to create 2D grid plane", e);
        }
        
        return gridGroup;
    }
    
    // Coordinate axes removed - not properly implemented
    
    /**
     * Create a flat grid line using a Box for true 2D appearance.
     */
    private Box createFlatGridLine(double width, double height, double depth, Color color) {
        Box line = new Box(width, height, depth);
        
        // Create flat material
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(color);
        material.setSpecularColor(color.brighter());
        line.setMaterial(material);
        
        return line;
    }

    /**
     * Create a 3D line using a cylinder between two points.
     */
    private Cylinder createLine3D(double startX, double startY, double startZ, 
                                  double endX, double endY, double endZ, 
                                  double thickness, Color color) {
        
        // Calculate line properties
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        
        double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        
        // Create cylinder
        Cylinder cylinder = new Cylinder(thickness, length);
        
        // Create material
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(color);
        material.setSpecularColor(color.brighter());
        cylinder.setMaterial(material);
        
        // Position cylinder at midpoint
        double midX = (startX + endX) / 2.0;
        double midY = (startY + endY) / 2.0;
        double midZ = (startZ + endZ) / 2.0;
        
        cylinder.getTransforms().add(new Translate(midX, midY, midZ));
        
        // Orient cylinder along the line direction
        if (length > 0) {
            // Calculate rotation angles
            double pitch = Math.atan2(Math.sqrt(deltaX * deltaX + deltaZ * deltaZ), deltaY);
            double yaw = Math.atan2(deltaX, deltaZ);
            
            cylinder.getTransforms().add(new Rotate(Math.toDegrees(pitch), Rotate.X_AXIS));
            cylinder.getTransforms().add(new Rotate(Math.toDegrees(yaw), Rotate.Y_AXIS));
        }
        
        return cylinder;
    }
    
    
    // Axis labels removed - not needed without axes
    
    /**
     * Render debug information for a model.
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
            
            // Create debug info for each part
            if (parts.getHead() != null) {
                renderPartDebugInfo("head", parts.getHead());
            }
            if (parts.getBody() != null) {
                renderPartDebugInfo("body", parts.getBody());
            }
            if (parts.getLegs() != null) {
                for (int i = 0; i < parts.getLegs().size(); i++) {
                    ModelDefinition.ModelPart leg = parts.getLegs().get(i);
                    renderPartDebugInfo("leg" + (i + 1), leg);
                }
            }
            if (parts.getUdder() != null) {
                renderPartDebugInfo("udder", parts.getUdder());
            }
            if (parts.getTail() != null) {
                renderPartDebugInfo("tail", parts.getTail());
            }
            
            logger.debug("Rendered debug info for model: {}", model.getVariantName());
            
        } catch (Exception e) {
            logger.error("Failed to render model debug info", e);
        }
    }
    
    /**
     * Render debug information for a specific model part.
     */
    private void renderPartDebugInfo(String partName, ModelDefinition.ModelPart part) {
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
            
            // Create debug label
            Text label = new Text(partName);
            label.setFont(Font.font(12));
            label.setFill(Color.WHITE);
            
            // Position label above the part
            label.getTransforms().add(new Translate(
                center.x, 
                center.y - partSize.y / 2 - 1.0, 
                center.z
            ));
            
            // Add to scene (this would need to be integrated with the scene manager)
            // For now, just log the debug info
            logger.trace("Debug info for '{}': pos=({},{},{}), size=({},{},{})", 
                partName, center.x, center.y, center.z, 
                partSize.x, partSize.y, partSize.z);
                
        } catch (Exception e) {
            logger.error("Failed to render debug info for part: " + partName, e);
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
        return String.format("Debug Stats: Grid=%s, Axes=%s, CoordAxes=%s, Labels=%s, Debug=%s",
            gridVisible, axesVisible, coordinateAxesVisible, partLabelsVisible, debugMode);
    }
}