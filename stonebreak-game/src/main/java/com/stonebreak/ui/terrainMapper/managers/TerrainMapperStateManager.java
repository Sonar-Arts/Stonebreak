package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.ui.terrainMapper.components.TerrainMapViewport;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * All mutable state for the terrain mapper screen. Pure state plus widget
 * references — no rendering, no input dispatch, no business actions. Handlers
 * and renderers collaborate around this object.
 *
 * Name + seed are stored as plain Strings (matching the Skija world-select
 * pattern) rather than a NanoVG-backed {@code TextInputField}; the sidebar
 * renderer draws the field and the input handler mutates the string
 * directly. Keeps the screen on a single Skija path and avoids pulling a
 * NanoVG frame into the MasonryUI contract.
 */
public final class TerrainMapperStateManager {

    /** Which field receives character input. */
    public enum ActiveField { NONE, WORLD_NAME, SEED }

    // ─────────────────────────────────────────────── Widgets
    private final MButton backButton;
    private final MButton createButton;
    private final MButton simulateSeedButton;
    private final List<MCategoryButton<VisualizerKind>> modeButtons = new ArrayList<>();
    private final MButton setSpawnButton;
    private final MButton centerOnSpawnButton;

    // ─────────────────────────────────────────────── Spawn point
    private boolean spawnSet = false;
    private int spawnWorldX;
    private int spawnWorldZ;

    // ─────────────────────────────────────────────── Text fields
    private String worldName = "";
    private String seedText = "";
    private ActiveField activeField = ActiveField.WORLD_NAME;
    private String errorMessage;

    // ─────────────────────────────────────────────── Visualization
    private VisualizerKind activeVisualizer = VisualizerKind.HEIGHT;
    private final VisualizerRegistry visualizers;

    // ─────────────────────────────────────────────── Viewport + cache
    private final TerrainMapViewport viewport = new TerrainMapViewport();
    private final TerrainPreviewCache previewCache = new TerrainPreviewCache();

    // ─────────────────────────────────────────────── Map drag
    private boolean dragging;
    private float lastDragX;
    private float lastDragY;
    private long lastZoomNanos;

    // ─────────────────────────────────────────────── Hover readout
    private boolean hasHoverValue;
    private int hoverWorldX;
    private int hoverWorldZ;
    private float hoverRawValue;

    public TerrainMapperStateManager() {
        long initialSeed = new java.util.Random().nextLong();
        this.seedText = Long.toString(initialSeed);
        this.visualizers = new VisualizerRegistry(initialSeed);

        this.backButton = new MButton("Back")
                .size(TerrainMapperConfig.FOOTER_BUTTON_WIDTH, TerrainMapperConfig.FOOTER_BUTTON_HEIGHT);
        this.createButton = new MButton("Create World")
                .size(TerrainMapperConfig.FOOTER_BUTTON_WIDTH, TerrainMapperConfig.FOOTER_BUTTON_HEIGHT);
        this.simulateSeedButton = new MButton("Simulate Seed")
                .size(TerrainMapperConfig.FOOTER_BUTTON_WIDTH, TerrainMapperConfig.FOOTER_BUTTON_HEIGHT);

        for (VisualizerKind kind : VisualizerKind.values()) {
            MCategoryButton<VisualizerKind> button = new MCategoryButton<>(kind, kind.displayName());
            button.size(TerrainMapperConfig.SIDEBAR_WIDTH - TerrainMapperConfig.SIDEBAR_PADDING * 2f,
                    TerrainMapperConfig.MODE_BUTTON_HEIGHT);
            modeButtons.add(button);
        }

        float btnWidth = TerrainMapperConfig.SIDEBAR_WIDTH - TerrainMapperConfig.SIDEBAR_PADDING * 2f;
        this.setSpawnButton = new MButton("Click map to set spawn")
                .enabled(false)
                .size(btnWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);
        this.centerOnSpawnButton = new MButton("Center on Spawn")
                .enabled(false)
                .size(btnWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);
    }

    // ─────────────────────────────────────────────── Widgets

    public MButton getBackButton() { return backButton; }
    public MButton getCreateButton() { return createButton; }
    public MButton getSimulateSeedButton() { return simulateSeedButton; }
    public List<MCategoryButton<VisualizerKind>> getModeButtons() { return modeButtons; }
    public MButton getSetSpawnButton() { return setSpawnButton; }
    public MButton getCenterOnSpawnButton() { return centerOnSpawnButton; }

    // ─────────────────────────────────────────────── Spawn point

    public boolean hasSpawnPoint() { return spawnSet; }
    public int spawnWorldX() { return spawnWorldX; }
    public int spawnWorldZ() { return spawnWorldZ; }

    public void setSpawnPoint(int worldX, int worldZ) {
        spawnSet = true;
        spawnWorldX = worldX;
        spawnWorldZ = worldZ;
        setSpawnButton.setText("Random Spawn?");
        setSpawnButton.setEnabled(true);
        centerOnSpawnButton.setEnabled(true);
    }

    public void clearSpawnPoint() {
        spawnSet = false;
        setSpawnButton.setText("Click map to set spawn");
        setSpawnButton.setEnabled(false);
        centerOnSpawnButton.setEnabled(false);
    }

    // ─────────────────────────────────────────────── Text

