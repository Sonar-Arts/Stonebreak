package com.openmason.main.systems.menus.textureCreator.dialogs;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for importing PNG files with canvas size selection.
 */
public class ImportPNGDialog {

    private static final Logger logger = LoggerFactory.getLogger(ImportPNGDialog.class);

    // Dialog state
    private boolean isOpen = false;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private TextureCreatorState.CanvasSize selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
    private TextureCreatorState.CanvasSize confirmedSelection = null;
    private boolean needsPositioning = false;

    // Dialog dimensions
    private static final float DIALOG_WIDTH = 520.0f;
    private static final float DIALOG_HEIGHT = 450.0f;
    private static final float MIN_WINDOW_WIDTH = 520.0f;
    private static final float MIN_WINDOW_HEIGHT = 450.0f;
    private static final float MAX_WINDOW_WIDTH = 1200.0f;
    private static final float MAX_WINDOW_HEIGHT = 800.0f;

    // Preview dimensions
    private static final float PREVIEW_SIZE = 180.0f; // Square preview area
    private static final float PREVIEW_SPACING = 30.0f;

    // Visual styling constants (reused from NewTextureDialog)
    private static final int BACKGROUND_COLOR = ImColor.rgba(245, 245, 245, 255);
    private static final int GRID_COLOR = ImColor.rgba(200, 200, 200, 255);
    private static final int BORDER_COLOR = ImColor.rgba(120, 120, 120, 255);
    private static final int FACE_COLOR = ImColor.rgba(100, 150, 255, 255);
    private static final int NON_EDITABLE_COLOR = ImColor.rgba(200, 200, 200, 200);
    private static final int PREVIEW_BORDER_COLOR = ImColor.rgba(200, 200, 200, 255);
    private static final int RADIO_BORDER_SELECTED = ImColor.rgba(100, 150, 255, 255);
    private static final int RADIO_BG_SELECTED = ImColor.rgba(100, 150, 255, 40);
    private static final int RADIO_BORDER_UNSELECTED = ImColor.rgba(160, 160, 160, 255);
    private static final int RADIO_BG_UNSELECTED = ImColor.rgba(240, 240, 240, 255);
    private static final int WARNING_COLOR = ImColor.rgba(255, 150, 0, 255);
    private static final int INFO_COLOR = ImColor.rgba(100, 180, 100, 255);

    /**
     * Create import PNG dialog.
     */
    public ImportPNGDialog() {
        logger.debug("Import PNG dialog created");
    }

    /**
     * Show the dialog with detected PNG dimensions.
     *
     * @param sourceWidth detected PNG width
     * @param sourceHeight detected PNG height
     */
    public void show(int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        isOpen = true;
        confirmedSelection = null;
        needsPositioning = true;

        // Pre-select matching size if applicable
        if (sourceWidth == 16 && sourceHeight == 16) {
            selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
        } else if (sourceWidth == 64 && sourceHeight == 48) {
            selectedSize = TextureCreatorState.CanvasSize.SIZE_64x48;
        } else {
            // Default to 16x16 for non-matching sizes
            selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
        }

        logger.debug("Import PNG dialog opened: source {}x{}", sourceWidth, sourceHeight);
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
     * Returns the selected size if user clicked "Import", null otherwise.
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

        // Set window size constraints
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

        if (ImGui.beginPopupModal("Import PNG Texture")) {

            // Header
            ImGui.spacing();
            String headerText = "Import PNG Texture";
            ImVec2 headerSize = ImGui.calcTextSize(headerText);
            ImGui.setCursorPosX((DIALOG_WIDTH - headerSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(80, 80, 80, 255));
            ImGui.text(headerText);
            ImGui.popStyleColor();
            ImGui.spacing();

            // Show detected dimensions
            String detectedText = String.format("Detected: %dx%d", sourceWidth, sourceHeight);
            ImVec2 detectedSize = ImGui.calcTextSize(detectedText);
            ImGui.setCursorPosX((DIALOG_WIDTH - detectedSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(100, 100, 100, 255));
            ImGui.text(detectedText);
            ImGui.popStyleColor();

            ImGui.spacing();
            String subText = "Choose target canvas size";
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

            if (ImGui.button("Import", buttonWidth, buttonHeight)) {
                confirmedSelection = selectedSize;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("Importing PNG to {} canvas", selectedSize.getDisplayName());
            }

            ImGui.sameLine();

            if (ImGui.button("Cancel", buttonWidth, buttonHeight)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.debug("Import PNG dialog cancelled");
            }

            // Handle ESC key to close
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Escape))) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open the modal popup if we just set isOpen to true
        if (isOpen && !ImGui.isPopupOpen("Import PNG Texture")) {
            ImGui.openPopup("Import PNG Texture");
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

        // Check if resize will occur
        boolean resize16 = !(sourceWidth == 16 && sourceHeight == 16);
        boolean resize64 = !(sourceWidth == 64 && sourceHeight == 48);

        // Left column - 16x16
        ImGui.beginGroup();
        {
            // Preview with border
            ImVec2 cursorPos = ImGui.getCursorScreenPos();
            ImDrawList drawList = ImGui.getWindowDrawList();

            drawList.addRect(cursorPos.x - 1, cursorPos.y - 1,
                           cursorPos.x + PREVIEW_SIZE + 1, cursorPos.y + PREVIEW_SIZE + 1,
                           PREVIEW_BORDER_COLOR, 2.0f, 0, 1.0f);

            renderPreviewFor16x16(cursorPos.x, cursorPos.y, PREVIEW_SIZE);
            ImGui.dummy(PREVIEW_SIZE, PREVIEW_SIZE);

            // Label
            ImGui.spacing();
            ImGui.spacing();
            String label16 = "16x16 Block/Item Texture";
            ImVec2 textSize16 = ImGui.calcTextSize(label16);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - textSize16.x) / 2.0f);
            ImGui.text(label16);

            // Resize warning or confirmation
            ImGui.spacing();
            String status16 = resize16 ? "Will resize from source" : "Exact match!";
            int statusColor16 = resize16 ? WARNING_COLOR : INFO_COLOR;
            ImVec2 statusSize16 = ImGui.calcTextSize(status16);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - statusSize16.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, statusColor16);
            ImGui.text(status16);
            ImGui.popStyleColor();

