package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;

/**
 * Renders the main 3D viewport window.
 * Follows Single Responsibility Principle - only renders the main viewport UI.
 */
public class ViewportMainView {

    private final ViewportUIState state;
    private final ViewportActions actions;
    private final ViewportController viewport;
    private final ThemeManager themeManager;
    private final PreferencesManager preferencesManager;
    private final ToolPaneRenderer toolPaneRenderer;
    private final ViewportToolbarRenderer toolbarRenderer;

    private final ImVec2 viewportSize = new ImVec2();
    private final ImVec2 viewportPos = new ImVec2();

    public ViewportMainView(ViewportUIState state, ViewportActions actions,
                           ViewportController viewport, ThemeManager themeManager,
                           PreferencesManager preferencesManager) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
        this.themeManager = themeManager;
        this.preferencesManager = preferencesManager;
        this.toolPaneRenderer = new ToolPaneRenderer(state, actions, viewport);
        this.toolbarRenderer = new ViewportToolbarRenderer(state, actions, viewport);
    }

    /**
     * Render the main viewport window.
     * Uses NoNavInputs to prevent ImGui's keyboard navigation (Tab, arrows) from
     * interfering with viewport shortcuts like Tab for edit mode cycling.
     */
    public void render() {
        if (ImGui.begin("3D Viewport", ImGuiWindowFlags.NoNavInputs)) {
            state.setViewportFocused(ImGui.isWindowFocused());
            renderToolbar();
            ImGui.separator();
            renderViewport3D();
        } else {
            state.setViewportFocused(false);
        }

        ImGui.end();
    }

    /**
     * Render the single-row flat viewport toolbar (gizmo modes, view combos,
     * display toggles, view actions, tool pane launchers).
     */
    private void renderToolbar() {
        toolbarRenderer.render();
    }

    /**
     * Render actual 3D viewport content.
     * The tool pane slides over the left edge of the viewport image as an overlay.
     */
    private void renderViewport3D() {
        // Get available content region
        ImGui.getContentRegionAvail(viewportSize);
        ImGui.getCursorScreenPos(viewportPos);

        // Ensure minimum size
        if (viewportSize.x < 400) viewportSize.x = 400;
        if (viewportSize.y < 300) viewportSize.y = 300;

        // Resize viewport if needed
        viewport.resize((int) viewportSize.x, (int) viewportSize.y);

        // Render 3D content
        viewport.render();

        // Display the rendered texture with mouse capture functionality
        int colorTexture = viewport.getColorTexture();
        if (colorTexture == -1) {
            ImGui.text("Viewport texture not available");
            return;
        }

        // Get image position before drawing for manual bounds checking
        ImVec2 imagePos = ImGui.getCursorScreenPos();

        // Display the rendered texture directly without any widgets
        ImGui.image(colorTexture, viewportSize.x, viewportSize.y, 0, 1, 1, 0);

        // Render box select rectangle overlay on top of the viewport image
        renderBoxSelectRect(imagePos);

        // Render sliding tool pane overlay on the left edge of the viewport image
        toolPaneRenderer.render(imagePos.x, imagePos.y, viewportSize.x, viewportSize.y);

        // Render edit mode overlay in top-left corner
        renderEditModeOverlay(imagePos);

        // Check if the viewport window itself is being hovered
        boolean viewportHovered = ImGui.isWindowHovered();

        // Handle input after image
        if (viewport.getInputHandler() != null) {
            viewport.getInputHandler().handleInput(imagePos, viewportSize.x, viewportSize.y, viewportHovered);
        }
    }

    /**
     * Render edit mode overlay in top-left corner of viewport.
     * Shows current mode name in orange with dark gray rounded rectangle background.
     * Also shows grid snapping indicator below when enabled.
     */
    private void renderEditModeOverlay(ImVec2 imagePos) {
        String modeName = EditModeManager.getInstance().getCurrentMode().getDisplayName();
        String displayText = "Edit Mode: " + modeName;

        // Calculate text size for proper rectangle sizing
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, displayText);

        // Padding around text
        float paddingX = 8.0f;
        float paddingY = 4.0f;

        // Position: top-left corner with 10px offset from viewport edge
        float rectX = imagePos.x + 10.0f;
        float rectY = imagePos.y + 10.0f;
        float rectWidth = textSize.x + (paddingX * 2);
        float rectHeight = textSize.y + (paddingY * 2);

        // Colors
        int backgroundColor = ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.85f); // Dark gray
        int borderColor = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);     // Black
        int textColor = ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.0f, 0.95f);      // Bright orange

        // Corner rounding
        float rounding = 4.0f;

        // Draw using window draw list (renders on top of image)
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw filled rounded rectangle (background)
        drawList.addRectFilled(rectX, rectY, rectX + rectWidth, rectY + rectHeight, backgroundColor, rounding);

        // Draw rounded rectangle border
        drawList.addRect(rectX, rectY, rectX + rectWidth, rectY + rectHeight, borderColor, rounding);

        // Draw centered text
        float textX = rectX + paddingX;
        float textY = rectY + paddingY;
        drawList.addText(textX, textY, textColor, displayText);

        // Render stacked indicators below the edit mode overlay
        float indicatorBottom = rectY + rectHeight;
        indicatorBottom = renderGridSnappingIndicator(drawList, imagePos, indicatorBottom);
        indicatorBottom = renderModalToolIndicator(drawList, imagePos, indicatorBottom,
            viewport.isKnifeToolActive(), "Knife Tool  |  Esc to cancel");
        indicatorBottom = renderModalToolIndicator(drawList, imagePos, indicatorBottom,
            viewport.isScaleToolActive(), "Scale  |  Click/Enter/S to confirm, Esc to cancel");
        indicatorBottom = renderModalToolIndicator(drawList, imagePos, indicatorBottom,
            viewport.isBoxSelectActive(), "Box Select  |  Drag to select, Esc to cancel");
        indicatorBottom = renderModalToolIndicator(drawList, imagePos, indicatorBottom,
            viewport.isInsetToolActive(), "Inset Faces  |  Move toward face; Click/Enter/I to confirm, Esc to cancel");
        renderModalToolIndicator(drawList, imagePos, indicatorBottom,
            viewport.isExtrudeToolActive(), "Extrude Faces  |  Drag along normal; Click/Enter/E to confirm, Esc to cancel");
    }

    /**
     * Render the box select rectangle overlay while a box drag is in progress.
     * The rect from the controller is viewport-relative; offset by the image position.
     */
    private void renderBoxSelectRect(ImVec2 imagePos) {
        float[] rect = viewport.getBoxSelectRect();
        if (rect == null) {
            return;
        }

        float minX = imagePos.x + rect[0];
        float minY = imagePos.y + rect[1];
        float maxX = imagePos.x + rect[2];
        float maxY = imagePos.y + rect[3];

        // Colors - translucent blue fill with a brighter border
        int fillColor = ImGui.colorConvertFloat4ToU32(0.3f, 0.5f, 1.0f, 0.15f);
        int borderColor = ImGui.colorConvertFloat4ToU32(0.5f, 0.7f, 1.0f, 0.8f);

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addRectFilled(minX, minY, maxX, maxY, fillColor);
        drawList.addRect(minX, minY, maxX, maxY, borderColor);
    }

    /**
     * Render grid snapping indicator below the edit mode overlay.
     * Only visible when grid snapping is enabled.
     *
     * @return Bottom Y of the rendered indicator, or {@code aboveBottom} if not rendered
     */
    private float renderGridSnappingIndicator(ImDrawList drawList, ImVec2 imagePos, float aboveBottom) {
        boolean snappingEnabled = state.getGridSnappingEnabled().get();
        if (!snappingEnabled) {
            return aboveBottom;
        }

        String snappingText = "Grid Snap: ON";

        // Calculate text size
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, snappingText);

        // Padding around text
        float paddingX = 8.0f;
        float paddingY = 4.0f;
        float verticalGap = 4.0f;

        // Position: below the previous indicator
        float rectX = imagePos.x + 10.0f;
        float rectY = aboveBottom + verticalGap;
        float rectWidth = textSize.x + (paddingX * 2);
        float rectHeight = textSize.y + (paddingY * 2);

        // Colors - use green tint to indicate active snapping
        int backgroundColor = ImGui.colorConvertFloat4ToU32(0.15f, 0.25f, 0.15f, 0.85f); // Dark green-gray
        int borderColor = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);        // Black
        int textColor = ImGui.colorConvertFloat4ToU32(0.4f, 1.0f, 0.4f, 0.95f);          // Bright green

        // Corner rounding
        float rounding = 4.0f;

        // Draw filled rounded rectangle (background)
        drawList.addRectFilled(rectX, rectY, rectX + rectWidth, rectY + rectHeight, backgroundColor, rounding);

        // Draw rounded rectangle border
        drawList.addRect(rectX, rectY, rectX + rectWidth, rectY + rectHeight, borderColor, rounding);

        // Draw text
        float textX = rectX + paddingX;
        float textY = rectY + paddingY;
        drawList.addText(textX, textY, textColor, snappingText);

        return rectY + rectHeight;
    }

    /**
     * Render a modal tool indicator below the previous indicator (knife, scale, box select).
     * Only visible when the tool is active.
     *
     * @return Bottom Y of the rendered indicator, or {@code aboveBottom} if not rendered
     */
    private float renderModalToolIndicator(ImDrawList drawList, ImVec2 imagePos, float aboveBottom,
                                           boolean active, String text) {
        if (!active) {
            return aboveBottom;
        }

        // Calculate text size
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, text);

        // Padding around text
        float paddingX = 8.0f;
        float paddingY = 4.0f;
        float verticalGap = 4.0f;

        // Position: below the previous indicator
        float rectX = imagePos.x + 10.0f;
        float rectY = aboveBottom + verticalGap;
        float rectWidth = textSize.x + (paddingX * 2);
        float rectHeight = textSize.y + (paddingY * 2);

        // Colors - orange tint matching the knife tool preview color
        int backgroundColor = ImGui.colorConvertFloat4ToU32(0.3f, 0.2f, 0.1f, 0.85f);   // Dark orange-brown
        int borderColor = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);        // Black
        int textColor = ImGui.colorConvertFloat4ToU32(1.0f, 0.6f, 0.0f, 0.95f);          // Orange

        // Corner rounding
        float rounding = 4.0f;

        // Draw filled rounded rectangle (background)
        drawList.addRectFilled(rectX, rectY, rectX + rectWidth, rectY + rectHeight, backgroundColor, rounding);

        // Draw rounded rectangle border
        drawList.addRect(rectX, rectY, rectX + rectWidth, rectY + rectHeight, borderColor, rounding);

        // Draw text
        float textX = rectX + paddingX;
        float textY = rectY + paddingY;
        drawList.addText(textX, textY, textColor, text);

        return rectY + rectHeight;
    }

}
