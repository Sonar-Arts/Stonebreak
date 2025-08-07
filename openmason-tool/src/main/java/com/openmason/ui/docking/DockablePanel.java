package com.openmason.ui.docking;

import javafx.beans.property.*;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dockable panel that can be embedded in the docking system.
 * Provides professional panel management with state persistence and customization.
 */
public class DockablePanel {
    
    private static final Logger logger = LoggerFactory.getLogger(DockablePanel.class);
    
    // Properties
    private final StringProperty id = new SimpleStringProperty();
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty category = new SimpleStringProperty();
    private final ObjectProperty<Node> content = new SimpleObjectProperty<>();
    private final BooleanProperty closeable = new SimpleBooleanProperty(true);
    private final BooleanProperty detachable = new SimpleBooleanProperty(true);
    private final BooleanProperty resizable = new SimpleBooleanProperty(true);
    private final BooleanProperty visible = new SimpleBooleanProperty(true);
    private final BooleanProperty pinned = new SimpleBooleanProperty(false);
    private final DoubleProperty preferredWidth = new SimpleDoubleProperty(250);
    private final DoubleProperty preferredHeight = new SimpleDoubleProperty(300);
    private final DoubleProperty minWidth = new SimpleDoubleProperty(150);
    private final DoubleProperty minHeight = new SimpleDoubleProperty(100);
    private final ObjectProperty<DockPosition> dockPosition = new SimpleObjectProperty<>(DockPosition.LEFT);
    private final ObjectProperty<PanelState> state = new SimpleObjectProperty<>(PanelState.DOCKED);
    
    // Internal state
    private DockingSystem dockingSystem;
    private DockableTab tab;
    private Region tabContent;
    
    /**
     * Panel docking positions
     */
    public enum DockPosition {
        LEFT("Left"),
        RIGHT("Right"),
        TOP("Top"),
        BOTTOM("Bottom"),
        CENTER("Center"),
        FLOATING("Floating");
        
        private final String displayName;
        
