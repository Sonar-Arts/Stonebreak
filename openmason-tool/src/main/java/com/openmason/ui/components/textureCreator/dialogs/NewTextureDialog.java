package com.openmason.ui.components.textureCreator.dialogs;

import com.openmason.ui.components.textureCreator.TextureCreatorState;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for creating a new texture with canvas size selection.
 *
 * Provides visual previews of both canvas types (16x16 and 64x48 cube net)
 * and allows user to select which type to create.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only handles new texture dialog UI
 * - Open/Closed: Extensible for additional canvas sizes
 * - Dependency Inversion: Returns selection instead of calling controller directly
 *
 * Design principles:
 * - KISS: Simple modal popup with radio buttons
 * - DRY: Reusable preview rendering methods
 * - YAGNI: No complex template system or image caching
 *
 * @author Open Mason Team
 */
public class NewTextureDialog {

    private static final Logger logger = LoggerFactory.getLogger(NewTextureDialog.class);

    // Dialog state
    private boolean isOpen = false;
    private TextureCreatorState.CanvasSize selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
    private TextureCreatorState.CanvasSize confirmedSelection = null;
    private boolean needsPositioning = false;

    // Dialog dimensions
    private static final float DIALOG_WIDTH = 520.0f;
    private static final float DIALOG_HEIGHT = 380.0f;
    private static final float MIN_WINDOW_WIDTH = 520.0f;
    private static final float MIN_WINDOW_HEIGHT = 380.0f;
    private static final float MAX_WINDOW_WIDTH = 1200.0f;
    private static final float MAX_WINDOW_HEIGHT = 800.0f;

    // Preview dimensions
    private static final float PREVIEW_SIZE = 180.0f; // Square preview area
    private static final float PREVIEW_SPACING = 30.0f;

    // Visual styling constants
    private static final int BACKGROUND_COLOR = ImColor.rgba(245, 245, 245, 255); // Light gray
    private static final int GRID_COLOR = ImColor.rgba(200, 200, 200, 255); // Medium gray
    private static final int BORDER_COLOR = ImColor.rgba(120, 120, 120, 255); // Dark gray
    private static final int FACE_COLOR = ImColor.rgba(100, 150, 255, 255); // Blue
    private static final int TEXT_COLOR = ImColor.rgba(60, 60, 60, 255); // Dark gray text
    private static final int NON_EDITABLE_COLOR = ImColor.rgba(200, 200, 200, 200); // Light gray transparent
    private static final int PREVIEW_BORDER_COLOR = ImColor.rgba(200, 200, 200, 255); // Light border for previews

    // Radio button styling
    private static final int RADIO_BORDER_SELECTED = ImColor.rgba(100, 150, 255, 255); // Blue border
    private static final int RADIO_BG_SELECTED = ImColor.rgba(100, 150, 255, 40); // Light blue background
    private static final int RADIO_BORDER_UNSELECTED = ImColor.rgba(160, 160, 160, 255); // Gray border
    private static final int RADIO_BG_UNSELECTED = ImColor.rgba(240, 240, 240, 255); // Light gray background

    /**
     * Create new texture dialog.
     */
    public NewTextureDialog() {
        logger.debug("New texture dialog created");
    }

    /**
     * Show the dialog (opens modal popup).
     */
    public void show() {
        isOpen = true;
        confirmedSelection = null;
        needsPositioning = true; // Center on first open
        // Reset to default selection
        selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
        logger.debug("New texture dialog opened");
    }

    /**
     * Check if dialog is currently open.
     *
     * @return true if open, false otherwise
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Get confirmed canvas size selection.
     * Returns the selected size if user clicked "Create", null otherwise.
     * Resets to null after being read.
     *
     * @return selected canvas size or null
     */
    public TextureCreatorState.CanvasSize getSelectedCanvasSize() {
        TextureCreatorState.CanvasSize result = confirmedSelection;
        confirmedSelection = null; // Reset after reading
        return result;
    }

