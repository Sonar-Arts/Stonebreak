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
    
    // Grid and axes configuration
    private static final float GRID_SIZE = 20.0f;
    private static final int GRID_LINES = 20;
    private static final float AXIS_LENGTH = 10.0f;
    private static final float AXIS_THICKNESS = 0.05f;
    
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
        
        if (axesVisible || coordinateAxesVisible) {
            Group axesElements = create3DAxes();
            sceneManager.setAxesElements(axesElements);
        } else {
            sceneManager.setAxesElements(null);
        }
    }
    
    /**
     * Create 3D grid visualization emphasizing the X axis with better visibility.
     */
    private Group create3DGrid() {
        Group gridGroup = new Group();
        
        try {
            float halfSize = GRID_SIZE / 2.0f;
            float step = GRID_SIZE / GRID_LINES;
            
            // Create grid lines parallel to X axis (running along X direction) - more prominent
            for (int i = 0; i <= GRID_LINES; i++) {
                float z = -halfSize + i * step;
                Color lineColor = (i == GRID_LINES / 2) ? Color.DARKRED : Color.DARKGRAY;
                float thickness = (i == GRID_LINES / 2) ? AXIS_THICKNESS * 1.2f : AXIS_THICKNESS * 0.6f;
                
                Cylinder line = createLine3D(-halfSize, 0, z, halfSize, 0, z, thickness, lineColor);
                gridGroup.getChildren().add(line);
            }
            
            // Create grid lines perpendicular to X axis (running along Z direction) - clearer visibility
            for (int i = 0; i <= GRID_LINES; i += 2) { // Every 2nd line for less visual clutter
                float x = -halfSize + i * step;
                Color lineColor = (i == GRID_LINES / 2) ? Color.DARKBLUE : Color.GRAY;
                float thickness = (i == GRID_LINES / 2) ? AXIS_THICKNESS * 1.0f : AXIS_THICKNESS * 0.4f;
                
                Cylinder line = createLine3D(x, 0, -halfSize, x, 0, halfSize, thickness, lineColor);
                gridGroup.getChildren().add(line);
            }
            
            // Highlight the main X axis with a bold red line
            Cylinder mainXAxis = createLine3D(-halfSize, 0, 0, halfSize, 0, 0, AXIS_THICKNESS * 1.2f, Color.DARKRED);
            gridGroup.getChildren().add(mainXAxis);
            
            // Add X axis markers at regular intervals
            for (int i = -5; i <= 5; i++) {
                if (i == 0) continue; // Skip origin, handled separately
                float x = i * 2.0f; // Every 2 units
                
                // Create small tick marks
                Cylinder tick = createLine3D(x, 0, -0.2f, x, 0, 0.2f, AXIS_THICKNESS * 0.8f, Color.DARKRED);
                gridGroup.getChildren().add(tick);
                
                // Add text labels for major X coordinates
                if (i % 2 == 0) { // Every 4 units
                    String labelText = (i * 2) + "u"; // Add "u" for units
                    Text label = new Text(labelText);
                    label.setFont(Font.font("Arial", 10));
                    label.setFill(Color.DARKRED);
                    label.setTranslateX(x);
                    label.setTranslateY(0.8f);
                    label.setTranslateZ(-0.8f);
                    gridGroup.getChildren().add(label);
                }
            }
            
            // Add origin marker - more visible
            Box originMarker = new Box(0.6, 0.2, 0.6);
            PhongMaterial originMaterial = new PhongMaterial();
            originMaterial.setDiffuseColor(Color.GOLD);
            originMaterial.setSpecularColor(Color.YELLOW);
            originMarker.setMaterial(originMaterial);
            originMarker.setTranslateX(0);
            originMarker.setTranslateY(0.1);
            originMarker.setTranslateZ(0);
            gridGroup.getChildren().add(originMarker);
            
            // Add origin label - more visible
            Text originLabel = new Text("ORIGIN (0,0,0)");
            originLabel.setFont(Font.font("Arial", 14));
            originLabel.setFill(Color.GOLD);
            originLabel.setTranslateX(0.8);
            originLabel.setTranslateY(1.2);
            originLabel.setTranslateZ(0.8);
            gridGroup.getChildren().add(originLabel);
            
            logger.debug("Created X-axis-focused 2D grid with {} lines and X-axis markers", GRID_LINES + GRID_LINES/2 + 1);
            
        } catch (Exception e) {
            logger.error("Failed to create X-axis grid", e);
        }
        
        return gridGroup;
    }
    
    /**
     * Create 3D coordinate axes.
     */
    private Group create3DAxes() {
        Group axesGroup = new Group();
        
        try {
            // X-axis (Red)
            Cylinder xAxis = createLine3D(0, 0, 0, AXIS_LENGTH, 0, 0, AXIS_THICKNESS, Color.RED);
            axesGroup.getChildren().add(xAxis);
            
            // Y-axis (Green)
            Cylinder yAxis = createLine3D(0, 0, 0, 0, AXIS_LENGTH, 0, AXIS_THICKNESS, Color.GREEN);
            axesGroup.getChildren().add(yAxis);
            
            // Z-axis (Blue)
            Cylinder zAxis = createLine3D(0, 0, 0, 0, 0, AXIS_LENGTH, AXIS_THICKNESS, Color.BLUE);
            axesGroup.getChildren().add(zAxis);
            
            // Add axis labels if coordinate axes are visible
            if (coordinateAxesVisible) {
                addAxisLabels(axesGroup);
            }
            
            logger.trace("Created 3D coordinate axes");
            
        } catch (Exception e) {
            logger.error("Failed to create 3D axes", e);
        }
        
        return axesGroup;
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
    
    /**
     * Add axis labels to the axes group.
     */
    private void addAxisLabels(Group axesGroup) {
        try {
            // X-axis label
            Text xLabel = new Text("X");
            xLabel.setFont(Font.font(16));
            xLabel.setFill(Color.RED);
            xLabel.getTransforms().add(new Translate(AXIS_LENGTH + 0.5, 0, 0));
            axesGroup.getChildren().add(xLabel);
            
            // Y-axis label
            Text yLabel = new Text("Y");
            yLabel.setFont(Font.font(16));
            yLabel.setFill(Color.GREEN);
            yLabel.getTransforms().add(new Translate(0, AXIS_LENGTH + 0.5, 0));
            axesGroup.getChildren().add(yLabel);
            
            // Z-axis label
            Text zLabel = new Text("Z");
            zLabel.setFont(Font.font(16));
            zLabel.setFill(Color.BLUE);
            zLabel.getTransforms().add(new Translate(0, 0, AXIS_LENGTH + 0.5));
            axesGroup.getChildren().add(zLabel);
            
            logger.trace("Added axis labels");
            
        } catch (Exception e) {
            logger.error("Failed to add axis labels", e);
        }
    }
    
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
        logger.debug("- Grid size: {}, lines: {}", GRID_SIZE, GRID_LINES);
        logger.debug("- Axis length: {}, thickness: {}", AXIS_LENGTH, AXIS_THICKNESS);
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