package com.openmason.main.systems.menus.windows;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;

/**
 * Reusable custom window title bar with modern window control buttons.
 * <p>
 * Renders a draggable title bar with configurable buttons (minimize, maximize/restore, close).
 * All icons are drawn via ImGui draw lists for crisp, theme-aware rendering.
 * </p>
 * <p>
 * Usage:
 * <pre>
 *   WindowTitleBar titleBar = new WindowTitleBar("My Window", true, true);
 *   // inside ImGui.begin():
 *   WindowTitleBar.Result result = titleBar.render();
 *   if (result.minimizeClicked()) { ... }
 *   if (result.maximizeClicked()) { ... }
 *   if (result.closeClicked()) { ... }
 * </pre>
 * </p>
 */
public class WindowTitleBar {

    private static final float TITLE_BAR_HEIGHT = 30.0f;
    private static final float BUTTON_WIDTH = 46.0f;
    private static final float TITLE_PADDING = 10.0f;
    private static final float ICON_HALF_SIZE = 5.0f;
    private static final float ICON_STROKE = 1.2f;

    private final String windowTitle;
    private final boolean showMinimize;
    private final boolean showMaximize;

    // Drag state
    private boolean isDragging = false;
    private float cachedWindowWidth = 0.0f;

    // Maximize state (tracked externally but needed for restore icon)
    private boolean maximized = false;

    /**
     * Result of rendering the title bar. Callers inspect which button was clicked.
     */
    public record Result(boolean minimizeClicked, boolean maximizeClicked, boolean closeClicked) {}

    /**
     * Create a title bar with all three buttons.
     *
     * @param windowTitle  the ImGui window title (must match the title in ImGui.begin)
     * @param showMinimize whether to show the minimize button
     * @param showMaximize whether to show the maximize/restore button
     */
    public WindowTitleBar(String windowTitle, boolean showMinimize, boolean showMaximize) {
        this.windowTitle = windowTitle;
        this.showMinimize = showMinimize;
        this.showMaximize = showMaximize;
    }

    /**
     * Set maximized state so the title bar can draw the correct maximize/restore icon.
     */
    public void setMaximized(boolean maximized) {
        this.maximized = maximized;
    }