    /**
     * Render the dialog.
     * Call this every frame from the main render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // Set window size constraints (min/max)
        ImGui.setNextWindowSizeConstraints(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT,
                                          MAX_WINDOW_WIDTH, MAX_WINDOW_HEIGHT);

        // Set initial size and position only on first open
        if (needsPositioning) {
            ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
            ImGui.setNextWindowPos(
                ImGui.getMainViewport().getSizeX() / 2.0f - DIALOG_WIDTH / 2.0f,
                ImGui.getMainViewport().getSizeY() / 2.0f - DIALOG_HEIGHT / 2.0f
            );
            needsPositioning = false;
        }

        // Window is now resizable (NoResize flag removed)
        if (ImGui.beginPopupModal("New Texture")) {

            // Header
            ImGui.spacing();
            String headerText = "Create New Texture";
            ImVec2 headerSize = ImGui.calcTextSize(headerText);
            ImGui.setCursorPosX((DIALOG_WIDTH - headerSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(80, 80, 80, 255));
            ImGui.text(headerText);
            ImGui.popStyleColor();
            ImGui.spacing();

            String subText = "Choose a texture size to begin";
            ImVec2 subSize = ImGui.calcTextSize(subText);
            ImGui.setCursorPosX((DIALOG_WIDTH - subSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(140, 140, 140, 255));
            ImGui.text(subText);
            ImGui.popStyleColor();

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.spacing();

            // Render previews and selection
            renderCanvasSelection();

            ImGui.spacing();
            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Buttons
            float buttonWidth = 120.0f;
            float buttonHeight = 32.0f;
            float totalButtonWidth = buttonWidth * 2 + ImGui.getStyle().getItemSpacingX();
            float buttonStartX = (DIALOG_WIDTH - totalButtonWidth) / 2.0f;

            ImGui.setCursorPosX(buttonStartX);

            if (ImGui.button("Create", buttonWidth, buttonHeight)) {
                confirmedSelection = selectedSize;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("Created new texture: {}", selectedSize.getDisplayName());
            }

            ImGui.sameLine();

            if (ImGui.button("Cancel", buttonWidth, buttonHeight)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.debug("New texture dialog cancelled");
            }

            // Handle ESC key to close
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Escape))) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open the modal popup if we just set isOpen to true
        if (isOpen && !ImGui.isPopupOpen("New Texture")) {
            ImGui.openPopup("New Texture");
        }
    }

    /**
     * Render canvas selection with previews and radio buttons.
     */
    private void renderCanvasSelection() {
        // Calculate positions for centered previews
        float totalWidth = PREVIEW_SIZE * 2 + PREVIEW_SPACING;
        float startX = (DIALOG_WIDTH - totalWidth) / 2.0f;

        ImGui.setCursorPosX(startX);

        boolean is16Selected = selectedSize == TextureCreatorState.CanvasSize.SIZE_16x16;
        boolean is64Selected = selectedSize == TextureCreatorState.CanvasSize.SIZE_64x48;

        // Left column - 16x16
        ImGui.beginGroup();
        {
            // Preview with subtle border
            ImVec2 cursorPos = ImGui.getCursorScreenPos();
            ImDrawList drawList = ImGui.getWindowDrawList();

            // Draw preview border
            drawList.addRect(cursorPos.x - 1, cursorPos.y - 1,
                           cursorPos.x + PREVIEW_SIZE + 1, cursorPos.y + PREVIEW_SIZE + 1,
                           PREVIEW_BORDER_COLOR, 2.0f, 0, 1.0f);

            renderPreviewFor16x16(cursorPos.x, cursorPos.y, PREVIEW_SIZE);
            ImGui.dummy(PREVIEW_SIZE, PREVIEW_SIZE); // Reserve space

            // Label
            ImGui.spacing();
            ImGui.spacing();
            String label16 = "16x16 Block/Item Texture";
            ImVec2 textSize16 = ImGui.calcTextSize(label16);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - textSize16.x) / 2.0f);
            ImGui.text(label16);

