package com.openmason.ui.help;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context-sensitive help provider with smart tooltips, hover help,
 * and contextual assistance for professional user guidance.
 */
public class ContextHelpProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(ContextHelpProvider.class);
    
    // Help content cache
    private final Map<String, HelpSystem.ContextHelp> helpCache = new ConcurrentHashMap<>();
    private final Map<Node, String> nodeContextMap = new HashMap<>();
    
    // UI state
    private Popup currentHelpPopup;
    private ContextHelpTooltip currentTooltip;
    private String activeContext = "";
    private boolean helpEnabled = true;
    private int helpDelay = 1000; // milliseconds
    
    /**
     * Enhanced context help tooltip
     */
    private static class ContextHelpTooltip extends VBox {
        private final Label titleLabel;
        private final Label contentLabel;
        private final VBox tipsBox;
        private final HBox actionBox;
        private final Button detailsButton;
        private final Button closeButton;
        
        public ContextHelpTooltip() {
            setSpacing(10);
            setPadding(new Insets(15));
            setMaxWidth(350);
            getStyleClass().add("context-help-tooltip");
            
            // Styling
            setStyle(
                "-fx-background-color: #2b2b2b; " +
                "-fx-background-radius: 8; " +
                "-fx-border-color: #0099ff; " +
                "-fx-border-radius: 8; " +
                "-fx-border-width: 2;"
            );
            
            // Drop shadow
            DropShadow shadow = new DropShadow();
            shadow.setColor(Color.color(0, 0, 0, 0.6));
            shadow.setOffsetX(0);
            shadow.setOffsetY(4);
            shadow.setRadius(12);
            setEffect(shadow);
            
            // Title
            titleLabel = new Label();
            titleLabel.getStyleClass().addAll("context-help-title", "h4");
            titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold;");
            
            // Content
            contentLabel = new Label();
            contentLabel.getStyleClass().add("context-help-content");
            contentLabel.setWrapText(true);
            contentLabel.setStyle("-fx-text-fill: #e0e0e0;");
            
            // Tips section
            tipsBox = new VBox(5);
            tipsBox.getStyleClass().add("context-help-tips");
            
            // Action buttons
            actionBox = new HBox(10);
            actionBox.setAlignment(Pos.CENTER_RIGHT);
            
            detailsButton = new Button("More Details");
            detailsButton.getStyleClass().addAll("primary-button", "small-button");
            
            closeButton = new Button("✕");
            closeButton.getStyleClass().addAll("secondary-button", "small-button", "close-button");
            closeButton.setPrefSize(24, 24);
            closeButton.setMaxSize(24, 24);
            
            actionBox.getChildren().addAll(detailsButton, closeButton);
            
            getChildren().addAll(titleLabel, contentLabel, tipsBox, actionBox);
        }
        
        public void setContextHelp(HelpSystem.ContextHelp help) {
            titleLabel.setText(help.getTitle());
            contentLabel.setText(help.getQuickHelp());
            
            // Clear and populate tips
            tipsBox.getChildren().clear();
            
            if (!help.getQuickTips().isEmpty()) {
                Label tipsTitle = new Label("Quick Tips:");
                tipsTitle.setStyle("-fx-text-fill: #b0b0b0; -fx-font-weight: bold; -fx-font-size: 11px;");
                tipsBox.getChildren().add(tipsTitle);
                
                for (String tip : help.getQuickTips()) {
                    Label tipLabel = new Label("• " + tip);
                    tipLabel.setWrapText(true);
                    tipLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 11px;");
                    tipsBox.getChildren().add(tipLabel);
                }
            }
            
            // Set up details button
            detailsButton.setOnAction(e -> {
                if (help.getDetailedHelpTopicId() != null && !help.getDetailedHelpTopicId().isEmpty()) {
                    HelpSystem.getInstance().showHelp(help.getDetailedHelpTopicId());
                }
            });
            
            detailsButton.setVisible(help.getDetailedHelpTopicId() != null && 
                                   !help.getDetailedHelpTopicId().isEmpty());
        }
        
        public Button getCloseButton() {
            return closeButton;
        }
    }
    
    public ContextHelpProvider() {
        logger.info("Context help provider initialized");
    }
    
    /**
     * Register context help for a UI node
     */
    public void registerContextHelp(Node node, String contextId) {
        if (node == null || contextId == null) {
            return;
        }
        
        nodeContextMap.put(node, contextId);
        
        // Set up mouse event handlers
        node.setOnMouseEntered(this::handleMouseEntered);
        node.setOnMouseExited(this::handleMouseExited);
        node.setOnMouseMoved(this::handleMouseMoved);
        
        logger.debug("Registered context help for node: {}", contextId);
    }
    
    /**
     * Unregister context help for a UI node
     */
    public void unregisterContextHelp(Node node) {
        if (node == null) {
            return;
        }
        
        String contextId = nodeContextMap.remove(node);
        
        // Remove event handlers
        node.setOnMouseEntered(null);
        node.setOnMouseExited(null);
        node.setOnMouseMoved(null);
        
        if (contextId != null) {
            logger.debug("Unregistered context help for node: {}", contextId);
        }
    }
    
    /**
     * Add context help to the cache
     */
    public void addContextHelp(HelpSystem.ContextHelp help) {
        if (help != null) {
            helpCache.put(help.getContextId(), help);
            logger.debug("Added context help: {}", help.getContextId());
        }
    }
    
    /**
     * Show context help popup
     */
    public void showContextHelp(HelpSystem.ContextHelp help) {
        if (help == null || !helpEnabled) {
            return;
        }
        
        Platform.runLater(() -> {
            hideCurrentHelp();
            
            // Create tooltip
            currentTooltip = new ContextHelpTooltip();
            currentTooltip.setContextHelp(help);
            
            // Set up close button
            currentTooltip.getCloseButton().setOnAction(e -> hideCurrentHelp());
            
            // Create popup
            currentHelpPopup = new Popup();
            currentHelpPopup.getContent().add(currentTooltip);
            currentHelpPopup.setAutoHide(true);
            currentHelpPopup.setHideOnEscape(true);
            
            // Show popup
            Stage primaryStage = findPrimaryStage();
            if (primaryStage != null) {
                // Position near cursor
                double x = primaryStage.getX() + 100;
                double y = primaryStage.getY() + 100;
                
                currentHelpPopup.show(primaryStage, x, y);
                
                // Fade in animation
                FadeTransition fadeIn = new FadeTransition(Duration.millis(200), currentTooltip);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();
                
                activeContext = help.getContextId();
                logger.debug("Showed context help: {}", help.getContextId());
            }
        });
    }
    
    /**
     * Show context help for a specific context ID
     */
    public void showContextHelp(String contextId) {
        HelpSystem.ContextHelp help = helpCache.get(contextId);
        if (help == null) {
            help = HelpSystem.getInstance().getContextHelp(contextId);
        }
        
        if (help != null) {
            showContextHelp(help);
        } else {
            logger.debug("No context help found for: {}", contextId);
        }
    }
    
    /**
     * Show context help near a specific node
     */
    public void showContextHelpNear(Node node, HelpSystem.ContextHelp help) {
        if (node == null || help == null || !helpEnabled) {
            return;
        }
        
        Platform.runLater(() -> {
            hideCurrentHelp();
            
            // Create tooltip
            currentTooltip = new ContextHelpTooltip();
            currentTooltip.setContextHelp(help);
            
            // Set up close button
            currentTooltip.getCloseButton().setOnAction(e -> hideCurrentHelp());
            
            // Create popup
            currentHelpPopup = new Popup();
            currentHelpPopup.getContent().add(currentTooltip);
            currentHelpPopup.setAutoHide(true);
            currentHelpPopup.setHideOnEscape(true);
            
            // Position near the node
            try {
                Bounds nodeBounds = node.localToScreen(node.getBoundsInLocal());
                double x = nodeBounds.getMaxX() + 10;
                double y = nodeBounds.getMinY();
                
                // Ensure popup stays on screen
                double screenWidth = javafx.stage.Screen.getPrimary().getVisualBounds().getWidth();
                double screenHeight = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
                
                if (x + 350 > screenWidth) {
                    x = nodeBounds.getMinX() - 360;
                }
                
                if (y + 200 > screenHeight) {
                    y = nodeBounds.getMaxY() - 200;
                }
                
                currentHelpPopup.show(node, x, y);
                
                // Fade in animation
                FadeTransition fadeIn = new FadeTransition(Duration.millis(200), currentTooltip);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();
                
                activeContext = help.getContextId();
                logger.debug("Showed context help near node: {}", help.getContextId());
                
            } catch (Exception e) {
                logger.debug("Failed to position context help near node", e);
                // Fall back to default positioning
                showContextHelp(help);
            }
        });
    }
    
    /**
     * Hide current context help
     */
    public void hideCurrentHelp() {
        Platform.runLater(() -> {
            if (currentHelpPopup != null) {
                // Fade out animation
                if (currentTooltip != null) {
                    FadeTransition fadeOut = new FadeTransition(Duration.millis(150), currentTooltip);
                    fadeOut.setFromValue(1.0);
                    fadeOut.setToValue(0.0);
                    fadeOut.setOnFinished(e -> {
                        currentHelpPopup.hide();
                        currentHelpPopup = null;
                        currentTooltip = null;
                    });
                    fadeOut.play();
                } else {
                    currentHelpPopup.hide();
                    currentHelpPopup = null;
                }
                
                activeContext = "";
            }
        });
    }
    
    /**
     * Handle mouse entered event
     */
    private void handleMouseEntered(MouseEvent event) {
        if (!helpEnabled) return;
        
        Node source = (Node) event.getSource();
        String contextId = nodeContextMap.get(source);
        
        if (contextId != null && !contextId.equals(activeContext)) {
            // Schedule help popup with delay
            Platform.runLater(() -> {
                javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(Duration.millis(helpDelay), e -> {
                        if (nodeContextMap.containsKey(source)) { // Check if still registered
                            showContextHelpNear(source, getContextHelp(contextId));
                        }
                    })
                );
                timeline.play();
            });
        }
    }
    
    /**
     * Handle mouse exited event
     */
    private void handleMouseExited(MouseEvent event) {
        // Hide help after a short delay
        Platform.runLater(() -> {
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(300), e -> {
                    if (!isMouseOverHelp()) {
                        hideCurrentHelp();
                    }
                })
            );
            timeline.play();
        });
    }
    
    /**
     * Handle mouse moved event
     */
    private void handleMouseMoved(MouseEvent event) {
        // Update help position if needed
        // This could be used for following the mouse cursor
    }
    
    /**
     * Check if mouse is over help popup
     */
    private boolean isMouseOverHelp() {
        // This would check if the mouse is over the help popup
        // For now, return false to allow hiding
        return false;
    }
    
    /**
     * Get context help by ID
     */
    private HelpSystem.ContextHelp getContextHelp(String contextId) {
        HelpSystem.ContextHelp help = helpCache.get(contextId);
        if (help == null) {
            help = HelpSystem.getInstance().getContextHelp(contextId);
            if (help != null) {
                helpCache.put(contextId, help);
            }
        }
        return help;
    }
    
    /**
     * Find primary stage
     */
    private Stage findPrimaryStage() {
        // This would need to be provided by the application
        // For now, return null and handle gracefully
        return null;
    }
    
    /**
     * Enable or disable context help
     */
    public void setHelpEnabled(boolean enabled) {
        this.helpEnabled = enabled;
        
        if (!enabled) {
            hideCurrentHelp();
        }
        
        logger.info("Context help {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Set help popup delay in milliseconds
     */
    public void setHelpDelay(int delayMs) {
        this.helpDelay = Math.max(0, delayMs);
        logger.debug("Context help delay set to: {}ms", this.helpDelay);
    }
    
    /**
     * Check if help is currently visible
     */
    public boolean isHelpVisible() {
        return currentHelpPopup != null && currentHelpPopup.isShowing();
    }
    
    /**
     * Get current active context
     */
    public String getActiveContext() {
        return activeContext;
    }
    
    /**
     * Clear all registered context help
     */
    public void clearAll() {
        hideCurrentHelp();
        
        // Remove event handlers from all registered nodes
        for (Node node : nodeContextMap.keySet()) {
            node.setOnMouseEntered(null);
            node.setOnMouseExited(null);
            node.setOnMouseMoved(null);
        }
        
        nodeContextMap.clear();
        helpCache.clear();
        
        logger.info("Cleared all context help registrations");
    }
    
    /**
     * Get help statistics
     */
    public String getStatistics() {
        return String.format("Context Help: %d registered nodes, %d cached help items, %s",
                nodeContextMap.size(), helpCache.size(), 
                helpEnabled ? "enabled" : "disabled");
    }
    
    /**
     * Dispose resources
     */
    public void dispose() {
        hideCurrentHelp();
        clearAll();
        logger.info("Context help provider disposed");
    }
}