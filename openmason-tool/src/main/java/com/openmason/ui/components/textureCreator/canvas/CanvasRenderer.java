package com.openmason.ui.components.textureCreator.canvas;

import com.openmason.ui.components.textureCreator.SymmetryState;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.selection.SelectionRenderer;
import com.openmason.ui.components.textureCreator.utils.SymmetryHelper;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * OpenGL-based canvas renderer.
 *
 * Handles uploading pixel data to GPU texture and rendering to ImGui.
 * Follows SOLID principles - Single Responsibility: only renders canvas.
 *
 * @author Open Mason Team
 */
public class CanvasRenderer {

    private static final Logger logger = LoggerFactory.getLogger(CanvasRenderer.class);

    // OpenGL texture ID
    private int textureId = -1;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // Grid rendering settings (visible on checkerboard background)
    // Note: Alpha values are now controlled by preferences (gridOpacity and quadrantOverlayOpacity)
    private static final float GRID_LINE_THICKNESS = 1.5f;                        // Slightly thicker for better visibility

    // Transparency checkerboard settings (like Photoshop/GIMP)
    private static final int CHECKER_COLOR_LIGHT = ImColor.rgba(204, 204, 204, 255);  // Light gray
    private static final int CHECKER_COLOR_DARK = ImColor.rgba(153, 153, 153, 255);   // Dark gray
    private static final int CHECKER_SIZE = 8;  // Size of each checker square in pixels

    // Canvas border settings
    private static final int CANVAS_BORDER_COLOR = ImColor.rgba(0, 0, 0, 255);  // Solid black border
    private static final float CANVAS_BORDER_THICKNESS = 1.0f;

    // Cube net overlay renderer (for 64x48 textures)
    private final CubeNetOverlayRenderer cubeNetOverlayRenderer;

    // Selection renderer
    private final SelectionRenderer selectionRenderer;

    /**
     * Create canvas renderer.
     */
    public CanvasRenderer() {
        this.cubeNetOverlayRenderer = new CubeNetOverlayRenderer();
        this.selectionRenderer = new SelectionRenderer();
        logger.debug("Canvas renderer created");
    }

    /**
     * Initialize or update the OpenGL texture with canvas data.
     *
     * @param canvas pixel canvas to render
     */
    public void uploadTexture(PixelCanvas canvas) {
        if (canvas == null) {
            logger.warn("Cannot upload null canvas");
            return;
        }

        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // Create new texture if needed
        if (textureId == -1 || textureWidth != width || textureHeight != height) {
            createTexture(width, height);
        }

        // Upload pixel data to GPU
        updateTexture(canvas);
    }

    /**
     * Create OpenGL texture.
     *
     * @param width texture width
     * @param height texture height
     */
    private void createTexture(int width, int height) {
        // Delete old texture if exists
        if (textureId != -1) {
            glDeleteTextures(textureId);
        }

        // Generate new texture
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Set texture parameters for pixel art
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); // No filtering for crisp pixels
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Allocate texture storage
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        glBindTexture(GL_TEXTURE_2D, 0);

        this.textureWidth = width;
        this.textureHeight = height;