        DockPosition(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Panel states
     */
    public enum PanelState {
        DOCKED,      // Panel is docked in the main window
        FLOATING,    // Panel is in a floating window
        MINIMIZED,   // Panel is minimized to tab only
        HIDDEN       // Panel is completely hidden
    }
    
    /**
     * Create a new dockable panel
     */
    public DockablePanel(String id, String title, Node content) {
        this.id.set(id);
        this.title.set(title);
        this.content.set(content);
        this.category.set("General");
        
        logger.debug("Created dockable panel: {} ({})", title, id);
    }
    
    /**
     * Create a new dockable panel with category
     */
    public DockablePanel(String id, String title, String category, Node content) {
        this(id, title, content);
        this.category.set(category);
    }
    
    // Property getters and setters
    public String getId() { return id.get(); }
    public void setId(String id) { this.id.set(id); }
    public StringProperty idProperty() { return id; }
    
    public String getTitle() { return title.get(); }
    public void setTitle(String title) { this.title.set(title); }
    public StringProperty titleProperty() { return title; }
    
    public String getCategory() { return category.get(); }
    public void setCategory(String category) { this.category.set(category); }
    public StringProperty categoryProperty() { return category; }
    
    public Node getContent() { return content.get(); }
    public void setContent(Node content) { this.content.set(content); }
    public ObjectProperty<Node> contentProperty() { return content; }
    
    public boolean isCloseable() { return closeable.get(); }
    public void setCloseable(boolean closeable) { this.closeable.set(closeable); }
    public BooleanProperty closeableProperty() { return closeable; }
    
    public boolean isDetachable() { return detachable.get(); }
    public void setDetachable(boolean detachable) { this.detachable.set(detachable); }
    public BooleanProperty detachableProperty() { return detachable; }
    
    public boolean isResizable() { return resizable.get(); }
    public void setResizable(boolean resizable) { this.resizable.set(resizable); }
    public BooleanProperty resizableProperty() { return resizable; }
    
    public boolean isVisible() { return visible.get(); }
    public void setVisible(boolean visible) { this.visible.set(visible); }
    public BooleanProperty visibleProperty() { return visible; }
    
    public boolean isPinned() { return pinned.get(); }
    public void setPinned(boolean pinned) { this.pinned.set(pinned); }
    public BooleanProperty pinnedProperty() { return pinned; }
    
    public double getPreferredWidth() { return preferredWidth.get(); }
    public void setPreferredWidth(double width) { this.preferredWidth.set(width); }
    public DoubleProperty preferredWidthProperty() { return preferredWidth; }
    
    public double getPreferredHeight() { return preferredHeight.get(); }
    public void setPreferredHeight(double height) { this.preferredHeight.set(height); }
    public DoubleProperty preferredHeightProperty() { return preferredHeight; }
    
    public double getMinWidth() { return minWidth.get(); }
    public void setMinWidth(double minWidth) { this.minWidth.set(minWidth); }
    public DoubleProperty minWidthProperty() { return minWidth; }
    
    public double getMinHeight() { return minHeight.get(); }
    public void setMinHeight(double minHeight) { this.minHeight.set(minHeight); }
    public DoubleProperty minHeightProperty() { return minHeight; }
    
    public DockPosition getDockPosition() { return dockPosition.get(); }
    public void setDockPosition(DockPosition position) { this.dockPosition.set(position); }
    public ObjectProperty<DockPosition> dockPositionProperty() { return dockPosition; }
    
    public PanelState getState() { return state.get(); }
    public void setState(PanelState state) { this.state.set(state); }
    public ObjectProperty<PanelState> stateProperty() { return state; }
    
    /**
     * Show the panel
     */
    public void show() {
        setVisible(true);
        if (dockingSystem != null) {
            dockingSystem.showPanel(this);
        }
        logger.debug("Showed panel: {}", getId());
    }
    
    /**
     * Hide the panel
     */
    public void hide() {
        setVisible(false);
        if (dockingSystem != null) {
            dockingSystem.hidePanel(this);
        }
        logger.debug("Hidden panel: {}", getId());
    }
    
    /**
     * Close the panel
     */
    public void close() {
        if (isCloseable() && dockingSystem != null) {
            dockingSystem.closePanel(this);
            logger.debug("Closed panel: {}", getId());
        }
    }
    
    /**
     * Dock the panel to a specific position
     */
    public void dock(DockPosition position) {
        setDockPosition(position);
        setState(PanelState.DOCKED);
        if (dockingSystem != null) {
            dockingSystem.dockPanel(this, position);
        }
        logger.debug("Docked panel {} to {}", getId(), position);
    }
    
    /**
     * Detach the panel to a floating window
     */
    public void detach() {
        if (isDetachable()) {
            setState(PanelState.FLOATING);
            if (dockingSystem != null) {
                dockingSystem.detachPanel(this);
            }
            logger.debug("Detached panel: {}", getId());
        }
    }
    
    /**
     * Minimize the panel to tab only
     */
    public void minimize() {
        setState(PanelState.MINIMIZED);
        if (dockingSystem != null) {
            dockingSystem.minimizePanel(this);
        }
        logger.debug("Minimized panel: {}", getId());
    }
    
    /**
     * Restore the panel from minimized state
     */
    public void restore() {
        setState(PanelState.DOCKED);
        if (dockingSystem != null) {
            dockingSystem.restorePanel(this);
        }
        logger.debug("Restored panel: {}", getId());
    }
    
    /**
     * Toggle pin state
     */
    public void togglePin() {
        setPinned(!isPinned());
        logger.debug("Toggled pin for panel {}: {}", getId(), isPinned());
    }
    
    /**
     * Get orientation preference based on dock position
     */
    public Orientation getPreferredOrientation() {
        switch (getDockPosition()) {
            case LEFT:
            case RIGHT:
                return Orientation.VERTICAL;
            case TOP:
            case BOTTOM:
                return Orientation.HORIZONTAL;
            default:
                return Orientation.VERTICAL;
        }
    }
    
    /**
     * Create default content if none provided
     */
    public Node getOrCreateContent() {
        Node contentNode = getContent();
        if (contentNode == null) {
            Label placeholder = new Label("Panel: " + getTitle());
            placeholder.getStyleClass().add("panel-placeholder");
            return placeholder;
        }
        return contentNode;
    }
    
    /**
     * Set the parent docking system
     */
    void setDockingSystem(DockingSystem dockingSystem) {
        this.dockingSystem = dockingSystem;
    }
    
    /**
     * Get the parent docking system
     */
    public DockingSystem getDockingSystem() {
        return dockingSystem;
    }
    
    /**
     * Set the tab representation
     */
    void setTab(DockableTab tab) {
        this.tab = tab;
    }
    
    /**
     * Get the tab representation
     */
    public DockableTab getTab() {
        return tab;
    }
    
    /**
     * Set the tab content region
     */
    void setTabContent(Region tabContent) {
        this.tabContent = tabContent;
    }
    
    /**
     * Get the tab content region
     */
    public Region getTabContent() {
        return tabContent;
    }
    
    /**
     * Check if this panel can be combined with another in the same dock area
     */
    public boolean canCombineWith(DockablePanel other) {
        if (other == null) {
            return false;
        }
        
        // Panels in the same category can be combined
        if (getCategory().equals(other.getCategory())) {
            return true;
        }
        
        // Panels in the same dock position can be combined
        return getDockPosition() == other.getDockPosition();
    }
    
    /**
     * Serialize panel state for persistence
     */
    public PanelState serializeState() {
        // Return the current state of the panel
        return getState();
    }
    
    /**
     * Restore panel state from serialized data
     */
    public void restoreState(PanelState savedState) {
        // In a full implementation, this would restore from serialized state
        logger.debug("Restored state for panel: {}", getId());
    }
    
    /**
     * Get a summary of the panel configuration
     */
    public String getConfigurationSummary() {
        return String.format("Panel[id=%s, title=%s, position=%s, state=%s, size=%.0fx%.0f]",
                getId(), getTitle(), getDockPosition(), getState(), 
                getPreferredWidth(), getPreferredHeight());
    }
    
    @Override
    public String toString() {
        return String.format("DockablePanel{id='%s', title='%s', position=%s}", 
                getId(), getTitle(), getDockPosition());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        DockablePanel that = (DockablePanel) obj;
        return getId().equals(that.getId());
    }
    
    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}