    public String getWorldName() { return worldName; }
    public String getSeedText() { return seedText; }
    public ActiveField getActiveField() { return activeField; }

    public void setActiveField(ActiveField field) {
        this.activeField = field == null ? ActiveField.NONE : field;
    }

    public void appendToActiveField(char character) {
        if (Character.isSurrogate(character)) return;
        switch (activeField) {
            case WORLD_NAME -> appendWorldName(character);
            case SEED       -> appendSeed(character);
            default         -> {}
        }
    }

    public void backspaceActiveField() {
        switch (activeField) {
            case WORLD_NAME -> {
                if (!worldName.isEmpty()) worldName = worldName.substring(0, worldName.length() - 1);
            }
            case SEED -> {
                if (!seedText.isEmpty()) {
                    seedText = seedText.substring(0, seedText.length() - 1);
                    refreshSeed();
                }
            }
            default -> {}
        }
    }

    public void toggleActiveField() {
        activeField = switch (activeField) {
            case WORLD_NAME -> ActiveField.SEED;
            case SEED, NONE -> ActiveField.WORLD_NAME;
        };
    }

    public void setWorldName(String value) {
        this.worldName = value == null ? "" : value;
    }

    public void setSeedText(String value) {
        this.seedText = value == null ? "" : value;
        refreshSeed();
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String message) { this.errorMessage = message; }

    private void appendWorldName(char c) {
        if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
            if (worldName.length() < TerrainMapperConfig.MAX_WORLD_NAME_LENGTH) {
                worldName += c;
                errorMessage = null;
            }
        }
    }

    private void appendSeed(char c) {
        if (Character.isLetterOrDigit(c) || c == '-') {
            if (seedText.length() < TerrainMapperConfig.MAX_SEED_LENGTH) {
                seedText += c;
                refreshSeed();
            }
        }
    }

    // ─────────────────────────────────────────────── Visualizer

    public VisualizerKind getActiveVisualizer() { return activeVisualizer; }

    public void setActiveVisualizer(VisualizerKind kind) {
        if (kind == null || kind == activeVisualizer) return;
        this.activeVisualizer = kind;
    }

    public VisualizerRegistry getVisualizers() { return visualizers; }

    // ─────────────────────────────────────────────── Seed

    /** Derive a deterministic long seed from the current seed text. */
    public long getResolvedSeed() {
        return deriveSeed(seedText);
    }

    public void randomizeSeed() {
        long random = new Random().nextLong();
        this.seedText = Long.toString(random);
        refreshSeed();
    }

    private void refreshSeed() {
        visualizers.rebuild(getResolvedSeed());
    }

    private static long deriveSeed(String text) {
        if (text == null || text.isBlank()) return 0L;
        String trimmed = text.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed.hashCode();
        }
    }

    // ─────────────────────────────────────────────── Viewport + cache

    public TerrainMapViewport getViewport() { return viewport; }
    public TerrainPreviewCache getPreviewCache() { return previewCache; }

    // ─────────────────────────────────────────────── Drag state

    public boolean isDragging() { return dragging; }

    public void beginDrag(float x, float y) {
        this.dragging = true;
        this.lastDragX = x;
        this.lastDragY = y;
    }

    public void endDrag() {
        this.dragging = false;
    }

    /** Mark the viewport as actively zooming so the next rebuild uses interactive quality. */
    public void markZoomInteracting() {
        this.lastZoomNanos = System.nanoTime();
    }

    /**
     * True while the user is panning, or within the zoom cooldown window.
     * The cache uses this to pick a coarser sample step and stay at 60 fps.
     */
    public boolean isInteracting() {
        if (dragging) return true;
        return (System.nanoTime() - lastZoomNanos) < TerrainMapperConfig.ZOOM_COOLDOWN_NANOS;
    }

    public int effectiveSampleStep() {
        return isInteracting()
                ? TerrainMapperConfig.SAMPLE_STEP_INTERACTIVE_PX
                : TerrainMapperConfig.SAMPLE_STEP_PX;
    }

    public float consumeDragDx(float x) {
        float dx = x - lastDragX;
        lastDragX = x;
        return dx;
    }

    public float consumeDragDy(float y) {
        float dy = y - lastDragY;
        lastDragY = y;
        return dy;
    }

    // ─────────────────────────────────────────────── Hover

    public boolean hasHoverValue() { return hasHoverValue; }
    public int hoverWorldX() { return hoverWorldX; }
    public int hoverWorldZ() { return hoverWorldZ; }
    public float hoverRawValue() { return hoverRawValue; }

    public void setHoverValue(int worldX, int worldZ, float raw) {
        this.hasHoverValue = true;
        this.hoverWorldX = worldX;
        this.hoverWorldZ = worldZ;
        this.hoverRawValue = raw;
    }

    public void clearHoverValue() {
        this.hasHoverValue = false;
    }

    // ─────────────────────────────────────────────── Reset

    public void reset() {
        worldName = "";
        errorMessage = null;
        activeField = ActiveField.WORLD_NAME;
        activeVisualizer = VisualizerKind.HEIGHT;
        dragging = false;
        hasHoverValue = false;
        clearSpawnPoint();
        viewport.reset();
        long newSeed = new java.util.Random().nextLong();
        seedText = Long.toString(newSeed);
        visualizers.rebuild(newSeed);
    }

    public void dispose() {
        previewCache.dispose();
    }
}