            // Description
            ImGui.spacing();
            String desc16 = "Standard texture size";
            ImVec2 descSize16 = ImGui.calcTextSize(desc16);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - descSize16.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(120, 120, 120, 255));
            ImGui.text(desc16);
            ImGui.popStyleColor();

            // Radio button - centered
            ImGui.spacing();
            ImGui.spacing();
            String radioLabel16 = "Select 16x16";
            ImVec2 radioSize16 = ImGui.calcTextSize(radioLabel16);

            // Increase spacing between circle and text
            float increasedSpacing = 12.0f;
            float radioWidth16 = radioSize16.x + ImGui.getFrameHeight() + increasedSpacing;

            // Center the radio button
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - radioWidth16) / 2.0f);

            // Calculate the circle's center position and radius BEFORE rendering
            float circleRadius16 = ImGui.getFrameHeight() / 2.0f;
            float circleCenterX16 = ImGui.getCursorScreenPos().x + circleRadius16;
            float circleCenterY16 = ImGui.getCursorScreenPos().y + circleRadius16;

            // Draw border around the circle BEFORE radio button renders
            drawRadioButtonBorder(ImGui.getWindowDrawList(), circleCenterX16, circleCenterY16,
                                circleRadius16, is16Selected);

            // Push increased spacing style
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemInnerSpacing, increasedSpacing, ImGui.getStyle().getItemInnerSpacingY());

            // Render the radio button
            if (ImGui.radioButton(radioLabel16, is16Selected)) {
                selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
            }

            // Pop spacing style
            ImGui.popStyleVar();
        }
        ImGui.endGroup();

        ImGui.sameLine(0, PREVIEW_SPACING);

        // Right column - 64x48
        ImGui.beginGroup();
        {
            // Preview with subtle border
            ImVec2 cursorPos = ImGui.getCursorScreenPos();
            ImDrawList drawList = ImGui.getWindowDrawList();

            float aspectRatio = 64.0f / 48.0f;
            float previewWidth = PREVIEW_SIZE;
            float previewHeight = PREVIEW_SIZE / aspectRatio;
            float yOffset = (PREVIEW_SIZE - previewHeight) / 2.0f;

            // Draw preview border
            drawList.addRect(cursorPos.x - 1, cursorPos.y + yOffset - 1,
                           cursorPos.x + previewWidth + 1, cursorPos.y + yOffset + previewHeight + 1,
                           PREVIEW_BORDER_COLOR, 2.0f, 0, 1.0f);

            renderPreviewFor64x48(cursorPos.x, cursorPos.y + yOffset, previewWidth, previewHeight);
            ImGui.dummy(PREVIEW_SIZE, PREVIEW_SIZE); // Reserve space (maintain alignment)

            // Label
            ImGui.spacing();
            ImGui.spacing();
            String label64 = "64x48 Block Texture";
            ImVec2 textSize64 = ImGui.calcTextSize(label64);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - textSize64.x) / 2.0f);
            ImGui.text(label64);

            // Description
            ImGui.spacing();
            String desc64 = "Cube net layout";
            ImVec2 descSize64 = ImGui.calcTextSize(desc64);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - descSize64.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(120, 120, 120, 255));
            ImGui.text(desc64);
            ImGui.popStyleColor();

            // Radio button - centered
            ImGui.spacing();
            ImGui.spacing();
            String radioLabel64 = "Select 64x48";
            ImVec2 radioSize64 = ImGui.calcTextSize(radioLabel64);

            // Increase spacing between circle and text
            float increasedSpacing = 12.0f;
            float radioWidth64 = radioSize64.x + ImGui.getFrameHeight() + increasedSpacing;

            // Center the radio button
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - radioWidth64) / 2.0f);

            // Calculate the circle's center position and radius BEFORE rendering
            float circleRadius64 = ImGui.getFrameHeight() / 2.0f;
            float circleCenterX64 = ImGui.getCursorScreenPos().x + circleRadius64;
            float circleCenterY64 = ImGui.getCursorScreenPos().y + circleRadius64;

            // Draw border around the circle BEFORE radio button renders
            drawRadioButtonBorder(ImGui.getWindowDrawList(), circleCenterX64, circleCenterY64,
                                circleRadius64, is64Selected);

            // Push increased spacing style
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemInnerSpacing, increasedSpacing, ImGui.getStyle().getItemInnerSpacingY());

            // Render the radio button
            if (ImGui.radioButton(radioLabel64, is64Selected)) {
                selectedSize = TextureCreatorState.CanvasSize.SIZE_64x48;
            }

            // Pop spacing style
            ImGui.popStyleVar();
        }
        ImGui.endGroup();
    }

    /**
     * Render preview for 16x16 canvas.
     * Shows a grid pattern demarcating 16x16 pixels.
     *
     * @param x preview X position
     * @param y preview Y position
     * @param size preview size (square)
     */
    private void renderPreviewFor16x16(float x, float y, float size) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw background
        drawList.addRectFilled(x, y, x + size, y + size, BACKGROUND_COLOR);

        // Draw grid lines showing 16x16 pixels
        int gridLines = 16;
        float cellSize = size / gridLines;

        for (int i = 0; i <= gridLines; i++) {
            float offset = i * cellSize;

            // Determine line color (every 4th line is darker for quadrants)
            int lineColor = (i % 4 == 0) ? BORDER_COLOR : GRID_COLOR;
            float lineThickness = (i % 4 == 0) ? 1.2f : 0.75f;

            // Vertical lines
            drawList.addLine(x + offset, y, x + offset, y + size, lineColor, lineThickness);

            // Horizontal lines
            drawList.addLine(x, y + offset, x + size, y + offset, lineColor, lineThickness);
        }

        // Add centered dimension label
        String label = "16×16";
        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (size - textSize.x) / 2.0f;
        float textY = y + (size - textSize.y) / 2.0f;

        // Draw text background for readability
        float padding = 6.0f;
        drawList.addRectFilled(
            textX - padding, textY - padding,
            textX + textSize.x + padding, textY + textSize.y + padding,
            ImColor.rgba(255, 255, 255, 220), 3.0f
        );

        drawList.addText(textX, textY, ImColor.rgba(100, 100, 100, 255), label);
    }

    /**
     * Render preview for 64x48 cube net canvas.
     * Shows cube net layout with face labels.
     *
     * @param x preview X position
     * @param y preview Y position
     * @param width preview width
     * @param height preview height
     */
    private void renderPreviewFor64x48(float x, float y, float width, float height) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw background
        drawList.addRectFilled(x, y, x + width, y + height, BACKGROUND_COLOR);

        // Calculate face positions (cube net is 4 wide x 3 tall in faces)
        float faceWidth = width / 4.0f;
        float faceHeight = height / 3.0f;

        // Draw non-editable regions (light gray)
        // Row 0: columns 0, 2, 3
        drawNonEditableRegion(drawList, x, y, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 2, y, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 3, y, faceWidth, faceHeight);
        // Row 2: columns 0, 2, 3
        drawNonEditableRegion(drawList, x, y + faceHeight * 2, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 2, y + faceHeight * 2, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 3, y + faceHeight * 2, faceWidth, faceHeight);

        // Draw cube net faces with labels
        // Row 0: TOP
        drawFaceRectangle(drawList, x + faceWidth, y, faceWidth, faceHeight, "TOP");

        // Row 1: LEFT, FRONT, RIGHT, BACK
        drawFaceRectangle(drawList, x, y + faceHeight, faceWidth, faceHeight, "LEFT");
        drawFaceRectangle(drawList, x + faceWidth, y + faceHeight, faceWidth, faceHeight, "FRONT");
        drawFaceRectangle(drawList, x + faceWidth * 2, y + faceHeight, faceWidth, faceHeight, "RIGHT");
        drawFaceRectangle(drawList, x + faceWidth * 3, y + faceHeight, faceWidth, faceHeight, "BACK");

        // Row 2: BOTTOM
        drawFaceRectangle(drawList, x + faceWidth, y + faceHeight * 2, faceWidth, faceHeight, "BOTTOM");
    }

    /**
     * Draw a non-editable region (grayed out).
     *
     * @param drawList ImGui draw list
     * @param x region X position
     * @param y region Y position
     * @param w region width
     * @param h region height
     */
    private void drawNonEditableRegion(ImDrawList drawList, float x, float y, float w, float h) {
        drawList.addRectFilled(x, y, x + w, y + h, NON_EDITABLE_COLOR);

        // Draw subtle diagonal lines
        drawList.addLine(x, y, x + w, y + h, GRID_COLOR, 0.75f);
        drawList.addLine(x + w, y, x, y + h, GRID_COLOR, 0.75f);
    }

    /**
     * Draw a face rectangle with label.
     *
     * @param drawList ImGui draw list
     * @param x face X position
     * @param y face Y position
     * @param w face width
     * @param h face height
     * @param label face label text
     */
    private void drawFaceRectangle(ImDrawList drawList, float x, float y, float w, float h, String label) {
        // Draw light fill
        drawList.addRectFilled(x, y, x + w, y + h, ImColor.rgba(100, 150, 255, 25));

        // Draw face boundary
        drawList.addRect(x, y, x + w, y + h, FACE_COLOR, 0, 0, 1.2f);

        // Draw centered label
        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (w - textSize.x) / 2.0f;
        float textY = y + (h - textSize.y) / 2.0f;

        drawList.addText(textX, textY, FACE_COLOR, label);
    }

    /**
     * Draw a border around the circular radio button itself (not the label).
     *
     * @param drawList ImGui draw list
     * @param centerX radio button circle center X position
     * @param centerY radio button circle center Y position
     * @param radius radio button circle radius
     * @param selected whether this radio button is selected
     */
    private void drawRadioButtonBorder(ImDrawList drawList, float centerX, float centerY,
                                      float radius, boolean selected) {
        if (selected) {
            // Selected state: blue outer ring (smaller, thinner)
            drawList.addCircleFilled(centerX, centerY, radius + 1.5f, RADIO_BG_SELECTED);
            drawList.addCircle(centerX, centerY, radius + 1.5f, RADIO_BORDER_SELECTED, 0, 1.5f);
        } else {
            // Unselected state: gray outer ring (smaller, thinner)
            drawList.addCircleFilled(centerX, centerY, radius + 1.5f, RADIO_BG_UNSELECTED);
            drawList.addCircle(centerX, centerY, radius + 1.5f, RADIO_BORDER_UNSELECTED, 0, 1.2f);
        }
    }

    /**
     * Close the dialog (cleanup if needed).
     */
    public void close() {
        isOpen = false;
        confirmedSelection = null;
    }
}
