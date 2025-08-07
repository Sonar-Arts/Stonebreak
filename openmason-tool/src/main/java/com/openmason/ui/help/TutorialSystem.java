package com.openmason.ui.help;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Interactive tutorial system with step-by-step guidance, visual highlighting,
 * and progress tracking for professional user onboarding.
 */
public class TutorialSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(TutorialSystem.class);
    
    // Tutorial state
    private HelpSystem.Tutorial currentTutorial;
    private int currentStepIndex = -1;
    private final BooleanProperty tutorialActive = new SimpleBooleanProperty(false);
    private final IntegerProperty currentStep = new SimpleIntegerProperty(0);
    private final IntegerProperty totalSteps = new SimpleIntegerProperty(0);
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    
    // UI components
    private TutorialOverlay overlay;
    private TutorialGuide guide;
    private Stage overlayStage;
    private Popup guidePopup;
    
    // Visual effects
    private Timeline pulseAnimation;
    private DropShadow highlightEffect;
    
    /**
     * Tutorial overlay for dimming background and highlighting elements
     */
    private static class TutorialOverlay extends Region {
        private final Rectangle dimmer;
        private final Circle spotlight;
        private Node targetNode;
        private double spotlightRadius = 100;
        
        public TutorialOverlay() {
            // Create semi-transparent background
            dimmer = new Rectangle();
            dimmer.setFill(Color.color(0, 0, 0, 0.7));
            dimmer.widthProperty().bind(widthProperty());
            dimmer.heightProperty().bind(heightProperty());
            
            // Create spotlight circle
            spotlight = new Circle();
            spotlight.setFill(Color.TRANSPARENT);
            spotlight.setStroke(Color.color(0.4, 0.8, 1.0, 0.8));
            spotlight.setStrokeWidth(3);
            spotlight.getStyleClass().add("tutorial-spotlight");
            
            getChildren().addAll(dimmer, spotlight);
            setMouseTransparent(false); // Block interactions with dimmed areas
        }
        
        public void highlightNode(Node node, double radius) {
            this.targetNode = node;
            this.spotlightRadius = radius;
            
            if (node != null) {
                updateSpotlight();
                spotlight.setVisible(true);
            } else {
                spotlight.setVisible(false);
            }
        }
        
        private void updateSpotlight() {
            if (targetNode == null) return;
            
            try {
                Bounds nodeBounds = targetNode.localToScene(targetNode.getBoundsInLocal());
                Bounds overlayBounds = sceneToLocal(nodeBounds);
                
                double centerX = overlayBounds.getCenterX();
                double centerY = overlayBounds.getCenterY();
                
                spotlight.setCenterX(centerX);
                spotlight.setCenterY(centerY);
                spotlight.setRadius(spotlightRadius);
                
                // Create cutout effect using clip
                Rectangle clip = new Rectangle();
                clip.widthProperty().bind(widthProperty());
                clip.heightProperty().bind(heightProperty());
                
                Circle cutout = new Circle(centerX, centerY, spotlightRadius);
                
                // Note: JavaFX doesn't have easy boolean operations for shapes
                // In a full implementation, this would use Path with subtract operation
                
            } catch (Exception e) {
                logger.debug("Failed to update spotlight position", e);
            }
        }
        
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            updateSpotlight();
        }
    }
    
    /**
     * Tutorial guide popup with instructions and navigation
     */
    private static class TutorialGuide extends VBox {
        private final Label titleLabel;
        private final Label instructionLabel;
        private final ProgressBar progressBar;
        private final Label progressLabel;
        private final HBox buttonBox;
        private final Button prevButton;
        private final Button nextButton;
        private final Button skipButton;
        private final Button endButton;
        
        private TutorialSystem tutorialSystem;
        
        public TutorialGuide() {
            setSpacing(15);
            setPadding(new Insets(20));
            setMaxWidth(400);
            getStyleClass().add("tutorial-guide");
            
            // Apply styling
            setStyle("-fx-background-color: #3c3c3c; " +
                    "-fx-background-radius: 8; " +
                    "-fx-border-color: #555555; " +
                    "-fx-border-radius: 8; " +
                    "-fx-border-width: 1;");
            
            // Drop shadow effect
            DropShadow shadow = new DropShadow();
            shadow.setColor(Color.color(0, 0, 0, 0.5));
            shadow.setOffsetX(0);
            shadow.setOffsetY(4);
            shadow.setRadius(12);
            setEffect(shadow);
            
            // Title
            titleLabel = new Label("Tutorial Step");
            titleLabel.getStyleClass().addAll("tutorial-title", "h3");
            titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold;");
            
            // Instruction text
            instructionLabel = new Label();
            instructionLabel.getStyleClass().add("tutorial-instruction");
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-text-fill: #e0e0e0;");
            
            // Progress bar
            progressBar = new ProgressBar();
            progressBar.setPrefWidth(300);
            progressBar.getStyleClass().add("tutorial-progress");
            
            progressLabel = new Label("Step 1 of 5");
            progressLabel.getStyleClass().add("tutorial-progress-label");
            progressLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 12px;");
            
            VBox progressBox = new VBox(5);
            progressBox.getChildren().addAll(progressBar, progressLabel);
            
            // Navigation buttons
            buttonBox = new HBox(10);
            buttonBox.setAlignment(Pos.CENTER_RIGHT);
            
            prevButton = new Button("Previous");
            prevButton.getStyleClass().addAll("secondary-button");
            
            nextButton = new Button("Next");
            nextButton.getStyleClass().addAll("primary-button");
            
            skipButton = new Button("Skip Tutorial");
            skipButton.getStyleClass().addAll("secondary-button");
            
            endButton = new Button("Finish");
            endButton.getStyleClass().addAll("primary-button");
            endButton.setVisible(false);
            
            buttonBox.getChildren().addAll(skipButton, prevButton, nextButton, endButton);
            
            getChildren().addAll(titleLabel, instructionLabel, progressBox, buttonBox);
        }
        
        public void setTutorialSystem(TutorialSystem system) {
            this.tutorialSystem = system;
            
            // Set up button actions
            prevButton.setOnAction(e -> system.previousStep());
            nextButton.setOnAction(e -> system.nextStep());
            skipButton.setOnAction(e -> system.skipTutorial());
            endButton.setOnAction(e -> system.endTutorial());
        }
        
        public void updateStep(HelpSystem.TutorialStep step, int stepIndex, int totalSteps) {
            titleLabel.setText(step.getTitle());
            instructionLabel.setText(step.getInstruction());
            
            progressBar.setProgress((double) (stepIndex + 1) / totalSteps);
            progressLabel.setText(String.format("Step %d of %d", stepIndex + 1, totalSteps));
            
            // Update button states
            prevButton.setDisable(stepIndex == 0);
            
            boolean isLastStep = stepIndex == totalSteps - 1;
            nextButton.setVisible(!isLastStep);
            endButton.setVisible(isLastStep);
        }
        
        public void setSkipVisible(boolean visible) {
            skipButton.setVisible(visible);
        }
    }
    
    public TutorialSystem() {
        initializeEffects();
    }
    
    private void initializeEffects() {
        // Create highlight effect
        highlightEffect = new DropShadow();
        highlightEffect.setColor(Color.color(0.4, 0.8, 1.0, 0.8));
        highlightEffect.setRadius(15);
        highlightEffect.setSpread(0.3);
        
        // Create pulse animation
        pulseAnimation = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(highlightEffect.radiusProperty(), 15)),
            new KeyFrame(Duration.seconds(1), new KeyValue(highlightEffect.radiusProperty(), 25)),
            new KeyFrame(Duration.seconds(2), new KeyValue(highlightEffect.radiusProperty(), 15))
        );
        pulseAnimation.setCycleCount(Timeline.INDEFINITE);
    }
    
    /**
     * Start an interactive tutorial
     */
    public void startTutorial(HelpSystem.Tutorial tutorial) {
        if (tutorial == null || tutorial.getSteps().isEmpty()) {
            logger.warn("Cannot start tutorial: invalid or empty tutorial");
            return;
        }
        
        currentTutorial = tutorial;
        currentStepIndex = -1;
        tutorialActive.set(true);
        totalSteps.set(tutorial.getSteps().size());
        
        logger.info("Starting tutorial: {} ({} steps)", tutorial.getTitle(), tutorial.getSteps().size());
        
        // Create UI components
        createTutorialUI();
        
        // Start first step
        nextStep();
    }
    
    /**
     * Create tutorial UI components
     */
    private void createTutorialUI() {
        Platform.runLater(() -> {
            // Create overlay stage
            overlayStage = new Stage();
            overlayStage.initStyle(StageStyle.TRANSPARENT);
            overlayStage.setAlwaysOnTop(true);
            
            overlay = new TutorialOverlay();
            Scene overlayScene = new Scene(overlay, Color.TRANSPARENT);
            overlayScene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
            overlayStage.setScene(overlayScene);
            
            // Create guide popup
            guide = new TutorialGuide();
            guide.setTutorialSystem(this);
            
            guidePopup = new Popup();
            guidePopup.getContent().add(guide);
            guidePopup.setAutoHide(false);
            guidePopup.setHideOnEscape(false);
            
            // Show overlay and guide
            showTutorialUI();
        });
    }
    
    /**
     * Show tutorial UI components
     */
    private void showTutorialUI() {
        // Find main application stage
        Stage mainStage = findMainStage();
        if (mainStage != null) {
            // Position overlay to cover main stage
            overlayStage.setX(mainStage.getX());
            overlayStage.setY(mainStage.getY());
            overlayStage.setWidth(mainStage.getWidth());
            overlayStage.setHeight(mainStage.getHeight());
            
            overlayStage.show();
            
            // Position guide popup
            double guideX = mainStage.getX() + mainStage.getWidth() - 450;
            double guideY = mainStage.getY() + 50;
            guidePopup.show(mainStage, guideX, guideY);
        }
    }
    
    /**
     * Find the main application stage
     */
    private Stage findMainStage() {
        // This would need to be provided by the application
        // For now, return null and handle gracefully
        return null;
    }
    
    /**
     * Go to next tutorial step
     */
    public void nextStep() {
        if (currentTutorial == null) return;
        
        // Validate current step if needed
        if (currentStepIndex >= 0) {
            HelpSystem.TutorialStep currentStepObj = currentTutorial.getSteps().get(currentStepIndex);
            if (currentStepObj.getValidator() != null && !currentStepObj.getValidator().isCompleted()) {
                // Step not completed, show message
                showStepIncompleteMessage();
                return;
            }
        }
        
        currentStepIndex++;
        
        if (currentStepIndex >= currentTutorial.getSteps().size()) {
            // Tutorial completed
            endTutorial();
            return;
        }
        
        currentStep.set(currentStepIndex + 1);
        progress.set((double) (currentStepIndex + 1) / currentTutorial.getSteps().size());
        
        HelpSystem.TutorialStep step = currentTutorial.getSteps().get(currentStepIndex);
        showStep(step);
        
        logger.debug("Tutorial step {}/{}: {}", currentStepIndex + 1, 
                    currentTutorial.getSteps().size(), step.getTitle());
    }
    
    /**
     * Go to previous tutorial step
     */
    public void previousStep() {
        if (currentTutorial == null || currentStepIndex <= 0) return;
        
        currentStepIndex--;
        currentStep.set(currentStepIndex + 1);
        progress.set((double) (currentStepIndex + 1) / currentTutorial.getSteps().size());
        
        HelpSystem.TutorialStep step = currentTutorial.getSteps().get(currentStepIndex);
        showStep(step);
        
        logger.debug("Tutorial step {}/{}: {}", currentStepIndex + 1, 
                    currentTutorial.getSteps().size(), step.getTitle());
    }
    
    /**
     * Show a tutorial step
     */
    private void showStep(HelpSystem.TutorialStep step) {
        Platform.runLater(() -> {
            // Update guide
            if (guide != null) {
                guide.updateStep(step, currentStepIndex, currentTutorial.getSteps().size());
            }
            
            // Highlight target element
            highlightTargetElement(step.getTargetElement());
            
            // Execute step action if provided
            if (step.getAction() != null) {
                CompletableFuture.runAsync(() -> step.getAction().accept(null))
                    .exceptionally(throwable -> {
                        logger.error("Error executing tutorial step action", throwable);
                        return null;
                    });
            }
        });
    }
    
    /**
     * Highlight target element for the current step
     */
    private void highlightTargetElement(String targetElementId) {
        if (targetElementId == null || targetElementId.isEmpty()) {
            clearHighlight();
            return;
        }
        
        // Find target element by ID
        Node targetNode = findElementById(targetElementId);
        
        if (targetNode != null) {
            // Highlight in overlay
            if (overlay != null) {
                overlay.highlightNode(targetNode, 80);
            }
            
            // Apply highlight effect
            targetNode.setEffect(highlightEffect);
            
            // Start pulse animation
            if (pulseAnimation != null) {
                pulseAnimation.play();
            }
            
            // Scroll target into view if possible
            scrollIntoView(targetNode);
            
        } else {
            logger.debug("Target element not found: {}", targetElementId);
            clearHighlight();
        }
    }
    
    /**
     * Find element by CSS ID
     */
    private Node findElementById(String id) {
        // This would need to search through the scene graph
        // For now, return null and handle gracefully
        return null;
    }
    
    /**
     * Scroll target element into view
     */
    private void scrollIntoView(Node targetNode) {
        // Find parent scroll pane and scroll to target
        Node parent = targetNode.getParent();
        while (parent != null && !(parent instanceof ScrollPane)) {
            parent = parent.getParent();
        }
        
        if (parent instanceof ScrollPane) {
            ScrollPane scrollPane = (ScrollPane) parent;
            
            // Calculate scroll position to center target
            Bounds targetBounds = targetNode.getBoundsInParent();
            Bounds viewportBounds = scrollPane.getViewportBounds();
            
            double targetCenterY = targetBounds.getCenterY();
            double viewportCenterY = viewportBounds.getHeight() / 2;
            
            double scrollY = (targetCenterY - viewportCenterY) / 
                           (scrollPane.getContent().getBoundsInLocal().getHeight() - viewportBounds.getHeight());
            
            scrollY = Math.max(0, Math.min(1, scrollY));
            
            // Animate scroll
            Timeline scrollAnimation = new Timeline(
                new KeyFrame(Duration.seconds(0.5), 
                    new KeyValue(scrollPane.vvalueProperty(), scrollY, Interpolator.EASE_BOTH))
            );
            scrollAnimation.play();
        }
    }
    
    /**
     * Clear element highlighting
     */
    private void clearHighlight() {
        if (overlay != null) {
            overlay.highlightNode(null, 0);
        }
        
        if (pulseAnimation != null) {
            pulseAnimation.stop();
        }
    }
    
    /**
     * Skip the current tutorial
     */
    public void skipTutorial() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Skip Tutorial");
        confirm.setHeaderText("Skip Tutorial?");
        confirm.setContentText("Are you sure you want to skip this tutorial? You can restart it later from the Help menu.");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                endTutorial();
                logger.info("Tutorial skipped by user: {}", 
                           currentTutorial != null ? currentTutorial.getTitle() : "unknown");
            }
        });
    }
    
    /**
     * End the current tutorial
     */
    public void endTutorial() {
        if (currentTutorial != null) {
            logger.info("Tutorial completed: {}", currentTutorial.getTitle());
            
            // Show completion message
            if (currentStepIndex >= currentTutorial.getSteps().size() - 1) {
                showTutorialCompletedMessage();
            }
        }
        
        // Clean up UI
        closeTutorialUI();
        
        // Reset state
        currentTutorial = null;
        currentStepIndex = -1;
        tutorialActive.set(false);
        currentStep.set(0);
        totalSteps.set(0);
        progress.set(0.0);
    }
    
    /**
     * Close tutorial UI components
     */
    private void closeTutorialUI() {
        Platform.runLater(() -> {
            clearHighlight();
            
            if (guidePopup != null) {
                guidePopup.hide();
                guidePopup = null;
            }
            
            if (overlayStage != null) {
                overlayStage.close();
                overlayStage = null;
            }
            
            overlay = null;
            guide = null;
        });
    }
    
    /**
     * Show step incomplete message
     */
    private void showStepIncompleteMessage() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Step Incomplete");
        alert.setHeaderText("Complete the current step");
        alert.setContentText("Please complete the current step before proceeding to the next one.");
        alert.showAndWait();
    }
    
    /**
     * Show tutorial completed message
     */
    private void showTutorialCompletedMessage() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Tutorial Completed");
        alert.setHeaderText("Congratulations!");
        alert.setContentText(String.format("You've successfully completed the '%s' tutorial. " +
                                           "You can now explore OpenMason on your own or try another tutorial.",
                                           currentTutorial.getTitle()));
        alert.showAndWait();
    }
    
    /**
     * Pause the current tutorial
     */
    public void pauseTutorial() {
        if (tutorialActive.get()) {
            closeTutorialUI();
            logger.info("Tutorial paused");
        }
    }
    
    /**
     * Resume the paused tutorial
     */
    public void resumeTutorial() {
        if (currentTutorial != null && !tutorialActive.get()) {
            tutorialActive.set(true);
            createTutorialUI();
            
            if (currentStepIndex >= 0 && currentStepIndex < currentTutorial.getSteps().size()) {
                showStep(currentTutorial.getSteps().get(currentStepIndex));
            }
            
            logger.info("Tutorial resumed");
        }
    }
    
    /**
     * Check if a tutorial is currently active
     */
    public boolean isTutorialActive() {
        return tutorialActive.get();
    }
    
    /**
     * Get current tutorial progress (0.0 to 1.0)
     */
    public double getTutorialProgress() {
        return progress.get();
    }
    
    // Property getters
    public BooleanProperty tutorialActiveProperty() { return tutorialActive; }
    public IntegerProperty currentStepProperty() { return currentStep; }
    public IntegerProperty totalStepsProperty() { return totalSteps; }
    public DoubleProperty progressProperty() { return progress; }
    
    public HelpSystem.Tutorial getCurrentTutorial() { return currentTutorial; }
    public int getCurrentStepIndex() { return currentStepIndex; }
    
    /**
     * Clean up resources
     */
    public void dispose() {
        endTutorial();
        
        if (pulseAnimation != null) {
            pulseAnimation.stop();
        }
        
        logger.info("Tutorial system disposed");
    }
}