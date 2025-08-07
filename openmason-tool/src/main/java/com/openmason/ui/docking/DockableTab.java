package com.openmason.ui.docking;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom tab implementation for dockable panels with context menu,
 * drag-and-drop support, and enhanced visual feedback.
 */
public class DockableTab extends Tab {
    
    private static final Logger logger = LoggerFactory.getLogger(DockableTab.class);
    
    private final DockablePanel panel;
    private ContextMenu contextMenu;
    private boolean isDragging = false;
    private HBox tabHeader;
    
    /**
     * Create a new dockable tab for a panel
     */
    public DockableTab(DockablePanel panel) {
        super();
        this.panel = panel;
        
        initializeTab();
        setupEventHandlers();
        createContextMenu();
        
        logger.debug("Created dockable tab for panel: {}", panel.getId());
    }
    
    /**
     * Initialize tab properties and content
     */
    private void initializeTab() {
        // Create custom tab header with mouse event support
        Label titleLabel = new Label();
        titleLabel.textProperty().bind(panel.titleProperty());
        
        tabHeader = new HBox();
        tabHeader.setAlignment(Pos.CENTER_LEFT);
        tabHeader.getChildren().add(titleLabel);
        
        // Set the custom header as the tab's graphic
        setGraphic(tabHeader);
        
        // Set tab content to panel content
        setContent(panel.getOrCreateContent());
        
        // Bind closeable property
        closableProperty().bind(panel.closeableProperty());
        
        // Style the tab
        getStyleClass().add("dockable-tab");
        
        // Handle tab closing
        setOnCloseRequest(event -> {
            if (panel.isCloseable()) {
                panel.close();
            } else {
                event.consume();
            }
        });
    }
    
    /**
     * Create custom tab header with pin button and status indicators
     */
    private Region createTabHeader() {
        HBox header = new HBox(4);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tab-header");
        
        // Tab title label
        Label titleLabel = new Label();
        titleLabel.textProperty().bind(panel.titleProperty());
        titleLabel.getStyleClass().add("tab-title");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        
        // Pin button
        Button pinButton = new Button();
        pinButton.getStyleClass().addAll("tab-pin-button", "small-button");
        pinButton.setPrefSize(16, 16);
        pinButton.setMaxSize(16, 16);
        
        // Update pin button appearance based on panel state
        updatePinButton(pinButton);
        panel.pinnedProperty().addListener((obs, oldVal, newVal) -> updatePinButton(pinButton));
        
        pinButton.setOnAction(e -> {
            e.consume();
            panel.togglePin();
        });
        
        // Close button (only if closeable)
        Button closeButton = new Button("×");
        closeButton.getStyleClass().addAll("tab-close-button", "small-button");
        closeButton.setPrefSize(16, 16);
        closeButton.setMaxSize(16, 16);
        closeButton.visibleProperty().bind(panel.closeableProperty());
        closeButton.managedProperty().bind(panel.closeableProperty());
        
        closeButton.setOnAction(e -> {
            e.consume();
            if (panel.isCloseable()) {
                panel.close();
            }
        });
        
        header.getChildren().addAll(titleLabel, pinButton, closeButton);
        
        return header;
    }
    
    /**
     * Update pin button appearance
     */
    private void updatePinButton(Button pinButton) {
        if (panel.isPinned()) {
            pinButton.setText("📌");
            pinButton.setTooltip(new Tooltip("Unpin panel"));
            pinButton.getStyleClass().remove("unpinned");
            pinButton.getStyleClass().add("pinned");
        } else {
            pinButton.setText("📌");
            pinButton.setTooltip(new Tooltip("Pin panel"));
            pinButton.getStyleClass().remove("pinned");
            pinButton.getStyleClass().add("unpinned");
        }
    }
    
