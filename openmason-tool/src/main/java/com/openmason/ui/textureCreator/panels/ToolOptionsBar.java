package com.openmason.ui.textureCreator.panels;

import com.openmason.ui.textureCreator.TextureCreatorPreferences;
import com.openmason.ui.textureCreator.tools.DrawingTool;
import com.openmason.ui.textureCreator.tools.ShapeTool;
import com.openmason.ui.textureCreator.tools.move.MoveToolController;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

/**
 * Tool options toolbar - displays tool-specific options below the menu bar.
 */
public class ToolOptionsBar {

    // Toolbar dimensions - fixed height for consistency
    private static final float TOOLBAR_HEIGHT = 32.0f; // Fixed height that fits all content
    private static final float OPTION_SPACING = 15.0f; // Spacing between options
    private static final float LEFT_PADDING = 10.0f;   // Left padding from window edge

    private final TextureCreatorPreferences preferences;

    public ToolOptionsBar(TextureCreatorPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * Render the tool options toolbar.
     * Always visible with consistent fixed height.
     *
     * @param currentTool The currently active tool (can be null)
     */
    public void render(DrawingTool currentTool) {

        // Calculate position below menu bar
        ImGuiViewport viewport = ImGui.getMainViewport();
        float menuBarHeight = viewport.getWorkPosY() - viewport.getPosY();
        float toolbarY = viewport.getPosY() + menuBarHeight;

        // Set up fixed-height toolbar window
        ImGui.setNextWindowPos(viewport.getPosX(), toolbarY);
        ImGui.setNextWindowSize(viewport.getSizeX(), TOOLBAR_HEIGHT);

        // Window flags for fixed, non-interactive window frame
        int flags = ImGuiWindowFlags.NoTitleBar |
                    ImGuiWindowFlags.NoResize |
                    ImGuiWindowFlags.NoMove |
                    ImGuiWindowFlags.NoScrollbar |
                    ImGuiWindowFlags.NoScrollWithMouse |
                    ImGuiWindowFlags.NoCollapse |
                    ImGuiWindowFlags.NoSavedSettings |
                    ImGuiWindowFlags.NoDocking;

        boolean hasOptions = currentTool != null && hasOptions(currentTool);

        if (ImGui.begin("##ToolOptionsBar", flags)) {
            if (hasOptions) {
                float framePaddingY = ImGui.getStyle().getFramePaddingY();
                float textHeight = ImGui.getTextLineHeight();
                float frameHeight = textHeight + framePaddingY * 2;
                float verticalOffset = (TOOLBAR_HEIGHT - frameHeight) / 2.0f;

                ImGui.setCursorPosX(LEFT_PADDING);
                ImGui.setCursorPosY(verticalOffset);

                ImGui.alignTextToFramePadding();

                if (currentTool instanceof MoveToolController) {
                    renderMoveToolOptions((MoveToolController) currentTool);
                } else if (currentTool instanceof ShapeTool) {
                    renderShapeToolOptions((ShapeTool) currentTool);
                } else if (currentTool.supportsBrushSize()) {
                    renderBrushSizeOption(currentTool);
                }
            }
            // If no options, toolbar shows empty space (no dummy needed, window size handles it)

            // Draw separator line at bottom
            renderBottomSeparator();
        }
        ImGui.end();
    }

    /**
     * Render the tool options toolbar in windowed mode (inside a parent window).
     * Uses relative positioning instead of absolute viewport positioning.
     *
     * @param currentTool The currently active tool (can be null)
     */
    public void renderWindowed(DrawingTool currentTool) {
        // Create a fixed-height child window for the toolbar
        int flags = ImGuiWindowFlags.NoScrollbar |
                    ImGuiWindowFlags.NoScrollWithMouse |
                    ImGuiWindowFlags.NoTitleBar;

        boolean hasOptions = currentTool != null && hasOptions(currentTool);

        if (ImGui.beginChild("##ToolOptionsBar", 0, TOOLBAR_HEIGHT, false, flags)) {
            if (hasOptions) {
                float framePaddingY = ImGui.getStyle().getFramePaddingY();
                float textHeight = ImGui.getTextLineHeight();
                float frameHeight = textHeight + framePaddingY * 2;
                float verticalOffset = (TOOLBAR_HEIGHT - frameHeight) / 2.0f;

                ImGui.setCursorPosX(LEFT_PADDING);
                ImGui.setCursorPosY(verticalOffset);

                ImGui.alignTextToFramePadding();

                if (currentTool instanceof MoveToolController) {
                    renderMoveToolOptions((MoveToolController) currentTool);
                } else if (currentTool instanceof ShapeTool) {
                    renderShapeToolOptions((ShapeTool) currentTool);
                } else if (currentTool.supportsBrushSize()) {
                    renderBrushSizeOption(currentTool);
                }
            }

            // Draw separator line at bottom
            renderBottomSeparatorWindowed();
        }
        ImGui.endChild();
    }

    /**
     * Get the height of the toolbar for layout calculations.
     * Always returns the fixed toolbar height.
     *
     * @return Fixed toolbar height in pixels
     */
    public float getHeight() {
        return TOOLBAR_HEIGHT;
    }

    /**
     * Check if the given tool has options to display.
     *
     * @param tool The tool to check
     * @return true if the tool has options, false otherwise
     */
    private boolean hasOptions(DrawingTool tool) {
        if (tool instanceof MoveToolController) {
            return true;
        }
        if (tool instanceof ShapeTool) {
            return true;
        }
        return tool.supportsBrushSize();
    }

    private void renderMoveToolOptions(MoveToolController moveTool) {
        var transform = moveTool.getLiveTransform();
        ImGui.text(String.format("Move Δ(%.1f, %.1f) px", transform.translateX(), transform.translateY()));
        ImGui.sameLine(0.0f, OPTION_SPACING);
        ImGui.text(String.format("Scale %.3f × %.3f", transform.scaleX(), transform.scaleY()));
        ImGui.sameLine(0.0f, OPTION_SPACING);
        ImGui.text(String.format("Rotate %.1f°", transform.rotationDegrees()));
        if (moveTool.hasPreviewLayer()) {
            ImGui.sameLine(0.0f, OPTION_SPACING);
            ImGui.text("Preview active");
        }
    }

    private void renderShapeToolOptions(ShapeTool shapeTool) {
        ImGui.text("Shape:");
        ImGui.sameLine(0.0f, OPTION_SPACING);
        ImGui.text(shapeTool.getCurrentShape().getDisplayName());
        ImGui.sameLine(0.0f, OPTION_SPACING);

        ImBoolean fillMode = new ImBoolean(preferences.isShapeToolFillMode());
        if (ImGui.checkbox("Fill", fillMode)) {
            preferences.setShapeToolFillMode(fillMode.get());
        }
    }

    /**
     * Render brush size option for tools that support it.
     * Follows DRY principle - shared rendering for all brush-based tools.
     *
     * @param tool The tool with brush size support
     */
    private void renderBrushSizeOption(DrawingTool tool) {
        ImGui.text("Brush Size:");
        ImGui.sameLine(0.0f, OPTION_SPACING);

        // Create ImInt wrapper for the slider
        ImInt brushSize = new ImInt(tool.getBrushSize());

        // Set width for slider (reasonable size for 1-20 range)
        ImGui.pushItemWidth(150.0f);

        // Render slider with 1-20 range
        if (ImGui.sliderInt("##BrushSize", brushSize.getData(), 1, 20, "%d px")) {
            // Update tool's brush size when slider changes
            tool.setBrushSize(brushSize.get());
        }

        ImGui.popItemWidth();
    }

    /**
     * Render a subtle separator line at the bottom of the toolbar.
     * Provides visual separation between toolbar and content area.
     */
    private void renderBottomSeparator() {
        float windowWidth = ImGui.getWindowWidth();
        float windowHeight = ImGui.getWindowHeight();
        float separatorY = ImGui.getWindowPosY() + windowHeight - 1.0f; // 1px from bottom

        int separatorColor = ImGui.getColorU32(ImGuiCol.Separator);

        ImGui.getWindowDrawList().addLine(
            ImGui.getWindowPosX(),
            separatorY,
            ImGui.getWindowPosX() + windowWidth,
            separatorY,
            separatorColor,
            1.0f
        );
    }

    /**
     * Render bottom separator for windowed mode.
     * Uses child window positioning instead of main window.
     */
    private void renderBottomSeparatorWindowed() {
        ImGui.separator();
    }
}
