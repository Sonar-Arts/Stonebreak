package com.openmason.ui.viewport;

import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;
import javafx.scene.paint.Color;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Box;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.transform.Rotate;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;

/**
 * Manages JavaFX 3D scene setup and components.
 * 
 * Responsible for:
 * - SubScene and Group hierarchy management
 * - 3D Camera setup and configuration
 * - Basic lighting setup
 * - Scene component lifecycle management
 */
public class ViewportSceneManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportSceneManager.class);
    
    // JavaFX 3D Scene components
    private SubScene subScene3D;
    private Group rootGroup3D;
    private Group modelGroup3D;
    private Group gridGroup3D;
    private Group axesGroup3D;
    private PerspectiveCamera camera3D;
    
    // Model rendering state
    private final Map<String, Box> modelPartBoxes = new HashMap<>();
    private final Map<String, PhongMaterial> modelPartMaterials = new HashMap<>();
    
    // Scene properties
    private double sceneWidth = 800.0;
    private double sceneHeight = 600.0;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;
    
    /**
     * Initialize the JavaFX 3D scene structure.
     */
    public void initializeScene(StackPane parent) {
        try {
            logger.info("Initializing JavaFX 3D Scene Manager");
            
            // Create root group for 3D content
            rootGroup3D = new Group();
            
            // Create sub-groups for organization
            modelGroup3D = new Group();
            gridGroup3D = new Group();
            axesGroup3D = new Group();
            
            // Add sub-groups to root
            rootGroup3D.getChildren().addAll(modelGroup3D, gridGroup3D, axesGroup3D);
            
            // Create 3D SubScene
            subScene3D = new SubScene(rootGroup3D, sceneWidth, sceneHeight, true, SceneAntialiasing.BALANCED);
            subScene3D.setFill(Color.rgb(40, 40, 40)); // Dark dark gray background
            
            // Ensure SubScene can receive input events
            subScene3D.setFocusTraversable(true);
            subScene3D.setMouseTransparent(false);
            
            // Create and configure perspective camera
            setupCamera();
            
            // Add basic lighting
            setupLighting();
            
            // Bind scene size to parent
            subScene3D.widthProperty().bind(parent.widthProperty());
            subScene3D.heightProperty().bind(parent.heightProperty());
            
            // Add scene to parent
            parent.getChildren().add(subScene3D);
            
            logger.info("JavaFX 3D Scene Manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize JavaFX 3D scene", e);
            throw new RuntimeException("Scene initialization failed", e);
        }
    }
    
    /**
     * Setup the 3D perspective camera.
     */
    private void setupCamera() {
        camera3D = new PerspectiveCamera(true);
        camera3D.setNearClip(NEAR_PLANE);
        camera3D.setFarClip(FAR_PLANE);
        camera3D.setFieldOfView(60.0);
        
        // Position camera for good default view
        camera3D.setTranslateX(0);
        camera3D.setTranslateY(0);
        camera3D.setTranslateZ(-10);
        
        subScene3D.setCamera(camera3D);
        
        logger.debug("3D Camera configured: FOV=60°, Near={}, Far={}", NEAR_PLANE, FAR_PLANE);
    }
    
    /**
     * Update JavaFX camera position from ArcBall camera state.
     */
    public void updateCameraFromMatrix(Matrix4f viewMatrix) {
        if (camera3D == null || viewMatrix == null) {
            return;
        }
        
        try {
            // Extract camera position from inverse view matrix
            Matrix4f invViewMatrix = new Matrix4f(viewMatrix).invert();
            
            // Get camera position (translation part of inverse view matrix)
            float x = invViewMatrix.m30();
            float y = invViewMatrix.m31(); 
            float z = invViewMatrix.m32();
            
            // Update JavaFX camera position
            camera3D.setTranslateX(x);
            camera3D.setTranslateY(-y); // JavaFX Y is inverted compared to OpenGL
            camera3D.setTranslateZ(z);
            
            // For JavaFX camera, we need to apply lookAt transformation
            // Reset any existing rotations
            camera3D.getTransforms().clear();
            
            // Calculate lookAt rotation
            Vector3f target = new Vector3f(0, 0, 0);
            Vector3f cameraPos = new Vector3f(x, -y, z); // Use JavaFX coordinate system
            Vector3f direction = new Vector3f(target).sub(cameraPos).normalize();
            
            // Calculate rotation angles to look at target
            double yaw = Math.toDegrees(Math.atan2(direction.x, direction.z));
            double pitch = Math.toDegrees(Math.asin(-direction.y));
            
            // Apply rotations to camera
            Rotate yawRotate = new Rotate(yaw, Rotate.Y_AXIS);
            Rotate pitchRotate = new Rotate(pitch, Rotate.X_AXIS);
            
            camera3D.getTransforms().addAll(yawRotate, pitchRotate);
            
            // Camera position updated (debug logging removed to reduce spam)
            
        } catch (Exception e) {
            logger.warn("Failed to update JavaFX camera from matrix", e);
        }
    }
    
    /**
     * Setup basic scene lighting.
     */
    private void setupLighting() {
        // Ambient light for overall scene illumination
        AmbientLight ambientLight = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.3, 1));
        
        // Point light for better 3D definition
        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(10);
        pointLight.setTranslateY(-10);
        pointLight.setTranslateZ(-10);
        
        rootGroup3D.getChildren().addAll(ambientLight, pointLight);
        
        logger.debug("Scene lighting configured: Ambient + Point light");
    }
    
    /**
     * Update scene dimensions.
     */
    public void updateSceneDimensions(double width, double height) {
        if (width > 0 && height > 0) {
            this.sceneWidth = width;
            this.sceneHeight = height;
            
            if (subScene3D != null) {
                subScene3D.setWidth(width);
                subScene3D.setHeight(height);
            }
            
            logger.debug("Scene dimensions updated: {}x{}", width, height);
        }
    }
    
    /**
     * Clear all model parts from the scene.
     */
    public void clearModelParts() {
        if (modelGroup3D != null) {
            modelGroup3D.getChildren().clear();
        }
        modelPartBoxes.clear();
        modelPartMaterials.clear();
        
        logger.debug("Model parts cleared from scene");
    }
    
    /**
     * Add a model part box to the scene.
     */
    public void addModelPart(String partName, Box box, PhongMaterial material) {
        if (modelGroup3D != null && box != null) {
            modelPartBoxes.put(partName, box);
            if (material != null) {
                modelPartMaterials.put(partName, material);
                box.setMaterial(material);
            }
            modelGroup3D.getChildren().add(box);
            
            logger.debug("Added model part '{}' to scene", partName);
        }
    }
    
    /**
     * Remove a specific model part from the scene.
     */
    public void removeModelPart(String partName) {
        Box box = modelPartBoxes.remove(partName);
        if (box != null && modelGroup3D != null) {
            modelGroup3D.getChildren().remove(box);
        }
        modelPartMaterials.remove(partName);
        
        logger.debug("Removed model part '{}' from scene", partName);
    }
    
    /**
     * Add grid elements to the scene.
     */
    public void setGridElements(Group gridElements) {
        if (gridGroup3D != null) {
            gridGroup3D.getChildren().clear();
            if (gridElements != null) {
                gridGroup3D.getChildren().add(gridElements);
                logger.debug("Grid elements added to scene");
            }
        }
    }
    
    /**
     * Add axes elements to the scene.
     */
    public void setAxesElements(Group axesElements) {
        if (axesGroup3D != null) {
            axesGroup3D.getChildren().clear();
            if (axesElements != null) {
                axesGroup3D.getChildren().add(axesElements);
                logger.debug("Axes elements added to scene");
            }
        }
    }
    
    /**
     * Dispose of scene resources.
     */
    public void dispose() {
        logger.info("Disposing ViewportSceneManager resources");
        
        clearModelParts();
        
        if (gridGroup3D != null) {
            gridGroup3D.getChildren().clear();
        }
        
        if (axesGroup3D != null) {
            axesGroup3D.getChildren().clear();
        }
        
        if (rootGroup3D != null) {
            rootGroup3D.getChildren().clear();
        }
        
        subScene3D = null;
        rootGroup3D = null;
        modelGroup3D = null;
        gridGroup3D = null;
        axesGroup3D = null;
        camera3D = null;
    }
    
    // Getters
    public SubScene getSubScene3D() { return subScene3D; }
    public Group getRootGroup3D() { return rootGroup3D; }
    public Group getModelGroup3D() { return modelGroup3D; }
    public Group getGridGroup3D() { return gridGroup3D; }
    public Group getAxesGroup3D() { return axesGroup3D; }
    public PerspectiveCamera getCamera3D() { return camera3D; }
    
    public Map<String, Box> getModelPartBoxes() { return modelPartBoxes; }
    public Map<String, PhongMaterial> getModelPartMaterials() { return modelPartMaterials; }
    
    public double getSceneWidth() { return sceneWidth; }
    public double getSceneHeight() { return sceneHeight; }
}