            // Radio button
            ImGui.spacing();
            ImGui.spacing();
            String radioLabel16 = "Select 16x16";
            ImVec2 radioSize16 = ImGui.calcTextSize(radioLabel16);
            float increasedSpacing = 12.0f;
            float radioWidth16 = radioSize16.x + ImGui.getFrameHeight() + increasedSpacing;
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - radioWidth16) / 2.0f);

            float circleRadius16 = ImGui.getFrameHeight() / 2.0f;
            float circleCenterX16 = ImGui.getCursorScreenPos().x + circleRadius16;
            float circleCenterY16 = ImGui.getCursorScreenPos().y + circleRadius16;

            drawRadioButtonBorder(ImGui.getWindowDrawList(), circleCenterX16, circleCenterY16,
                                circleRadius16, is16Selected);

            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemInnerSpacing, increasedSpacing, ImGui.getStyle().getItemInnerSpacingY());

            if (ImGui.radioButton(radioLabel16, is16Selected)) {
                selectedSize = TextureCreatorState.CanvasSize.SIZE_16x16;
            }

            ImGui.popStyleVar();
        }
        ImGui.endGroup();

        ImGui.sameLine(0, PREVIEW_SPACING);

        // Right column - 64x48
        ImGui.beginGroup();
        {
            // Preview with border
            ImVec2 cursorPos = ImGui.getCursorScreenPos();
            ImDrawList drawList = ImGui.getWindowDrawList();

            float aspectRatio = 64.0f / 48.0f;
            float previewWidth = PREVIEW_SIZE;
            float previewHeight = PREVIEW_SIZE / aspectRatio;
            float yOffset = (PREVIEW_SIZE - previewHeight) / 2.0f;

            drawList.addRect(cursorPos.x - 1, cursorPos.y + yOffset - 1,
                           cursorPos.x + previewWidth + 1, cursorPos.y + yOffset + previewHeight + 1,
                           PREVIEW_BORDER_COLOR, 2.0f, 0, 1.0f);

            renderPreviewFor64x48(cursorPos.x, cursorPos.y + yOffset, previewWidth, previewHeight);
            ImGui.dummy(PREVIEW_SIZE, PREVIEW_SIZE);

            // Label
            ImGui.spacing();
            ImGui.spacing();
            String label64 = "64x48 Block Texture";
            ImVec2 textSize64 = ImGui.calcTextSize(label64);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - textSize64.x) / 2.0f);
            ImGui.text(label64);

            // Resize warning or confirmation
            ImGui.spacing();
            String status64 = resize64 ? "Will resize from source" : "Exact match!";
            int statusColor64 = resize64 ? WARNING_COLOR : INFO_COLOR;
            ImVec2 statusSize64 = ImGui.calcTextSize(status64);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - statusSize64.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, statusColor64);
            ImGui.text(status64);
            ImGui.popStyleColor();

            // Radio button
            ImGui.spacing();
            ImGui.spacing();
            String radioLabel64 = "Select 64x48";
            ImVec2 radioSize64 = ImGui.calcTextSize(radioLabel64);
            float increasedSpacing = 12.0f;
            float radioWidth64 = radioSize64.x + ImGui.getFrameHeight() + increasedSpacing;
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - radioWidth64) / 2.0f);

            float circleRadius64 = ImGui.getFrameHeight() / 2.0f;
            float circleCenterX64 = ImGui.getCursorScreenPos().x + circleRadius64;
            float circleCenterY64 = ImGui.getCursorScreenPos().y + circleRadius64;

            drawRadioButtonBorder(ImGui.getWindowDrawList(), circleCenterX64, circleCenterY64,
                                circleRadius64, is64Selected);

            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemInnerSpacing, increasedSpacing, ImGui.getStyle().getItemInnerSpacingY());

            if (ImGui.radioButton(radioLabel64, is64Selected)) {
                selectedSize = TextureCreatorState.CanvasSize.SIZE_64x48;
            }

            ImGui.popStyleVar();
        }
        ImGui.endGroup();
    }

    /**
     * Render preview for 16x16 canvas (reused from NewTextureDialog).
     */
    private void renderPreviewFor16x16(float x, float y, float size) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw background
        drawList.addRectFilled(x, y, x + size, y + size, BACKGROUND_COLOR);

        // Draw grid lines
        int gridLines = 16;
        float cellSize = size / gridLines;

        for (int i = 0; i <= gridLines; i++) {
            float offset = i * cellSize;
            int lineColor = (i % 4 == 0) ? BORDER_COLOR : GRID_COLOR;
            float lineThickness = (i % 4 == 0) ? 1.2f : 0.75f;

            drawList.addLine(x + offset, y, x + offset, y + size, lineColor, lineThickness);
            drawList.addLine(x, y + offset, x + size, y + offset, lineColor, lineThickness);
        }

        // Add label
        String label = "16×16";
        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (size - textSize.x) / 2.0f;
        float textY = y + (size - textSize.y) / 2.0f;

        float padding = 6.0f;
        drawList.addRectFilled(
            textX - padding, textY - padding,
            textX + textSize.x + padding, textY + textSize.y + padding,
            ImColor.rgba(255, 255, 255, 220), 3.0f
        );

        drawList.addText(textX, textY, ImColor.rgba(100, 100, 100, 255), label);
    }

    /**
     * Render preview for 64x48 cube net canvas (reused from NewTextureDialog).
     */
    private void renderPreviewFor64x48(float x, float y, float width, float height) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw background
        drawList.addRectFilled(x, y, x + width, y + height, BACKGROUND_COLOR);

        // Calculate face positions
        float faceWidth = width / 4.0f;
        float faceHeight = height / 3.0f;

        // Draw non-editable regions
        drawNonEditableRegion(drawList, x, y, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 2, y, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 3, y, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x, y + faceHeight * 2, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 2, y + faceHeight * 2, faceWidth, faceHeight);
        drawNonEditableRegion(drawList, x + faceWidth * 3, y + faceHeight * 2, faceWidth, faceHeight);

        // Draw cube net faces
        drawFaceRectangle(drawList, x + faceWidth, y, faceWidth, faceHeight, "TOP");
        drawFaceRectangle(drawList, x, y + faceHeight, faceWidth, faceHeight, "LEFT");
        drawFaceRectangle(drawList, x + faceWidth, y + faceHeight, faceWidth, faceHeight, "FRONT");
        drawFaceRectangle(drawList, x + faceWidth * 2, y + faceHeight, faceWidth, faceHeight, "RIGHT");
        drawFaceRectangle(drawList, x + faceWidth * 3, y + faceHeight, faceWidth, faceHeight, "BACK");
        drawFaceRectangle(drawList, x + faceWidth, y + faceHeight * 2, faceWidth, faceHeight, "BOTTOM");
    }

    /**
     * Draw non-editable region (reused from NewTextureDialog).
     */
    private void drawNonEditableRegion(ImDrawList drawList, float x, float y, float w, float h) {
        drawList.addRectFilled(x, y, x + w, y + h, NON_EDITABLE_COLOR);
        drawList.addLine(x, y, x + w, y + h, GRID_COLOR, 0.75f);
        drawList.addLine(x + w, y, x, y + h, GRID_COLOR, 0.75f);
    }

    /**
     * Draw face rectangle with label (reused from NewTextureDialog).
     */
    private void drawFaceRectangle(ImDrawList drawList, float x, float y, float w, float h, String label) {
        drawList.addRectFilled(x, y, x + w, y + h, ImColor.rgba(100, 150, 255, 25));
        drawList.addRect(x, y, x + w, y + h, FACE_COLOR, 0, 0, 1.2f);

        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (w - textSize.x) / 2.0f;
        float textY = y + (h - textSize.y) / 2.0f;

        drawList.addText(textX, textY, FACE_COLOR, label);
    }

    /**
     * Draw radio button border (reused from NewTextureDialog).
     */
    private void drawRadioButtonBorder(ImDrawList drawList, float centerX, float centerY,
                                      float radius, boolean selected) {
        if (selected) {
            drawList.addCircleFilled(centerX, centerY, radius + 1.5f, RADIO_BG_SELECTED);
            drawList.addCircle(centerX, centerY, radius + 1.5f, RADIO_BORDER_SELECTED, 0, 1.5f);
        } else {
            drawList.addCircleFilled(centerX, centerY, radius + 1.5f, RADIO_BG_UNSELECTED);
            drawList.addCircle(centerX, centerY, radius + 1.5f, RADIO_BORDER_UNSELECTED, 0, 1.2f);
        }
    }

    /**
     * Close the dialog.
     */
    public void close() {
        isOpen = false;
        confirmedSelection = null;
    }
}