    /**
     * Render the title bar. Must be called inside an active ImGui window (after ImGui.begin).
     *
     * @return which buttons were clicked this frame
     */
    public Result render() {
        // Stable width during drag to prevent flicker
        float windowWidth;
        if (!isDragging) {
            windowWidth = ImGui.getWindowWidth();
            cachedWindowWidth = windowWidth;
        } else {
            windowWidth = cachedWindowWidth;
        }

        float winX = ImGui.getWindowPosX();
        float winY = ImGui.getWindowPosY();

        // --- Background ---
        ImVec4 titleBg = ImGui.getStyle().getColor(ImGuiCol.TitleBgActive);
        ImGui.getWindowDrawList().addRectFilled(
                winX, winY,
                winX + windowWidth, winY + TITLE_BAR_HEIGHT,
                ImGui.getColorU32(titleBg.x, titleBg.y, titleBg.z, titleBg.w)
        );

        // --- Draggable region ---
        int buttonCount = 1 + (showMinimize ? 1 : 0) + (showMaximize ? 1 : 0); // close always shown
        float controlsWidth = BUTTON_WIDTH * buttonCount;

        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##TitleBarDrag", windowWidth - controlsWidth, TITLE_BAR_HEIGHT);

        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll);
        }

        boolean currentlyDragging = ImGui.isItemActive() && ImGui.isMouseDragging(0);
        if (currentlyDragging) {
            if (!isDragging) {
                isDragging = true;
                cachedWindowWidth = ImGui.getWindowWidth();
            }
            float deltaX = ImGui.getMouseDragDeltaX(0);
            float deltaY = ImGui.getMouseDragDeltaY(0);
            ImGui.setWindowPos(windowTitle,
                    ImGui.getWindowPosX() + deltaX,
                    ImGui.getWindowPosY() + deltaY);
            ImGui.resetMouseDragDelta(0);
        } else if (isDragging) {
            isDragging = false;
        }

        // --- Title text ---
        ImGui.setCursorPos(TITLE_PADDING, (TITLE_BAR_HEIGHT - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(windowTitle);

        // --- Control buttons (right-aligned, flush) ---
        float buttonStartX = windowWidth - controlsWidth;
        ImGui.setCursorPos(buttonStartX, 0);

        ImVec4 textCol = ImGui.getStyle().getColor(ImGuiCol.Text);
        int iconColor = ImGui.getColorU32(textCol.x, textCol.y, textCol.z, 0.85f);

        boolean minClicked = false;
        boolean maxClicked = false;
        boolean closeClicked = false;

        float currentBtnX = buttonStartX;

        // Minimize
        if (showMinimize) {
            renderButton("##TBMinimize", BUTTON_WIDTH, TITLE_BAR_HEIGHT, false);
            minClicked = ImGui.isItemClicked();
            drawMinimizeIcon(winX + currentBtnX, winY, BUTTON_WIDTH, TITLE_BAR_HEIGHT, iconColor);
            ImGui.sameLine(0, 0);
            currentBtnX += BUTTON_WIDTH;
        }

        // Maximize / Restore
        if (showMaximize) {
            renderButton("##TBMaximize", BUTTON_WIDTH, TITLE_BAR_HEIGHT, false);
            maxClicked = ImGui.isItemClicked();
            if (maximized) {
                drawRestoreIcon(winX + currentBtnX, winY, BUTTON_WIDTH, TITLE_BAR_HEIGHT, iconColor);
            } else {
                drawMaximizeIcon(winX + currentBtnX, winY, BUTTON_WIDTH, TITLE_BAR_HEIGHT, iconColor);
            }
            ImGui.sameLine(0, 0);
            currentBtnX += BUTTON_WIDTH;
        }

        // Close (always shown)
        renderButton("##TBClose", BUTTON_WIDTH, TITLE_BAR_HEIGHT, true);
        closeClicked = ImGui.isItemClicked();
        drawCloseIcon(winX + currentBtnX, winY, BUTTON_WIDTH, TITLE_BAR_HEIGHT,
                ImGui.isItemHovered() ? ImGui.getColorU32(1.0f, 1.0f, 1.0f, 1.0f) : iconColor);

        // --- Bottom separator ---
        ImGui.setCursorPosY(TITLE_BAR_HEIGHT);
        ImGui.separator();
        ImGui.setCursorPosY(TITLE_BAR_HEIGHT + 2);

        return new Result(minClicked, maxClicked, closeClicked);
    }

    // ===========================
    // Button rendering
    // ===========================

    private void renderButton(String id, float width, float height, boolean isClose) {
        if (isClose) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.90f, 0.18f, 0.18f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.75f, 0.12f, 0.12f, 1.0f);
        } else {
            ImVec4 text = ImGui.getStyle().getColor(ImGuiCol.Text);
            ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, text.x, text.y, text.z, 0.12f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, text.x, text.y, text.z, 0.20f);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 0.0f);

        ImGui.button(id, width, height);

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    // ===========================
    // Icon drawing (draw list)
    // ===========================

    private void drawMinimizeIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        ImGui.getWindowDrawList().addLine(
                cx - ICON_HALF_SIZE, cy,
                cx + ICON_HALF_SIZE, cy,
                color, ICON_STROKE);
    }

    private void drawMaximizeIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        ImGui.getWindowDrawList().addRect(
                cx - ICON_HALF_SIZE, cy - ICON_HALF_SIZE,
                cx + ICON_HALF_SIZE, cy + ICON_HALF_SIZE,
                color, 0.0f, 0, ICON_STROKE);
    }

    private void drawRestoreIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        float size = 4.5f;
        float offset = 2.5f;

        // Back rectangle (top-right)
        ImGui.getWindowDrawList().addRect(
                cx - size + offset, cy - size - offset,
                cx + size + offset, cy + size - offset,
                color, 0.0f, 0, ICON_STROKE);

        // Front rectangle (bottom-left, with filled background to occlude back rect)
        ImVec4 bg = ImGui.getStyle().getColor(ImGuiCol.TitleBgActive);
        int bgColor = ImGui.getColorU32(bg.x, bg.y, bg.z, bg.w);
        ImGui.getWindowDrawList().addRectFilled(
                cx - size, cy - size,
                cx + size, cy + size,
                bgColor);
        ImGui.getWindowDrawList().addRect(
                cx - size, cy - size,
                cx + size, cy + size,
                color, 0.0f, 0, ICON_STROKE);
    }

    private void drawCloseIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        ImGui.getWindowDrawList().addLine(
                cx - ICON_HALF_SIZE, cy - ICON_HALF_SIZE,
                cx + ICON_HALF_SIZE, cy + ICON_HALF_SIZE,
                color, ICON_STROKE);
        ImGui.getWindowDrawList().addLine(
                cx + ICON_HALF_SIZE, cy - ICON_HALF_SIZE,
                cx - ICON_HALF_SIZE, cy + ICON_HALF_SIZE,
                color, ICON_STROKE);
    }
}
