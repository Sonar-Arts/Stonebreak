package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import com.openmason.ui.components.textureCreator.tools.RectangleSelectionTool;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool options toolbar - displays tool-specific options below the menu bar.
 *
 * This toolbar is:
 * - Fixed position (non-draggable)
 * - Horizontally oriented
 * - Only visible when the current tool has options
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only renders tool-specific options UI
 * - Open/Closed: Easy to add new tool options without modifying existing code
 * - Dependency Inversion: Depends on DrawingTool abstraction
 *
 * Follows YAGNI, KISS, DRY:
 * - YAGNI: Only implements options for tools that need them
 * - KISS: Simple type-checking and direct rendering
 * - DRY: Shared toolbar rendering logic
 *
 * @author Open Mason Team
 */
public class ToolOptionsBar {

    private static final Logger logger = LoggerFactory.getLogger(ToolOptionsBar.class);

    // Toolbar dimensions - fixed height for consistency
    private static final float TOOLBAR_HEIGHT = 32.0f; // Fixed height that fits all content
    private static final float OPTION_SPACING = 15.0f; // Spacing between options
    private static final float LEFT_PADDING = 10.0f;   // Left padding from window edge
    private static final float VERTICAL_PADDING = 6.0f; // Top/bottom padding

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
                // Center content vertically - use AlignTextToFramePadding for all elements
                float framePaddingY = ImGui.getStyle().getFramePaddingY();
                float textHeight = ImGui.getTextLineHeight();
                float frameHeight = textHeight + framePaddingY * 2;
                float verticalOffset = (TOOLBAR_HEIGHT - frameHeight) / 2.0f;

                // Set cursor position for centered content
                ImGui.setCursorPosX(LEFT_PADDING);
                ImGui.setCursorPosY(verticalOffset);

                // Align text to frame padding for consistent vertical alignment
                ImGui.alignTextToFramePadding();

                // Render tool-specific options based on tool type
                if (currentTool instanceof RectangleSelectionTool) {
                    renderRectangleSelectionOptions((RectangleSelectionTool) currentTool);
                }
            }
            // If no options, toolbar shows empty space (no dummy needed, window size handles it)

            // Draw separator line at bottom
            renderBottomSeparator();
        }
        ImGui.end();
    }

    /**
     * Get the height of the toolbar for layout calculations.
     * Always returns the fixed toolbar height.
     *
     * @param currentTool The currently active tool (unused, kept for API compatibility)
     * @return Fixed toolbar height in pixels
     */
    public float getHeight(DrawingTool currentTool) {
        return TOOLBAR_HEIGHT;
    }

    /**
     * Check if the given tool has options to display.
     *
     * @param tool The tool to check
     * @return true if the tool has options, false otherwise
     */
    private boolean hasOptions(DrawingTool tool) {
        return tool instanceof RectangleSelectionTool;
    }

    /**
     * Render options for the Rectangle Selection tool.
     *
     * Options:
     * - Fixed Aspect Ratio: Constrains selection to 1:1 square ratio
     *
     * @param tool The RectangleSelectionTool instance
     */
    private void renderRectangleSelectionOptions(RectangleSelectionTool tool) {
        // Label for the tool
        ImGui.text("Rectangle Selection:");
        ImGui.sameLine(0, OPTION_SPACING);

        // Fixed Aspect Ratio checkbox
        ImBoolean fixedAspectRatio = new ImBoolean(tool.isFixedAspectRatio());
        if (ImGui.checkbox("Fixed Aspect Ratio", fixedAspectRatio)) {
            tool.setFixedAspectRatio(fixedAspectRatio.get());
            logger.debug("Rectangle selection fixed aspect ratio: {}", fixedAspectRatio.get());
        }

        // Tooltip
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("When enabled, constrains rectangle to 1:1 square ratio");
        }
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
}
