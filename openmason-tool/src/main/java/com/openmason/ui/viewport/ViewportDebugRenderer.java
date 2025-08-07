package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;

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
    
    // Professional 3D viewport grid configuration
    private static final float GRID_SIZE = 40.0f;          // Total grid size (40x40 units)
    private static final float GRID_SPACING = 2.0f;        // Distance between grid lines
    private static final float MAJOR_GRID_INTERVAL = 10.0f; // Major grid lines every 10 units
    private static final float GRID_LINE_THICKNESS = 0.02f; // Thin grid lines
    private static final float MAJOR_LINE_THICKNESS = 0.04f; // Thicker major lines
    
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
        
        logger.trace("Debug visualization updated: grid={}, axes={}, coordAxes={}, labels={}", 
            gridVisible, axesVisible, coordinateAxesVisible, partLabelsVisible);
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
     * Create professional 3D viewport grid system.
     * Standard grid layout suitable for 3D model visualization with:
     * - Regular grid lines every 2 units
     * - Major grid lines every 10 units 
     * - Clean, minimal appearance
     * - Proper depth perception
     */
    private Group create3DGrid() {
        Group gridGroup = new Group();
        
        try {
            float halfSize = GRID_SIZE / 2.0f;
            int numLines = (int) (GRID_SIZE / GRID_SPACING);
            
            // Create horizontal grid lines (parallel to X-axis)
            for (int i = 0; i <= numLines; i++) {
                float z = -halfSize + i * GRID_SPACING;
                boolean isMajorLine = (Math.abs(z) % MAJOR_GRID_INTERVAL) < 0.01f;
                
                Color lineColor = isMajorLine ? Color.GRAY.darker() : Color.LIGHTGRAY;
                float thickness = isMajorLine ? MAJOR_LINE_THICKNESS : GRID_LINE_THICKNESS;
                
                Cylinder line = createLine3D(-halfSize, 0, z, halfSize, 0, z, thickness, lineColor);
                gridGroup.getChildren().add(line);
            }
            
            // Create vertical grid lines (parallel to Z-axis) 
            for (int i = 0; i <= numLines; i++) {
                float x = -halfSize + i * GRID_SPACING;
                boolean isMajorLine = (Math.abs(x) % MAJOR_GRID_INTERVAL) < 0.01f;
                
                Color lineColor = isMajorLine ? Color.GRAY.darker() : Color.LIGHTGRAY;
                float thickness = isMajorLine ? MAJOR_LINE_THICKNESS : GRID_LINE_THICKNESS;
                
                Cylinder line = createLine3D(x, 0, -halfSize, x, 0, halfSize, thickness, lineColor);
                gridGroup.getChildren().add(line);
            }
            
            // Add subtle origin marker
            Box originMarker = new Box(0.3, 0.05, 0.3);
            PhongMaterial originMaterial = new PhongMaterial();
            originMaterial.setDiffuseColor(Color.ORANGE);
            originMaterial.setSpecularColor(Color.GOLD);
            originMarker.setMaterial(originMaterial);
            originMarker.setTranslateX(0);
            originMarker.setTranslateY(0.025);
            originMarker.setTranslateZ(0);
            gridGroup.getChildren().add(originMarker);
            
            logger.debug("Created professional 3D viewport grid: {} lines, spacing={}, major intervals={}", 
                numLines * 2, GRID_SPACING, MAJOR_GRID_INTERVAL);
            
        } catch (Exception e) {
            logger.error("Failed to create 3D viewport grid", e);
        }
        
        return gridGroup;
    }
    
    // Coordinate axes removed - not properly implemented
    
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
            StonebreakModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) return;
            
            StonebreakModelDefinition.ModelParts parts = definition.getParts();
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
                    StonebreakModelDefinition.ModelPart leg = parts.getLegs().get(i);
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
    private void renderPartDebugInfo(String partName, StonebreakModelDefinition.ModelPart part) {
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