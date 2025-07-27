package com.openmason.ui.controllers;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.camera.ArcBallCamera;
import com.openmason.util.RetryManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller responsible for managing the 3D viewport and related UI controls.
 * Handles viewport initialization, view modes, rendering controls, and camera operations.
 */
public class ViewportController {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportController.class);
    
    // Viewport Components
    @FXML private AnchorPane viewportContainer;
    @FXML private ComboBox<String> cmbViewMode;
    @FXML private ComboBox<String> cmbRenderMode;
    
    // Toolbar Controls
    @FXML private Button btnResetView;
    @FXML private Button btnZoomIn;
    @FXML private Button btnZoomOut;
    @FXML private Button btnFitToView;
    @FXML private ToggleButton btnWireframe;
    @FXML private ToggleButton btnShowGrid;
    @FXML private ToggleButton btnShowAxes;
    
    // Menu Controls
    @FXML private MenuItem menuResetView;
    @FXML private MenuItem menuFitToView;
    @FXML private CheckMenuItem menuShowGrid;
    @FXML private CheckMenuItem menuShowAxes;
    @FXML private CheckMenuItem menuWireframeMode;
    
    // Core viewport component
    private OpenMason3DViewport viewport3D;
    
    // Retry manager for robust error recovery
    private final RetryManager retryManager = RetryManager.withConfig(
        RetryManager.CommonConfigs.quickOperation()
            .maxAttempts(3)
            .retryOn(e -> !(e.getMessage() != null && e.getMessage().contains("fatal")))
    );
    
    /**
     * Initializes the viewport controller and sets up the 3D viewport.
     */
    public void initialize() {
        setupViewport();
        setupViewportControls();
        bindViewportControls();
    }
    
    /**
     * Sets up the 3D viewport with error handling and fallback.
     * Implements retry logic for robust initialization.
     */
    private void setupViewport() {
        setupViewportWithRetry(3);
    }
    
    /**
     * Sets up the 3D viewport with retry logic for robust initialization.
     * Now uses RetryManager for professional error recovery with exponential backoff.
     * 
     * @param maxAttempts Maximum number of initialization attempts
     */
    private void setupViewportWithRetry(int maxAttempts) {
        RetryManager.RetryResult<Void> result = retryManager.execute(() -> {
            logger.debug("Attempting to initialize 3D viewport");
            viewport3D = new OpenMason3DViewport();
            
            // Configure viewport
            viewport3D.setPrefSize(800, 600);
            viewport3D.setMinSize(400, 300);
            
            // Add to container
            viewportContainer.getChildren().clear();
            viewportContainer.getChildren().add(viewport3D);
            AnchorPane.setTopAnchor(viewport3D, 0.0);
            AnchorPane.setLeftAnchor(viewport3D, 0.0);
            AnchorPane.setRightAnchor(viewport3D, 0.0);
            AnchorPane.setBottomAnchor(viewport3D, 0.0);
            
            return null; // Success
        }, "3D Viewport Initialization");
        
        if (result.isSuccess()) {
            logger.info("3D viewport initialized successfully after {} attempt(s) in {}ms", 
                       result.getAttemptCount(), result.getTotalDurationMs());
        } else {
            logger.error("Failed to initialize 3D viewport after {} attempts in {}ms", 
                        result.getAttemptCount(), result.getTotalDurationMs());
            showViewportError(result.getLastException());
            throw new RuntimeException("Failed to initialize 3D viewport", result.getLastException());
        }
    }
    
    /**
     * Shows viewport initialization error to user with fallback display and recovery options.
     */
    private void showViewportError(Exception e) {
        logger.error("Failed to initialize 3D viewport", e);
        
        Label errorLabel = new Label("3D Viewport initialization failed: " + e.getMessage());
        errorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 14px; -fx-padding: 20px;");
        errorLabel.setWrapText(true);
        
        Button retryButton = new Button("Retry Initialization");
        retryButton.setOnAction(event -> {
            retryButton.setDisable(true);
            retryButton.setText("Retrying...");
            
            // Use async retry to avoid blocking UI thread
            retryManager.executeAsync(() -> {
                setupViewport();
                return null;
            }, "Viewport Retry").thenAccept(result -> {
                javafx.application.Platform.runLater(() -> {
                    if (!result.isSuccess()) {
                        retryButton.setDisable(false);
                        retryButton.setText("Retry Initialization");
                        errorLabel.setText("Retry failed: " + result.getLastException().getMessage());
                    }
                });
            });
        });
        
        Button fallbackButton = new Button("Use Fallback Mode");
        fallbackButton.setOnAction(event -> {
            showFallbackViewport();
        });
        
        Label helpLabel = new Label(
            "Try restarting the application or check your graphics drivers if the problem persists."
        );
        helpLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");
        helpLabel.setWrapText(true);
        
        viewportContainer.getChildren().clear();
        viewportContainer.getChildren().addAll(errorLabel, retryButton, fallbackButton, helpLabel);
        
        AnchorPane.setTopAnchor(errorLabel, 20.0);
        AnchorPane.setLeftAnchor(errorLabel, 20.0);
        AnchorPane.setRightAnchor(errorLabel, 20.0);
        
        AnchorPane.setTopAnchor(retryButton, 80.0);
        AnchorPane.setLeftAnchor(retryButton, 20.0);
        
        AnchorPane.setTopAnchor(fallbackButton, 80.0);
        AnchorPane.setLeftAnchor(fallbackButton, 160.0);
        
        AnchorPane.setTopAnchor(helpLabel, 120.0);
        AnchorPane.setLeftAnchor(helpLabel, 20.0);
        AnchorPane.setRightAnchor(helpLabel, 20.0);
    }
    
    /**
     * Shows a fallback viewport when 3D initialization fails.
     */
    private void showFallbackViewport() {
        Label fallbackLabel = new Label("3D Viewport (Fallback Mode)");
        fallbackLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 20px;");
        
        Label infoLabel = new Label(
            "Running in fallback mode. Some 3D features may be limited.\n" +
            "Model preview and basic controls are still available."
        );
        infoLabel.setStyle("-fx-font-size: 12px; -fx-padding: 20px;");
        infoLabel.setWrapText(true);
        
        // Simple wireframe representation
        javafx.scene.canvas.Canvas fallbackCanvas = new javafx.scene.canvas.Canvas(400, 300);
        javafx.scene.canvas.GraphicsContext gc = fallbackCanvas.getGraphicsContext2D();
        
        // Draw simple 3D cube wireframe
        gc.setStroke(javafx.scene.paint.Color.BLUE);
        gc.setLineWidth(2);
        
        // Front face
        gc.strokeRect(50, 50, 100, 100);
        // Back face
        gc.strokeRect(80, 80, 100, 100);
        // Connect corners
        gc.strokeLine(50, 50, 80, 80);
        gc.strokeLine(150, 50, 180, 80);
        gc.strokeLine(150, 150, 180, 180);
        gc.strokeLine(50, 150, 80, 180);
        
        gc.setFill(javafx.scene.paint.Color.GRAY);
        gc.fillText("Fallback 3D View", 200, 120);
        
        viewportContainer.getChildren().clear();
        viewportContainer.getChildren().addAll(fallbackLabel, infoLabel, fallbackCanvas);
        
        AnchorPane.setTopAnchor(fallbackLabel, 10.0);
        AnchorPane.setLeftAnchor(fallbackLabel, 20.0);
        
        AnchorPane.setTopAnchor(infoLabel, 40.0);
        AnchorPane.setLeftAnchor(infoLabel, 20.0);
        AnchorPane.setRightAnchor(infoLabel, 20.0);
        
        AnchorPane.setTopAnchor(fallbackCanvas, 100.0);
        AnchorPane.setLeftAnchor(fallbackCanvas, 20.0);
    }
    
    /**
     * Sets up viewport mode controls and rendering options.
     */
    private void setupViewportControls() {
        // View mode options
        cmbViewMode.getItems().addAll(
            "Perspective",
            "Orthographic",
            "Front",
            "Side", 
            "Top"
        );
        cmbViewMode.setValue("Perspective");
        
        // Render mode options
        cmbRenderMode.getItems().addAll(
            "Solid",
            "Wireframe",
            "Points",
            "Textured"
        );
        cmbRenderMode.setValue("Solid");
        
        // Set up event handlers
        cmbViewMode.setOnAction(e -> updateViewMode());
        cmbRenderMode.setOnAction(e -> updateRenderMode());
    }
    
    /**
     * Binds viewport controls to viewport properties for synchronization.
     */
    private void bindViewportControls() {
        if (viewport3D == null) return;
        
        // Bidirectional binding for UI synchronization
        menuWireframeMode.selectedProperty().bindBidirectional(viewport3D.wireframeModeProperty());
        btnWireframe.selectedProperty().bindBidirectional(viewport3D.wireframeModeProperty());
        
        menuShowGrid.selectedProperty().bindBidirectional(viewport3D.gridVisibleProperty());
        btnShowGrid.selectedProperty().bindBidirectional(viewport3D.gridVisibleProperty());
        
        menuShowAxes.selectedProperty().bindBidirectional(viewport3D.axesVisibleProperty());
        btnShowAxes.selectedProperty().bindBidirectional(viewport3D.axesVisibleProperty());
        
        // Set up button actions
        btnResetView.setOnAction(e -> resetView());
        menuResetView.setOnAction(e -> resetView());
        
        btnZoomIn.setOnAction(e -> zoomIn());
        btnZoomOut.setOnAction(e -> zoomOut());
        
        btnFitToView.setOnAction(e -> fitToView());
        menuFitToView.setOnAction(e -> fitToView());
    }
    
    /**
     * Updates the viewport view mode based on combo box selection.
     */
    private void updateViewMode() {
        if (viewport3D == null) return;
        
        String viewMode = cmbViewMode.getValue();
        if (viewMode != null) {
            // Apply camera preset based on view mode
            ArcBallCamera camera = viewport3D.getCamera();
            switch (viewMode.toLowerCase()) {
                case "front" -> camera.applyPreset(ArcBallCamera.CameraPreset.FRONT);
                case "back" -> camera.applyPreset(ArcBallCamera.CameraPreset.BACK);
                case "left" -> camera.applyPreset(ArcBallCamera.CameraPreset.LEFT);
                case "right" -> camera.applyPreset(ArcBallCamera.CameraPreset.RIGHT);
                case "top" -> camera.applyPreset(ArcBallCamera.CameraPreset.TOP);
                case "bottom" -> camera.applyPreset(ArcBallCamera.CameraPreset.BOTTOM);
                case "isometric" -> camera.applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);
                default -> logger.warn("Unknown view mode: {}", viewMode);
            }
        }
    }
    
    /**
     * Updates the viewport render mode based on combo box selection.
     */
    private void updateRenderMode() {
        if (viewport3D == null) return;
        
        String renderMode = cmbRenderMode.getValue();
        if (renderMode != null) {
            // Set wireframe mode based on render mode
            boolean wireframe = "wireframe".equalsIgnoreCase(renderMode);
            viewport3D.setWireframeMode(wireframe);
        }
    }
    
    /**
     * Resets the viewport camera to default position and orientation.
     */
    private void resetView() {
        if (viewport3D != null) {
            viewport3D.resetCamera();
            logger.debug("Viewport camera reset");
        }
    }
    
    /**
     * Zooms the camera in by a fixed increment.
     */
    private void zoomIn() {
        if (viewport3D != null) {
            viewport3D.getCamera().zoom(1.0f); // Positive zoom = zoom in
        }
    }
    
    /**
     * Zooms the camera out by a fixed increment.
     */
    private void zoomOut() {
        if (viewport3D != null) {
            viewport3D.getCamera().zoom(-1.0f); // Negative zoom = zoom out
        }
    }
    
    /**
     * Fits the current model to the viewport view.
     */
    private void fitToView() {
        if (viewport3D != null) {
            // Apply optimal preset for model viewing
            viewport3D.getCamera().applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);
            logger.debug("Fit model to view");
        }
    }
    
    // Getters for external access
    public OpenMason3DViewport getViewport3D() {
        return viewport3D;
    }
    
    public BooleanProperty wireframeModeProperty() {
        return viewport3D != null ? viewport3D.wireframeModeProperty() : null;
    }
    
    public BooleanProperty gridVisibleProperty() {
        return viewport3D != null ? viewport3D.gridVisibleProperty() : null;
    }
    
    public BooleanProperty axesVisibleProperty() {
        return viewport3D != null ? viewport3D.axesVisibleProperty() : null;
    }
    
    public StringProperty currentTextureVariantProperty() {
        return viewport3D != null ? viewport3D.currentTextureVariantProperty() : null;
    }
}