package com.openmason.main.systems.menus.windows;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.services.drop.PendingFileDrops;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone window wrapper for TextureCreatorImGui with internal dockspace.
 * Provides independent floating window with layout persistence and state preservation.
 */
public class TextureEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorWindow.class);

    private static final String WINDOW_TITLE = "Texture Editor";
    private static final String DOCKSPACE_NAME = "TextureEditorWindowDockspace";

    private final TextureCreatorImGui textureCreator;
    private final ImBoolean visible;

    private boolean iniFileSet = false;

    // Title bar state
    private boolean isMaximized = false;
    private final float[] savedSize = new float[]{1200, 800};
    private final float[] savedPos = new float[]{100, 100};

    // Drag state for smooth window movement
    private boolean isDraggingWindow = false;
    private float cachedWindowWidth = 0.0f;

    /**
     * Create a new TextureEditorWindow.
     */
    public TextureEditorWindow(TextureCreatorImGui textureCreator) {
        if (textureCreator == null) {
            throw new IllegalArgumentException("TextureCreatorImGui cannot be null");
        }

        this.textureCreator = textureCreator;
        this.visible = new ImBoolean(false);

        textureCreator.setWindowedMode(true);
        logger.debug("TextureEditorWindow created with windowed mode enabled");
    }

    /**
     * Render the texture editor window.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(1200, 800);
            ImGui.setNextWindowPos(100, 100);
            iniFileSet = true;
        }

        // Minimum size ensures all UI elements remain visible
        ImGui.setNextWindowSizeConstraints(600, 400, Float.MAX_VALUE, Float.MAX_VALUE);
        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus |
                          ImGuiWindowFlags.NoDocking |
                          ImGuiWindowFlags.NoTitleBar |
                          ImGuiWindowFlags.NoCollapse |
                          ImGuiWindowFlags.NoScrollbar;

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            processPendingFileDrops();

            try {
                // Handle shortcuts unless actively typing
                boolean activelyTyping = ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput();
                if (!activelyTyping) {
                    textureCreator.handleKeyboardShortcuts();
                }

                renderCustomTitleBar();
                textureCreator.renderWindowedMenuBar();
                textureCreator.renderWindowedToolbar();

                int dockspaceId = ImGui.getID(DOCKSPACE_NAME);
                ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);

                textureCreator.renderWindowedPanels();
                renderResizeGrip();

            } catch (Exception e) {
                logger.error("Error rendering texture creator", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering texture editor");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    /**
     * Render custom title bar with window controls.
     */
    private void renderCustomTitleBar() {
        final float titleBarHeight = 30.0f;
        final float buttonWidth = 46.0f;
        final float buttonHeight = titleBarHeight;
        final float titlePadding = 10.0f;

        // Use cached width during drag to prevent flickering
        float windowWidth;
        if (!isDraggingWindow) {
            windowWidth = ImGui.getWindowWidth();
            cachedWindowWidth = windowWidth;
        } else {
            windowWidth = cachedWindowWidth;
        }

        float winX = ImGui.getWindowPosX();
        float winY = ImGui.getWindowPosY();

        // Title bar background — derived from theme TitleBgActive
        imgui.ImVec4 titleBg = ImGui.getStyle().getColor(imgui.flag.ImGuiCol.TitleBgActive);
        ImGui.getWindowDrawList().addRectFilled(
            winX, winY,
            winX + windowWidth, winY + titleBarHeight,
            ImGui.getColorU32(titleBg.x, titleBg.y, titleBg.z, titleBg.w)
        );

        // Draggable title bar area (everything except the buttons)
        float controlsWidth = buttonWidth * 3;
        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##TitleBarDrag", windowWidth - controlsWidth, titleBarHeight);

        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll);
        }
        boolean currentlyDragging = ImGui.isItemActive() && ImGui.isMouseDragging(0);

        if (currentlyDragging) {
            if (!isDraggingWindow) {
                isDraggingWindow = true;
                cachedWindowWidth = ImGui.getWindowWidth();
            }

            float deltaX = ImGui.getMouseDragDeltaX(0);
            float deltaY = ImGui.getMouseDragDeltaY(0);
            ImGui.setWindowPos(
                WINDOW_TITLE,
                ImGui.getWindowPosX() + deltaX,
                ImGui.getWindowPosY() + deltaY
            );
            ImGui.resetMouseDragDelta(0);
        } else if (isDraggingWindow) {
            isDraggingWindow = false;
        }

        // Title text
        ImGui.setCursorPos(titlePadding, (titleBarHeight - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(WINDOW_TITLE);

        // Window control buttons — flush right, full title bar height
        float buttonStartX = windowWidth - controlsWidth;
        ImGui.setCursorPos(buttonStartX, 0);

        imgui.ImVec4 textCol = ImGui.getStyle().getColor(imgui.flag.ImGuiCol.Text);
        int iconColor = ImGui.getColorU32(textCol.x, textCol.y, textCol.z, 0.85f);

        // --- Minimize button ---
        renderTitleBarButton("##Minimize", buttonWidth, buttonHeight, false);
        boolean minClicked = ImGui.isItemClicked();
        drawMinimizeIcon(winX + buttonStartX, winY, buttonWidth, buttonHeight, iconColor);
        if (minClicked) {
            handleMinimize();
        }

        // --- Maximize/Restore button ---
        ImGui.sameLine(0, 0);
        renderTitleBarButton("##Maximize", buttonWidth, buttonHeight, false);
        boolean maxClicked = ImGui.isItemClicked();
        if (isMaximized) {
            drawRestoreIcon(winX + buttonStartX + buttonWidth, winY, buttonWidth, buttonHeight, iconColor);
        } else {
            drawMaximizeIcon(winX + buttonStartX + buttonWidth, winY, buttonWidth, buttonHeight, iconColor);
        }
        if (maxClicked) {
            handleMaximize();
        }

        // --- Close button (red hover) ---
        ImGui.sameLine(0, 0);
        renderTitleBarButton("##Close", buttonWidth, buttonHeight, true);
        boolean closeClicked = ImGui.isItemClicked();
        drawCloseIcon(winX + buttonStartX + buttonWidth * 2, winY, buttonWidth, buttonHeight,
                ImGui.isItemHovered() ? ImGui.getColorU32(1.0f, 1.0f, 1.0f, 1.0f) : iconColor);
        if (closeClicked) {
            visible.set(false);
        }

        ImGui.setCursorPosY(titleBarHeight);
        ImGui.separator();
        ImGui.setCursorPosY(titleBarHeight + 2);
    }

    /**
     * Render an invisible button with hover/active highlights for title bar controls.
     */
    private void renderTitleBarButton(String id, float width, float height, boolean isClose) {
        if (isClose) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.90f, 0.18f, 0.18f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.75f, 0.12f, 0.12f, 1.0f);
        } else {
            imgui.ImVec4 textCol = ImGui.getStyle().getColor(imgui.flag.ImGuiCol.Text);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, textCol.x, textCol.y, textCol.z, 0.12f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, textCol.x, textCol.y, textCol.z, 0.20f);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 0.0f);

        ImGui.button(id, width, height);

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    /**
     * Draw minimize icon: horizontal line centered in button area.
     */
    private void drawMinimizeIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        float halfW = 5.0f;
        ImGui.getWindowDrawList().addLine(cx - halfW, cy, cx + halfW, cy, color, 1.2f);
    }

    /**
     * Draw maximize icon: square outline centered in button area.
     */
    private void drawMaximizeIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        float half = 5.0f;
        ImGui.getWindowDrawList().addRect(cx - half, cy - half, cx + half, cy + half, color, 0.0f, 0, 1.2f);
    }

    /**
     * Draw restore icon: two overlapping rectangles centered in button area.
     */
    private void drawRestoreIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        float size = 4.5f;
        float offset = 2.5f;

        // Back rectangle (top-right, partially occluded)
        ImGui.getWindowDrawList().addRect(
                cx - size + offset, cy - size - offset,
                cx + size + offset, cy + size - offset,
                color, 0.0f, 0, 1.2f);

        // Front rectangle (bottom-left, filled background to occlude back rect)
        imgui.ImVec4 titleBg = ImGui.getStyle().getColor(imgui.flag.ImGuiCol.TitleBgActive);
        int bgColor = ImGui.getColorU32(titleBg.x, titleBg.y, titleBg.z, titleBg.w);
        ImGui.getWindowDrawList().addRectFilled(
                cx - size, cy - size,
                cx + size, cy + size,
                bgColor);
        ImGui.getWindowDrawList().addRect(
                cx - size, cy - size,
                cx + size, cy + size,
                color, 0.0f, 0, 1.2f);
    }

    /**
     * Draw close icon: X shape centered in button area.
     */
    private void drawCloseIcon(float btnX, float btnY, float btnW, float btnH, int color) {
        float cx = btnX + btnW * 0.5f;
        float cy = btnY + btnH * 0.5f;
        float half = 5.0f;
        ImGui.getWindowDrawList().addLine(cx - half, cy - half, cx + half, cy + half, color, 1.2f);
        ImGui.getWindowDrawList().addLine(cx + half, cy - half, cx - half, cy + half, color, 1.2f);
    }

    /**
     * Render resize grip in bottom-right corner.
     */
    private void renderResizeGrip() {
        final float triangleSize = 14.0f;

        float windowX = ImGui.getWindowPosX();
        float windowY = ImGui.getWindowPosY();
        float windowWidth = ImGui.getWindowWidth();
        float windowHeight = ImGui.getWindowHeight();

        float cornerX = windowX + windowWidth;
        float cornerY = windowY + windowHeight;

        int color = ImGui.getColorU32(0.6f, 0.6f, 0.6f, 0.8f);

        ImGui.getForegroundDrawList().addTriangleFilled(
            cornerX, cornerY - triangleSize,
            cornerX - triangleSize, cornerY,
            cornerX, cornerY,
            color
        );
    }

    /**
     * Handle minimize - hides window.
     */
    private void handleMinimize() {
        visible.set(false);
        logger.debug("Texture editor window minimized (hidden)");
    }

    /**
     * Handle maximize/restore - toggles between fullscreen and saved size.
     */
    private void handleMaximize() {
        if (isMaximized) {
            ImGui.setWindowSize(WINDOW_TITLE, savedSize[0], savedSize[1]);
            ImGui.setWindowPos(WINDOW_TITLE, savedPos[0], savedPos[1]);
            isMaximized = false;
            logger.debug("Texture editor window restored to {}x{} at ({}, {})",
                savedSize[0], savedSize[1], savedPos[0], savedPos[1]);
        } else {
            savedSize[0] = ImGui.getWindowWidth();
            savedSize[1] = ImGui.getWindowHeight();
            savedPos[0] = ImGui.getWindowPosX();
            savedPos[1] = ImGui.getWindowPosY();

            long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
            if (primaryMonitor != 0) {
                int[] workAreaX = new int[1];
                int[] workAreaY = new int[1];
                int[] workAreaWidth = new int[1];
                int[] workAreaHeight = new int[1];
                GLFW.glfwGetMonitorWorkarea(primaryMonitor, workAreaX, workAreaY, workAreaWidth, workAreaHeight);

                ImGui.setWindowSize(WINDOW_TITLE, workAreaWidth[0], workAreaHeight[0]);
                ImGui.setWindowPos(WINDOW_TITLE, workAreaX[0], workAreaY[0]);
                isMaximized = true;
                logger.debug("Texture editor window maximized to {}x{} at ({}, {}) - work area",
                    workAreaWidth[0], workAreaHeight[0], workAreaX[0], workAreaY[0]);
            } else {
                imgui.ImGuiViewport mainViewport = ImGui.getMainViewport();
                ImGui.setWindowSize(WINDOW_TITLE, mainViewport.getSizeX(), mainViewport.getSizeY());
                ImGui.setWindowPos(WINDOW_TITLE, mainViewport.getPosX(), mainViewport.getPosY());
                isMaximized = true;
                logger.warn("Failed to get primary monitor, using viewport fallback");
            }
        }
    }

    public void show() {
        visible.set(true);
        logger.debug("Texture editor window shown");
    }

    public void hide() {
        visible.set(false);
        logger.debug("Texture editor window hidden");
    }

    public void toggle() {
        visible.set(!visible.get());
        logger.debug("Texture editor window toggled: {}", visible.get());
    }

    public boolean isVisible() {
        return visible.get();
    }

    public void setVisible(boolean visible) {
        this.visible.set(visible);
        logger.debug("Texture editor window visibility set to: {}", visible);
    }

    public TextureCreatorImGui getTextureCreator() {
        return textureCreator;
    }

    /**
     * Process pending file drops from GLFW callbacks.
     */
    private void processPendingFileDrops() {
        while (PendingFileDrops.hasPending()) {
            String[] filePaths = PendingFileDrops.poll();
            if (filePaths != null && filePaths.length > 0) {
                logger.debug("Processing {} dropped file(s) in texture editor", filePaths.length);
                textureCreator.processDroppedFiles(filePaths);
            }
        }
    }
}
