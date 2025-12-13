package com.stonebreak.ui.terrainmapper.managers;

import com.stonebreak.ui.TextInputField;
import com.stonebreak.world.generation.TerrainGeneratorType;

/**
 * Manages the state for the Terrain Mapper screen.
 * Handles text input fields, map pan/zoom state, mouse interaction state,
 * and visualization mode selection.
 */
public class TerrainStateManager {

    // Text input fields
    private final TextInputField worldNameField;
    private final TextInputField seedField;
    private ActiveField activeField;

    // Map pan and zoom state
    private float panX;
    private float panY;
    private float zoom;

    // Mouse interaction state
    private boolean isDragging;
    private double lastMouseX;
    private double lastMouseY;

    // Button hover states
    private boolean backButtonHovered;
    private boolean createButtonHovered;
    private boolean simulateSeedButtonHovered;

    // Visualization state
    private VisualizationMode selectedVisualizationMode;
    private boolean visualizationActive;

    // Terrain generator selection
    private TerrainGeneratorType selectedGeneratorType;

    public enum ActiveField {
        WORLD_NAME,
        SEED,
        NONE
    }

    public enum VisualizationMode {
        TERRAIN_HEIGHT("Terrain Height"),
        CONTINENTALNESS("Continentalness"),
        EROSION("Erosion"),
        PEAKS_VALLEYS("Peaks & Valleys"),
        WEIRDNESS("Weirdness"),
        TEMPERATURE("Temperature"),
        HUMIDITY("Humidity"),
        BIOME_DISTRIBUTION("Biome Distribution");

        private final String displayName;

        VisualizationMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public TerrainStateManager() {
        // Initialize text input fields
        this.worldNameField = new TextInputField("My World");
        this.seedField = new TextInputField("Leave blank for random");
        this.activeField = ActiveField.WORLD_NAME;
        this.worldNameField.setFocused(true);

        // Initialize map state
        this.panX = 0;
        this.panY = 0;
        this.zoom = 1.0f;

        // Initialize mouse state
        this.isDragging = false;
        this.lastMouseX = 0;
        this.lastMouseY = 0;

        // Initialize button states
        this.backButtonHovered = false;
        this.createButtonHovered = false;
        this.simulateSeedButtonHovered = false;

        // Initialize visualization state
        this.selectedVisualizationMode = VisualizationMode.TERRAIN_HEIGHT;
        this.visualizationActive = false;

        // Initialize generator type (default to HYBRID_SDF for optimal performance)
        this.selectedGeneratorType = TerrainGeneratorType.HYBRID_SDF;
    }

    /**
     * Resets the state for a fresh world creation session.
     */
    public void reset() {
        worldNameField.setText("");
        worldNameField.setFocused(true);
        seedField.setText("");
        seedField.setFocused(false);
        activeField = ActiveField.WORLD_NAME;

        panX = 0;
        panY = 0;
        zoom = 1.0f;

        isDragging = false;
        backButtonHovered = false;
        createButtonHovered = false;
        simulateSeedButtonHovered = false;

        selectedVisualizationMode = VisualizationMode.TERRAIN_HEIGHT;
        visualizationActive = false;

        selectedGeneratorType = TerrainGeneratorType.HYBRID_SDF;
    }

    // Getters and setters for text input fields
    public TextInputField getWorldNameField() {
        return worldNameField;
    }

    public TextInputField getSeedField() {
        return seedField;
    }

    public ActiveField getActiveField() {
        return activeField;
    }

    public void setActiveField(ActiveField activeField) {
        this.activeField = activeField;

        // Update focus state of fields
        worldNameField.setFocused(activeField == ActiveField.WORLD_NAME);
        seedField.setFocused(activeField == ActiveField.SEED);
    }

    public void switchToNextField() {
        if (activeField == ActiveField.WORLD_NAME) {
            setActiveField(ActiveField.SEED);
        } else if (activeField == ActiveField.SEED) {
            setActiveField(ActiveField.WORLD_NAME);
        }
    }

    // Getters and setters for map pan/zoom
    public float getPanX() {
        return panX;
    }

    public void setPanX(float panX) {
        this.panX = panX;
    }

    public float getPanY() {
        return panY;
    }

    public void setPanY(float panY) {
        this.panY = panY;
    }

    public float getZoom() {
        return zoom;
    }

    public void setZoom(float zoom) {
        this.zoom = zoom;
    }

    public void adjustPan(float deltaX, float deltaY) {
        this.panX += deltaX;
        this.panY += deltaY;
    }

    // Getters and setters for mouse state
    public boolean isDragging() {
        return isDragging;
    }

    public void setDragging(boolean dragging) {
        isDragging = dragging;
    }

    public double getLastMouseX() {
        return lastMouseX;
    }

    public void setLastMouseX(double lastMouseX) {
        this.lastMouseX = lastMouseX;
    }

    public double getLastMouseY() {
        return lastMouseY;
    }

    public void setLastMouseY(double lastMouseY) {
        this.lastMouseY = lastMouseY;
    }

    // Getters and setters for button hover states
    public boolean isBackButtonHovered() {
        return backButtonHovered;
    }

    public void setBackButtonHovered(boolean hovered) {
        this.backButtonHovered = hovered;
    }

    public boolean isCreateButtonHovered() {
        return createButtonHovered;
    }

    public void setCreateButtonHovered(boolean hovered) {
        this.createButtonHovered = hovered;
    }

    public boolean isSimulateSeedButtonHovered() {
        return simulateSeedButtonHovered;
    }

    public void setSimulateSeedButtonHovered(boolean hovered) {
        this.simulateSeedButtonHovered = hovered;
    }

    // Getters and setters for visualization state
    public VisualizationMode getSelectedVisualizationMode() {
        return selectedVisualizationMode;
    }

    public void setSelectedVisualizationMode(VisualizationMode mode) {
        this.selectedVisualizationMode = mode;
    }

    public boolean isVisualizationActive() {
        return visualizationActive;
    }

    public void setVisualizationActive(boolean active) {
        this.visualizationActive = active;
    }

    // Getters and setters for terrain generator selection
    public TerrainGeneratorType getSelectedGeneratorType() {
        return selectedGeneratorType;
    }

    public void setSelectedGeneratorType(TerrainGeneratorType generatorType) {
        this.selectedGeneratorType = generatorType;
    }
}
