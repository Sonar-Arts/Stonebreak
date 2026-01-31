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
        final float buttonSize = 25.0f;
        final float buttonSpacing = 2.0f;
        final float titlePadding = 10.0f;

        // Use cached width during drag to prevent flickering
        float windowWidth;
        if (!isDraggingWindow) {
            windowWidth = ImGui.getWindowWidth();
            cachedWindowWidth = windowWidth;
        } else {
            windowWidth = cachedWindowWidth;
        }
        ImGui.getWindowDrawList().addRectFilled(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowPosX() + windowWidth,
            ImGui.getWindowPosY() + titleBarHeight,
            ImGui.getColorU32(0.15f, 0.15f, 0.15f, 1.0f)
        );

        // Draggable title bar area
        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##TitleBarDrag", windowWidth - (buttonSize + buttonSpacing) * 3, titleBarHeight);

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

        ImGui.setCursorPos(titlePadding, (titleBarHeight - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(WINDOW_TITLE);

        float buttonStartX = windowWidth - (buttonSize + buttonSpacing) * 3 - buttonSpacing;
        float buttonStartY = (titleBarHeight - buttonSize) * 0.5f;
        ImGui.setCursorPos(buttonStartX, buttonStartY);

        ImGui.pushStyleVar(ImGuiStyleVar.ButtonTextAlign, 0.5f, 0.5f);

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.4f, 0.4f, 0.4f, 1.0f);
        if (ImGui.button("-##Minimize", buttonSize, buttonSize)) {
            handleMinimize();
        }
        ImGui.popStyleColor(3);

        ImGui.sameLine(0, buttonSpacing);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.4f, 0.4f, 0.4f, 1.0f);
        if (ImGui.button("[]##Maximize", buttonSize, buttonSize)) {
            handleMaximize();
        }
        ImGui.popStyleColor(3);

        ImGui.sameLine(0, buttonSpacing);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.8f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 1.0f, 0.3f, 0.3f, 1.0f);
        if (ImGui.button("×##Close", buttonSize, buttonSize)) {
            visible.set(false);
        }
        ImGui.popStyleColor(3);

        ImGui.popStyleVar();

        ImGui.setCursorPosY(titleBarHeight);
        ImGui.separator();
        ImGui.setCursorPosY(titleBarHeight + 2);
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
