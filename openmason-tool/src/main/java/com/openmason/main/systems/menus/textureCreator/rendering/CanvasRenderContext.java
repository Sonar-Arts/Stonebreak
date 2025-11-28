package com.openmason.main.systems.menus.textureCreator.rendering;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.CanvasState;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Encapsulates all context needed for canvas rendering.
 * Uses Builder pattern to avoid parameter explosion (was 13 parameters!).
 * Follows SOLID principles: Single Responsibility, Dependency Inversion.
 */
public class CanvasRenderContext {
    // Canvas data
    private final PixelCanvas compositedCanvas;
    private final PixelCanvas activeCanvas;
    private final PixelCanvas backgroundCanvas;

    // State
    private final CanvasState canvasState;
    private final DrawingTool currentTool;
    private final int currentColor;
    private final SelectionRegion currentSelection;

    // Preferences
    private final boolean showGrid;
    private final float gridOpacity;
    private final float cubeNetOverlayOpacity;

    // Command system
    private final CommandHistory commandHistory;

    // Callbacks
    private final Runnable onLayerModified;
    private final IntConsumer onColorPicked;
    private final IntConsumer onColorUsed;
    private final Consumer<SelectionRegion> onSelectionCreated;

    private CanvasRenderContext(Builder builder) {
        this.compositedCanvas = builder.compositedCanvas;
        this.activeCanvas = builder.activeCanvas;
        this.backgroundCanvas = builder.backgroundCanvas;
        this.canvasState = builder.canvasState;
        this.currentTool = builder.currentTool;
        this.currentColor = builder.currentColor;
        this.currentSelection = builder.currentSelection;
        this.showGrid = builder.showGrid;
        this.gridOpacity = builder.gridOpacity;
        this.cubeNetOverlayOpacity = builder.cubeNetOverlayOpacity;
        this.commandHistory = builder.commandHistory;
        this.onLayerModified = builder.onLayerModified;
        this.onColorPicked = builder.onColorPicked;
        this.onColorUsed = builder.onColorUsed;
        this.onSelectionCreated = builder.onSelectionCreated;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public PixelCanvas getCompositedCanvas() { return compositedCanvas; }
    public PixelCanvas getActiveCanvas() { return activeCanvas; }
    public PixelCanvas getBackgroundCanvas() { return backgroundCanvas; }
    public CanvasState getCanvasState() { return canvasState; }
    public DrawingTool getCurrentTool() { return currentTool; }
    public int getCurrentColor() { return currentColor; }
    public SelectionRegion getCurrentSelection() { return currentSelection; }
    public boolean isShowGrid() { return showGrid; }
    public float getGridOpacity() { return gridOpacity; }
    public float getCubeNetOverlayOpacity() { return cubeNetOverlayOpacity; }
    public CommandHistory getCommandHistory() { return commandHistory; }
    public Runnable getOnLayerModified() { return onLayerModified; }
    public IntConsumer getOnColorPicked() { return onColorPicked; }
    public IntConsumer getOnColorUsed() { return onColorUsed; }
    public Consumer<SelectionRegion> getOnSelectionCreated() { return onSelectionCreated; }

    /**
     * Fluent builder for CanvasRenderContext.
     * Eliminates parameter explosion and improves readability.
     */
    public static class Builder {
        private PixelCanvas compositedCanvas;
        private PixelCanvas activeCanvas;
        private PixelCanvas backgroundCanvas;
        private CanvasState canvasState;
        private DrawingTool currentTool;
        private int currentColor;
        private SelectionRegion currentSelection;
        private boolean showGrid;
        private float gridOpacity;
        private float cubeNetOverlayOpacity;
        private CommandHistory commandHistory;
        private Runnable onLayerModified;
        private IntConsumer onColorPicked;
        private IntConsumer onColorUsed;
        private Consumer<SelectionRegion> onSelectionCreated;

        public Builder compositedCanvas(PixelCanvas canvas) {
            this.compositedCanvas = canvas;
            return this;
        }

        public Builder activeCanvas(PixelCanvas canvas) {
            this.activeCanvas = canvas;
            return this;
        }

        public Builder backgroundCanvas(PixelCanvas canvas) {
            this.backgroundCanvas = canvas;
            return this;
        }

        public Builder canvasState(CanvasState state) {
            this.canvasState = state;
            return this;
        }

        public Builder currentTool(DrawingTool tool) {
            this.currentTool = tool;
            return this;
        }

        public Builder currentColor(int color) {
            this.currentColor = color;
            return this;
        }

        public Builder currentSelection(SelectionRegion selection) {
            this.currentSelection = selection;
            return this;
        }

        public Builder showGrid(boolean show) {
            this.showGrid = show;
            return this;
        }

        public Builder gridOpacity(float opacity) {
            this.gridOpacity = opacity;
            return this;
        }

        public Builder cubeNetOverlayOpacity(float opacity) {
            this.cubeNetOverlayOpacity = opacity;
            return this;
        }

        public Builder commandHistory(CommandHistory history) {
            this.commandHistory = history;
            return this;
        }

        public Builder onLayerModified(Runnable callback) {
            this.onLayerModified = callback;
            return this;
        }

        public Builder onColorPicked(IntConsumer callback) {
            this.onColorPicked = callback;
            return this;
        }

        public Builder onColorUsed(IntConsumer callback) {
            this.onColorUsed = callback;
            return this;
        }

        public Builder onSelectionCreated(Consumer<SelectionRegion> callback) {
            this.onSelectionCreated = callback;
            return this;
        }

        public CanvasRenderContext build() {
            return new CanvasRenderContext(this);
        }
    }
}
