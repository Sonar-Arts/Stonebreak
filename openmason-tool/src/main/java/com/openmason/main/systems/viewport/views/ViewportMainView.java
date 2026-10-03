package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;

/**
 * Renders the main 3D viewport window.
 * Follows Single Responsibility Principle - only renders the main viewport UI.
 */
public class ViewportMainView {

    // HUD indicator pills (edit mode, grid snap, modal tools)
    private static final float INDICATOR_INSET = 10.0f;
    private static final float INDICATOR_GAP = 4.0f;
    private static final float INDICATOR_PAD_X = 8.0f;
    private static final float INDICATOR_PAD_Y = 4.0f;
    private static final float INDICATOR_ROUNDING = 4.0f;
    private final ImVec2 indicatorTextSize = new ImVec2();


    /**
     * ImGui window title. Also the key DockBuilder docks this window by, AND the key
     * imgui.ini stores its dock position under — so renaming it orphans the saved entry
     * and the window would reopen floating. Any change here must bump
     * {@code MainLayoutBuilder.LAYOUT_VERSION} to force a re-dock.
     */
    public static final String WINDOW_TITLE = "Model Editor";

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
        if (ImGui.begin(WINDOW_TITLE, ImGuiWindowFlags.NoNavInputs)) {
            state.setViewportWindowVisible(true);
            state.setViewportFocused(ImGui.isWindowFocused());
            renderToolbar();
            ImGui.separator();
            renderViewport3D();
        } else {
            state.setViewportWindowVisible(false);
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
     * Shows the current mode name in a theme-derived pill.
     * Also shows grid snapping indicator below when enabled.
     */
    private void renderEditModeOverlay(ImVec2 imagePos) {
        String modeName = EditModeManager.getInstance().getCurrentMode().getDisplayName();

        // Draw using window draw list (renders on top of image)
        ImDrawList drawList = ImGui.getWindowDrawList();
        float indicatorBottom = drawIndicator(drawList, imagePos.x + INDICATOR_INSET,
                imagePos.y + INDICATOR_INSET, "Edit Mode: " + modeName, ThemeColors.Tone.WARNING, 0.06f);

        // Render stacked indicators below the edit mode overlay
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

        // Colors - translucent theme accent fill with a stronger accent border
        int fillColor = ThemeColors.u32(ImGuiCol.HeaderActive, 0.15f);
        int borderColor = ThemeColors.u32(ImGuiCol.HeaderActive, 0.8f);

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
        if (!state.getGridSnappingEnabled().get()) {
            return aboveBottom;
        }
        return drawIndicator(drawList, imagePos.x + INDICATOR_INSET, aboveBottom + INDICATOR_GAP,
                "Grid Snap: ON", ThemeColors.Tone.SUCCESS, 0.12f);
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
        return drawIndicator(drawList, imagePos.x + INDICATOR_INSET, aboveBottom + INDICATOR_GAP,
                text, ThemeColors.Tone.WARNING, 0.16f);
    }

    /**
     * Draw one HUD pill: the theme's window background tinted toward
     * {@code tone}, a theme border, and {@code tone}-colored text readable on
     * both light and dark themes.
     *
     * @return bottom Y of the pill
     */
    private float drawIndicator(ImDrawList drawList, float x, float y, String text,
                                ThemeColors.Tone tone, float tint) {
        ImGui.calcTextSize(indicatorTextSize, text);
        float w = indicatorTextSize.x + INDICATOR_PAD_X * 2;
        float h = indicatorTextSize.y + INDICATOR_PAD_Y * 2;

        drawList.addRectFilled(x, y, x + w, y + h, ThemeColors.surfaceU32(tone, tint, 0.88f), INDICATOR_ROUNDING);
        drawList.addRect(x, y, x + w, y + h, ThemeColors.u32(ImGuiCol.Border, 0.85f), INDICATOR_ROUNDING);
        drawList.addText(x + INDICATOR_PAD_X, y + INDICATOR_PAD_Y, ThemeColors.u32(tone, 0.95f), text);
        return y + h;
    }

}