    /**
     * Set up event handlers for drag and drop
     */
    private void setupEventHandlers() {
        // Mouse event handlers for drag detection on tab header
        tabHeader.setOnMousePressed(this::handleMousePressed);
        tabHeader.setOnMouseDragged(this::handleMouseDragged);
        tabHeader.setOnMouseReleased(this::handleMouseReleased);
        
        // Context menu on tab header
        tabHeader.setOnContextMenuRequested(event -> {
            if (contextMenu != null) {
                contextMenu.show(tabHeader, event.getScreenX(), event.getScreenY());
            }
        });
        
        // Double-click to detach on tab header
        tabHeader.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if (panel.isDetachable() && panel.getState() != DockablePanel.PanelState.FLOATING) {
                    panel.detach();
                }
            }
        });
    }
    
    /**
     * Handle mouse pressed for drag start
     */
    private void handleMousePressed(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && panel.isDetachable()) {
            // Store initial position for drag detection
            event.consume();
        }
    }
    
    /**
     * Handle mouse dragged for drag operation
     */
    private void handleMouseDragged(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && panel.isDetachable() && !isDragging) {
            // Start drag operation
            startDragOperation(event);
        }
    }
    
    /**
     * Handle mouse released for drag end
     */
    private void handleMouseReleased(MouseEvent event) {
        if (isDragging) {
            endDragOperation(event);
        }
    }
    
    /**
     * Start drag operation
     */
    private void startDragOperation(MouseEvent event) {
        isDragging = true;
        getStyleClass().add("dragging");
        
        // In a full implementation, this would:
        // 1. Create a drag preview window
        // 2. Hide the original tab
        // 3. Track mouse movement for drop targets
        
        logger.debug("Started drag operation for panel: {}", panel.getId());
    }
    
    /**
     * End drag operation
     */
    private void endDragOperation(MouseEvent event) {
        isDragging = false;
        getStyleClass().remove("dragging");
        
        // In a full implementation, this would:
        // 1. Determine drop target
        // 2. Move panel to new location
        // 3. Clean up drag preview
        
        logger.debug("Ended drag operation for panel: {}", panel.getId());
    }
    
    /**
     * Create context menu for tab
     */
    private void createContextMenu() {
        contextMenu = new ContextMenu();
        contextMenu.getStyleClass().add("dockable-tab-context-menu");
        
        // Dock position submenu
        Menu dockMenu = new Menu("Dock To");
        dockMenu.getStyleClass().add("dock-submenu");
        
        for (DockablePanel.DockPosition position : DockablePanel.DockPosition.values()) {
            if (position != DockablePanel.DockPosition.FLOATING) {
                MenuItem dockItem = new MenuItem(position.getDisplayName());
                dockItem.setOnAction(e -> panel.dock(position));
                
                // Disable current position
                if (position == panel.getDockPosition()) {
                    dockItem.setDisable(true);
                }
                
                dockMenu.getItems().add(dockItem);
            }
        }
        
        // Detach/Float
        MenuItem detachItem = new MenuItem("Float");
        detachItem.setOnAction(e -> panel.detach());
        detachItem.disableProperty().bind(
            Bindings.not(panel.detachableProperty())
            .or(Bindings.equal(panel.stateProperty(), DockablePanel.PanelState.FLOATING))
        );
        
        // Pin/Unpin
        MenuItem pinItem = new MenuItem();
        pinItem.textProperty().bind(Bindings.when(panel.pinnedProperty())
                .then("Unpin")
                .otherwise("Pin"));
        pinItem.setOnAction(e -> panel.togglePin());
        
        // Minimize
        MenuItem minimizeItem = new MenuItem("Minimize");
        minimizeItem.setOnAction(e -> panel.minimize());
        minimizeItem.disableProperty().bind(
            Bindings.equal(panel.stateProperty(), DockablePanel.PanelState.MINIMIZED)
        );
        
        // Separator
        SeparatorMenuItem separator1 = new SeparatorMenuItem();
        
        // Hide
        MenuItem hideItem = new MenuItem("Hide");
        hideItem.setOnAction(e -> panel.hide());
        
        // Close
        MenuItem closeItem = new MenuItem("Close");
        closeItem.setOnAction(e -> panel.close());
        closeItem.disableProperty().bind(Bindings.not(panel.closeableProperty()));
        
        // Separator
        SeparatorMenuItem separator2 = new SeparatorMenuItem();
        
        // Properties
        MenuItem propertiesItem = new MenuItem("Properties...");
        propertiesItem.setOnAction(e -> showPanelProperties());
        
        contextMenu.getItems().addAll(
            dockMenu,
            detachItem,
            separator1,
            pinItem,
            minimizeItem,
            hideItem,
            closeItem,
            separator2,
            propertiesItem
        );
    }
    
    /**
     * Show panel properties dialog
     */
    private void showPanelProperties() {
        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("Panel Properties");
        dialog.setHeaderText("Properties for: " + panel.getTitle());
        
        StringBuilder content = new StringBuilder();
        content.append("ID: ").append(panel.getId()).append("\n");
        content.append("Category: ").append(panel.getCategory()).append("\n");
        content.append("Position: ").append(panel.getDockPosition().getDisplayName()).append("\n");
        content.append("State: ").append(panel.getState()).append("\n");
        content.append("Size: ").append(String.format("%.0f x %.0f", 
                                               panel.getPreferredWidth(), 
                                               panel.getPreferredHeight())).append("\n");
        content.append("Closeable: ").append(panel.isCloseable()).append("\n");
        content.append("Detachable: ").append(panel.isDetachable()).append("\n");
        content.append("Pinned: ").append(panel.isPinned()).append("\n");
        
        dialog.setContentText(content.toString());
        dialog.showAndWait();
    }
    
    /**
     * Get the associated panel
     */
    public DockablePanel getPanel() {
        return panel;
    }
    
    /**
     * Check if tab is currently being dragged
     */
    public boolean isDragging() {
        return isDragging;
    }
    
    /**
     * Update tab appearance based on panel state
     */
    public void updateTabAppearance() {
        // Remove all state classes
        getStyleClass().removeAll("docked", "floating", "minimized", "hidden", "pinned", "unpinned");
        
        // Add current state class
        switch (panel.getState()) {
            case DOCKED:
                getStyleClass().add("docked");
                break;
            case FLOATING:
                getStyleClass().add("floating");
                break;
            case MINIMIZED:
                getStyleClass().add("minimized");
                break;
            case HIDDEN:
                getStyleClass().add("hidden");
                break;
        }
        
        // Add pin state class
        if (panel.isPinned()) {
            getStyleClass().add("pinned");
        } else {
            getStyleClass().add("unpinned");
        }
        
        // Update tooltip
        setTooltip(new Tooltip(String.format("%s (%s)\nRight-click for options, double-click to float",
                panel.getTitle(), panel.getCategory())));
    }
    
    @Override
    public String toString() {
        return String.format("DockableTab{panel=%s, dragging=%s}", 
                panel.getId(), isDragging);
    }
}