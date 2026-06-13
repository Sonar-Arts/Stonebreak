package com.openmason.main.systems.menus.textureCreator.panels.status;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.awt.Rectangle;

/**
 * Aseprite-style status bar rendered as a fixed-height strip at the bottom of
 * the texture editor window (never docked, never movable). Left side shows
 * the latest {@link StatusService} message; the right side shows live editing
 * context: current tool, hovered pixel, canvas size, zoom, and selection size.
 */
public final class StatusBarPanel {

    public static final float HEIGHT = 26.0f;

    private static final String SEGMENT_SEPARATOR = "  |  ";

    private final StatusService statusService;
    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final CanvasHoverInfo hoverInfo;

    public StatusBarPanel(StatusService statusService,
                          TextureCreatorState state,
                          TextureCreatorController controller,
                          CanvasHoverInfo hoverInfo) {
        this.statusService = statusService;
        this.state = state;
        this.controller = controller;
        this.hoverInfo = hoverInfo;
    }

    public void render() {
        // Pin to the window bottom: the strip occupies exactly the reserved
        // band and can never extend the host window's content height (which
        // would make the window scrollable and let the wheel shift the UI)
        ImGui.setCursorPos(0, ImGui.getWindowHeight() - HEIGHT);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0.0f, 0.0f);

        int flags = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;
        if (ImGui.beginChild("##TextureEditorStatusBar", 0, HEIGHT, false, flags)) {
            ImGui.separator();

            ImGui.textDisabled(statusService.getStatusMessage());

            String right = buildRightSegments();
            float textWidth = ImGui.calcTextSize(right).x;
            float x = ImGui.getWindowWidth() - textWidth - 12.0f;
            if (x > ImGui.getCursorPosX()) {
                ImGui.sameLine(x);
            } else {
                ImGui.sameLine();
            }
            ImGui.textDisabled(right);
        }
        ImGui.endChild();

        ImGui.popStyleVar(2);
    }

    private String buildRightSegments() {
        // Numeric fields use fixed-width formats: this block is right-aligned
        // by its measured width, and the UI font is monospaced — padding keeps
        // the cluster pixel-stable while zooming or moving over the canvas.
        StringBuilder sb = new StringBuilder(96);

        DrawingTool tool = state.getCurrentTool();
        if (tool != null) {
            sb.append(tool.getName());
        }

        appendSeparator(sb);
        if (hoverInfo != null && hoverInfo.isHovering()) {
            sb.append(String.format("%4d,%4d", hoverInfo.getPixelX(), hoverInfo.getPixelY()));
        } else {
            sb.append("   -,   -"); // same width as the populated segment
        }

        appendSeparator(sb);
        sb.append(state.getCurrentCanvasSize().getDisplayName());

        appendSeparator(sb);
        sb.append(String.format("%4d%%", Math.round(controller.getCanvasState().getZoomLevel() * 100f)));

        SelectionRegion selection = state.getCurrentSelection();
        if (selection != null && !selection.isEmpty()) {
            Rectangle bounds = selection.getBounds();
            appendSeparator(sb);
            sb.append("sel ").append(bounds.width).append('x').append(bounds.height);
        }

        return sb.toString();
    }

    private static void appendSeparator(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(SEGMENT_SEPARATOR);
        }
    }
}