        logger.debug("Created OpenGL texture: {}x{}, ID={}", width, height, textureId);
    }

    /**
     * Update texture with current canvas pixel data.
     *
     * @param canvas pixel canvas
     */
    private void updateTexture(PixelCanvas canvas) {
        if (textureId == -1) {
            logger.warn("Cannot update texture - not initialized");
            return;
        }

        // Convert pixel data to byte buffer
        byte[] pixelBytes = canvas.getPixelsAsRGBABytes();
        ByteBuffer buffer = BufferUtils.createByteBuffer(pixelBytes.length);
        buffer.put(pixelBytes);
        buffer.flip();

        // Upload to GPU
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, canvas.getWidth(), canvas.getHeight(),
                       GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Render canvas to ImGui window.
     *
     * @param canvas pixel canvas to render
     * @param canvasState canvas view state (zoom, pan)
     * @param showGrid whether to show grid overlay
     * @param gridOpacity opacity for grid lines (0.0 to 1.0)
     * @param cubeNetOverlayOpacity opacity for cube net overlay (0.0 to 1.0)
     * @param currentSelection current active selection region (nullable)
     * @param selectionPreviewBounds preview selection bounds during drag [startX, startY, endX, endY] (nullable)
     * @param symmetryState symmetry/mirror mode state (nullable)
     */
    public void render(PixelCanvas canvas, CanvasState canvasState, boolean showGrid,
                      float gridOpacity, float cubeNetOverlayOpacity,
                      SelectionRegion currentSelection, int[] selectionPreviewBounds,
                      SymmetryState symmetryState) {
        if (canvas == null || canvasState == null) {
            ImGui.text("No canvas");
            return;
        }

        // Upload texture data
        uploadTexture(canvas);

        if (textureId == -1) {
            ImGui.text("Texture not initialized");
            return;
        }

        // Calculate display size based on zoom
        float zoom = canvasState.getZoomLevel();
        float displayWidth = canvas.getWidth() * zoom;
        float displayHeight = canvas.getHeight() * zoom;

        // Get available space and calculate center position
        ImVec2 availableRegion = ImGui.getContentRegionAvail();
        float centerOffsetX = Math.max(0, (availableRegion.x - displayWidth) / 2.0f);
        float centerOffsetY = Math.max(0, (availableRegion.y - displayHeight) / 2.0f);

        // Set cursor to centered position (relative to window)
        ImGui.setCursorPos(centerOffsetX + canvasState.getPanOffsetX(),
                          centerOffsetY + canvasState.getPanOffsetY());

        // Get absolute screen position for ImDrawList rendering
        ImVec2 imagePos = ImGui.getCursorScreenPos();
        float canvasX = imagePos.x;
        float canvasY = imagePos.y;

        // Render transparency checkerboard background first
        renderCheckerboard(canvas, canvasState, canvasX, canvasY, displayWidth, displayHeight);

        // Render grid if enabled (after checkerboard, before cube net overlay and pixels)
        if (showGrid) {
            renderGrid(canvas, canvasState, canvasX, canvasY, gridOpacity);
        }

        // Render cube net overlay (for 64x48 textures) after grid but before pixels
        cubeNetOverlayRenderer.render(canvas.getWidth(), canvas.getHeight(),
                                      canvasX, canvasY, zoom, cubeNetOverlayOpacity);

        // Render texture as image on top of everything rendered so far
        // Note: cursor is already at correct position from setCursorPos above
        ImGui.image(textureId, displayWidth, displayHeight,
                   0, 0, 1, 1, // UV coordinates (normal)
                   1, 1, 1, 1, // Tint color (white = no tint)
                   0, 0, 0, 0);// Border color (none)

        // Render symmetry axes if enabled
        if (symmetryState != null && symmetryState.isActive() && symmetryState.isShowAxisLines()) {
            renderSymmetryAxes(canvas, canvasState, canvasX, canvasY, symmetryState);
        }

        // Render selection overlay
        renderSelection(canvasX, canvasY, zoom, currentSelection, selectionPreviewBounds);

        // Render canvas border on top of everything
        renderCanvasBorder(canvasX, canvasY, displayWidth, displayHeight);
    }

    /**
     * Render selection overlay (both active selection and preview during drag).
     *
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     * @param currentSelection active selection region (nullable)
     * @param selectionPreviewBounds preview bounds [startX, startY, endX, endY] (nullable)
     */
    private void renderSelection(float canvasX, float canvasY, float zoom,
                                 SelectionRegion currentSelection, int[] selectionPreviewBounds) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Render preview selection during drag
        if (selectionPreviewBounds != null && selectionPreviewBounds.length == 4) {
            selectionRenderer.renderPreview(drawList,
                    selectionPreviewBounds[0], selectionPreviewBounds[1],
                    selectionPreviewBounds[2], selectionPreviewBounds[3],
                    canvasX, canvasY, zoom);
        }
        // Render active selection (if no preview is being dragged)
        // Note: renderSelection() dispatches to correct renderer for rectangle or free-form selections
        else if (currentSelection != null && !currentSelection.isEmpty()) {
            selectionRenderer.renderSelection(drawList, currentSelection, canvasX, canvasY, zoom);
        }
    }

    /**
     * Render border around the canvas to define its boundaries.
     *
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param displayWidth canvas display width
     * @param displayHeight canvas display height
     */
    private void renderCanvasBorder(float canvasX, float canvasY, float displayWidth, float displayHeight) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addRect(canvasX, canvasY,
                        canvasX + displayWidth, canvasY + displayHeight,
                        CANVAS_BORDER_COLOR, 0.0f, 0, CANVAS_BORDER_THICKNESS);
    }

    /**
     * Render transparency checkerboard background (like image editors).
     *
     * @param canvas pixel canvas
     * @param canvasState canvas view state
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param displayWidth canvas display width
     * @param displayHeight canvas display height
     */
    private void renderCheckerboard(PixelCanvas canvas, CanvasState canvasState,
                                    float canvasX, float canvasY,
                                    float displayWidth, float displayHeight) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Calculate number of checker squares needed
        int numSquaresX = (int) Math.ceil(displayWidth / CHECKER_SIZE);
        int numSquaresY = (int) Math.ceil(displayHeight / CHECKER_SIZE);

        // Draw checkerboard pattern
        for (int y = 0; y < numSquaresY; y++) {
            for (int x = 0; x < numSquaresX; x++) {
                // Alternate colors in checkerboard pattern
                boolean isLight = (x + y) % 2 == 0;
                int color = isLight ? CHECKER_COLOR_LIGHT : CHECKER_COLOR_DARK;

                // Calculate square position and size
                float squareX = canvasX + x * CHECKER_SIZE;
                float squareY = canvasY + y * CHECKER_SIZE;
                float squareEndX = Math.min(squareX + CHECKER_SIZE, canvasX + displayWidth);
                float squareEndY = Math.min(squareY + CHECKER_SIZE, canvasY + displayHeight);

                // Draw filled rectangle
                drawList.addRectFilled(squareX, squareY, squareEndX, squareEndY, color);
            }
        }
    }

    /**
     * Render grid overlay on canvas.
     * Uses the same opacity for both minor and major grid lines, but with different color intensity.
     *
     * @param canvas pixel canvas
     * @param canvasState canvas view state
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param gridOpacity opacity for all grid lines (0.0 to 1.0)
     */
    private void renderGrid(PixelCanvas canvas, CanvasState canvasState, float canvasX, float canvasY,
                           float gridOpacity) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float zoom = canvasState.getZoomLevel();

        // Only draw grid if zoomed in enough to see individual pixels clearly
        if (zoom < 3.0f) {
            return; // Grid would be too dense
        }

        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // Calculate alpha value (0-255) from opacity (0.0-1.0)
        int baseAlpha = (int) (gridOpacity * 255.0f);

        // Create grid colors with same opacity but different intensity
        // Minor lines: lighter (60% intensity) for subtle guidance
        // Major lines: darker (100% intensity) for stronger quadrant divisions
        int minorLineColor = ImColor.rgba(0, 0, 0, (int)(baseAlpha * 0.6f));  // 60% intensity
        int majorLineColor = ImColor.rgba(0, 0, 0, baseAlpha);                // 100% intensity

        // Draw vertical lines
        for (int x = 0; x <= width; x++) {
            float lineX = canvasX + x * zoom;
            float startY = canvasY;
            float endY = canvasY + height * zoom;

            // Every 4th line is a major line for quadrant division
            int color = (x % 4 == 0) ? majorLineColor : minorLineColor;

            drawList.addLine(lineX, startY, lineX, endY, color, GRID_LINE_THICKNESS);
        }

        // Draw horizontal lines
        for (int y = 0; y <= height; y++) {
            float lineY = canvasY + y * zoom;
            float startX = canvasX;
            float endX = canvasX + width * zoom;

            // Every 4th line is a major line for quadrant division
            int color = (y % 4 == 0) ? majorLineColor : minorLineColor;

            drawList.addLine(startX, lineY, endX, lineY, color, GRID_LINE_THICKNESS);
        }
    }

    /**
     * Render symmetry axis lines.
     *
     * @param canvas pixel canvas
     * @param canvasState canvas view state
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param symmetryState symmetry state
     */
    private void renderSymmetryAxes(PixelCanvas canvas, CanvasState canvasState,
                                     float canvasX, float canvasY, SymmetryState symmetryState) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float zoom = canvasState.getZoomLevel();

        // Calculate axis positions at the VISUAL center of the canvas
        // For rendering, we want the visual midpoint, not the pixel index midpoint
        float visualCenterX = (canvas.getWidth() / 2.0f) + symmetryState.getAxisOffsetX();
        float visualCenterY = (canvas.getHeight() / 2.0f) + symmetryState.getAxisOffsetY();

        // Convert to screen space
        float screenCenterX = canvasX + visualCenterX * zoom;
        float screenCenterY = canvasY + visualCenterY * zoom;

        // Calculate canvas bounds in screen space
        float canvasEndX = canvasX + canvas.getWidth() * zoom;
        float canvasEndY = canvasY + canvas.getHeight() * zoom;

        // Define axis line colors (semi-transparent, bright colors for visibility)
        int horizontalAxisColor = ImColor.rgba(255, 0, 0, 180); // Red for horizontal axis
        int verticalAxisColor = ImColor.rgba(0, 100, 255, 180);   // Blue for vertical axis
        float axisThickness = 2.0f;

        // Render based on symmetry mode
        switch (symmetryState.getMode()) {
            case HORIZONTAL:
                // Draw horizontal axis line (Y-axis)
                drawList.addLine(canvasX, screenCenterY, canvasEndX, screenCenterY,
                    horizontalAxisColor, axisThickness);
                break;

            case VERTICAL:
                // Draw vertical axis line (X-axis)
                drawList.addLine(screenCenterX, canvasY, screenCenterX, canvasEndY,
                    verticalAxisColor, axisThickness);
                break;

            case QUADRANT:
                // Draw both axes
                drawList.addLine(canvasX, screenCenterY, canvasEndX, screenCenterY,
                    horizontalAxisColor, axisThickness);
                drawList.addLine(screenCenterX, canvasY, screenCenterX, canvasEndY,
                    verticalAxisColor, axisThickness);
                break;

            case NONE:
            default:
                // No axes to render
                break;
        }
    }

    /**
     * Get OpenGL texture ID.
     * @return texture ID, or -1 if not initialized
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Cleanup OpenGL resources.
     */
    public void dispose() {
        if (textureId != -1) {
            glDeleteTextures(textureId);
            textureId = -1;
            logger.debug("Canvas texture disposed");
        }
    }
}
