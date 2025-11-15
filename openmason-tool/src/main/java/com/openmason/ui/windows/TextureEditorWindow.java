package com.openmason.ui.windows;

import com.openmason.ui.components.textureCreator.TextureCreatorImGui;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for TextureCreatorImGui that renders it in a standalone ImGui window with dockspace.
 *
 * This class follows SOLID principles:
 * - Single Responsibility: Manages window lifecycle, visibility, and internal dockspace
 * - Open/Closed: TextureCreatorImGui remains unchanged, we wrap it
 * - Dependency Inversion: Depends on TextureCreatorImGui interface, not implementation details
 *
 * Design Goals:
 * - Independent floating window (not dockable into main interface)
 * - Internal dockspace for texture editor panels
 * - Preserves state when hidden/shown
 * - Uses separate ini file for layout persistence
 * - Allows simultaneous viewing with Model Viewer
 * - Focus-based keyboard shortcut handling (shortcuts only work when window is focused)
 */
public class TextureEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorWindow.class);

    private static final String WINDOW_TITLE = "Texture Editor";
    private static final String DOCKSPACE_NAME = "TextureEditorWindowDockspace";
    private static final String INI_FILENAME = "texture_editor.ini";

    private final TextureCreatorImGui textureCreator;
    private final ImBoolean visible;

    private boolean iniFileSet = false;
    private boolean isWindowed = true;  // Flag to indicate we're in windowed mode

    // Window state management for custom title bar controls
    private boolean isMaximized = false;
    private float[] savedSize = new float[]{1200, 800};  // Store window size before maximize
    private float[] savedPos = new float[]{100, 100};    // Store window position before maximize

    // Drag state management to prevent flickering during window movement
    private boolean isDraggingWindow = false;
    private float cachedWindowWidth = 0.0f;

    /**
     * Create a new TextureEditorWindow wrapping the given texture creator.
     *
     * @param textureCreator The texture creator UI to wrap
     */
    public TextureEditorWindow(TextureCreatorImGui textureCreator) {
        if (textureCreator == null) {
            throw new IllegalArgumentException("TextureCreatorImGui cannot be null");
        }

        this.textureCreator = textureCreator;
        this.visible = new ImBoolean(false);

        // Enable windowed mode so texture creator doesn't create fullscreen dockspace
        textureCreator.setWindowedMode(true);

        logger.debug("TextureEditorWindow created with windowed mode enabled");
    }

    /**
     * Render the texture editor window with internal dockspace.
     * Only renders if visible flag is true.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        // Set initial size and position for the window (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(1200, 800);
            ImGui.setNextWindowPos(100, 100);

            String currentIni = ImGui.getIO().getIniFilename();
            logger.debug("Current ini file: {}", currentIni);
            // Note: We'll keep using the main ini file for now to avoid issues
            // Each window will have unique IDs so they won't conflict
            iniFileSet = true;
        }

        // Set size constraints to prevent unwanted size changes during dragging
        // Minimum size ensures all UI elements remain visible and functional
        ImGui.setNextWindowSizeConstraints(600, 400, Float.MAX_VALUE, Float.MAX_VALUE);

        // Configure window flags for a truly separate window with custom title bar
        // NoDocking prevents this window from being docked into the main interface
        // NoTitleBar allows us to render custom title bar with minimize/maximize buttons
        // NoCollapse prevents double-click collapse behavior (we have custom controls)
        // NoScrollbar prevents main window scrollbar (child panels handle their own scrolling)
        // Window is resizable by default - ImGui handles resize grips automatically
        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus |
                          ImGuiWindowFlags.NoDocking |
                          ImGuiWindowFlags.NoTitleBar |
                          ImGuiWindowFlags.NoCollapse |
                          ImGuiWindowFlags.NoScrollbar;

        // Remove window padding to eliminate space above menu bar
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        // Begin standalone window with custom title bar
        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                // Handle keyboard shortcuts FIRST, before any widgets consume input
                // Only block shortcuts if actively typing in a text input field
                // The window focus check was removed because ImGui.isWindowFocused() returns false
                // when child panels/dockspace have focus, even though the window is active
                boolean activelyTyping = ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput();
                if (!activelyTyping) {
                    textureCreator.handleKeyboardShortcuts();
                }

                // Render custom title bar with minimize/maximize/close buttons
                renderCustomTitleBar();

                // Render menu bar and toolbar at the top (BEFORE dockspace)
                textureCreator.renderWindowedMenuBar();
                textureCreator.renderWindowedToolbar();

                // Create dockspace below menu/toolbar for panels
                // NOTE: Removed PassthruCentralNode flag to prevent input bleeding through to main window
                // Without this flag, ImGui will properly capture input within this window
                int dockspaceId = ImGui.getID(DOCKSPACE_NAME);
                ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.None);

                // Render panels, dialogs, etc.
                textureCreator.renderWindowedPanels();

                // Render resize grip in bottom-right corner
                renderResizeGrip();

            } catch (Exception e) {
                logger.error("Error rendering texture creator", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering texture editor");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();

        // Pop window padding style
        ImGui.popStyleVar();

        // If window was closed via X button, visible.get() will now be false
        // State is preserved in textureCreator for next time window opens
    }

    /**
     * Render custom title bar with minimize, maximize, and close buttons.
     * This replaces the default ImGui title bar with custom controls.
     */
    private void renderCustomTitleBar() {
        final float titleBarHeight = 30.0f;
        final float buttonSize = 25.0f;
        final float buttonSpacing = 2.0f;  // Horizontal spacing between buttons
        final float titlePadding = 10.0f;

        // Get window dimensions - use cached value during drag to prevent flickering
        float windowWidth;
        if (!isDraggingWindow) {
            windowWidth = ImGui.getWindowWidth();
            cachedWindowWidth = windowWidth;  // Cache for use during dragging
        } else {
            windowWidth = cachedWindowWidth;  // Use cached value to maintain stability
        }
        float cursorStartY = ImGui.getCursorPosY();

        // Title bar background (using a subtle background color)
        ImGui.getWindowDrawList().addRectFilled(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowPosX() + windowWidth,
            ImGui.getWindowPosY() + titleBarHeight,
            ImGui.getColorU32(0.15f, 0.15f, 0.15f, 1.0f)
        );

        // Make title bar draggable for window movement
        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##TitleBarDrag", windowWidth - (buttonSize + buttonSpacing) * 3, titleBarHeight);

        // Show move cursor when hovering over draggable area to indicate it can be dragged
        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll);
        }

        // Track drag state and handle window movement
        boolean currentlyDragging = ImGui.isItemActive() && ImGui.isMouseDragging(0);

        if (currentlyDragging) {
            // Set drag flag on first frame of drag
            if (!isDraggingWindow) {
                isDraggingWindow = true;
                cachedWindowWidth = ImGui.getWindowWidth();  // Cache width at drag start
            }

            // Apply position delta
            float deltaX = ImGui.getMouseDragDeltaX(0);
            float deltaY = ImGui.getMouseDragDeltaY(0);
            ImGui.setWindowPos(
                WINDOW_TITLE,
                ImGui.getWindowPosX() + deltaX,
                ImGui.getWindowPosY() + deltaY
            );
            ImGui.resetMouseDragDelta(0);
        } else if (isDraggingWindow) {
            // Clear drag flag when drag ends
            isDraggingWindow = false;
        }

        // Render window title text
        ImGui.setCursorPos(titlePadding, (titleBarHeight - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(WINDOW_TITLE);

        // Calculate button positions (right-aligned and vertically centered)
        float buttonStartX = windowWidth - (buttonSize + buttonSpacing) * 3 - buttonSpacing;
        float buttonStartY = (titleBarHeight - buttonSize) * 0.5f;  // Perfect vertical centering
        ImGui.setCursorPos(buttonStartX, buttonStartY);

        // Push button text alignment to ensure perfect centering of all button icons
        ImGui.pushStyleVar(ImGuiStyleVar.ButtonTextAlign, 0.5f, 0.5f);

        // Minimize button
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.4f, 0.4f, 0.4f, 1.0f);
        if (ImGui.button("-##Minimize", buttonSize, buttonSize)) {
            handleMinimize();
        }
        ImGui.popStyleColor(3);

        // Maximize/Restore button
        ImGui.sameLine(0, buttonSpacing);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.4f, 0.4f, 0.4f, 1.0f);
        // Use consistent symbol with no spacing - centered properly
        if (ImGui.button("[]##Maximize", buttonSize, buttonSize)) {
            handleMaximize();
        }
        ImGui.popStyleColor(3);

        // Close button (red hover) - Using × symbol for better centering
        ImGui.sameLine(0, buttonSpacing);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.8f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 1.0f, 0.3f, 0.3f, 1.0f);
        if (ImGui.button("×##Close", buttonSize, buttonSize)) {
            visible.set(false);
        }
        ImGui.popStyleColor(3);

        // Pop button text alignment style
        ImGui.popStyleVar();

        // Add separator line below title bar
        ImGui.setCursorPosY(titleBarHeight);
        ImGui.separator();

        // Reset cursor for content below
        ImGui.setCursorPosY(titleBarHeight + 2);
    }

    /**
     * Render a resize grip indicator in the bottom-right corner of the window.
     * Shows a simple filled triangle pointing to the corner.
     */
    private void renderResizeGrip() {
        final float triangleSize = 14.0f;

        // Get window position and dimensions
        float windowX = ImGui.getWindowPosX();
        float windowY = ImGui.getWindowPosY();
        float windowWidth = ImGui.getWindowWidth();
        float windowHeight = ImGui.getWindowHeight();

        // Calculate triangle position (bottom-right corner)
        float cornerX = windowX + windowWidth;
        float cornerY = windowY + windowHeight;

        // Draw filled triangle pointing to bottom-right corner
        int color = ImGui.getColorU32(0.6f, 0.6f, 0.6f, 0.8f);

        // Use foreground draw list for proper render precedence
        ImGui.getForegroundDrawList().addTriangleFilled(
            cornerX, cornerY - triangleSize,           // Top point (right edge)
            cornerX - triangleSize, cornerY,           // Left point (bottom edge)
            cornerX, cornerY,                          // Corner point (bottom-right)
            color
        );
    }

    /**
     * Handle minimize button click - hides the window completely.
     * Window can be reopened from the Tools menu.
     */
    private void handleMinimize() {
        visible.set(false);
        logger.debug("Texture editor window minimized (hidden)");
    }

    /**
     * Handle maximize/restore button click - toggles between maximized and normal size.
     * When maximizing, fills the entire screen window (minus taskbar and system UI).
     * When restoring, returns to the saved size and position.
     */
    private void handleMaximize() {
        if (isMaximized) {
            // Restore to saved size and position
            ImGui.setWindowSize(WINDOW_TITLE, savedSize[0], savedSize[1]);
            ImGui.setWindowPos(WINDOW_TITLE, savedPos[0], savedPos[1]);
            isMaximized = false;
            logger.debug("Texture editor window restored to {}x{} at ({}, {})",
                savedSize[0], savedSize[1], savedPos[0], savedPos[1]);
        } else {
            // Save current size and position before maximizing
            savedSize[0] = ImGui.getWindowWidth();
            savedSize[1] = ImGui.getWindowHeight();
            savedPos[0] = ImGui.getWindowPosX();
            savedPos[1] = ImGui.getWindowPosY();

            // Get primary monitor
            long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
            if (primaryMonitor != 0) {
                // Get monitor work area (screen minus taskbar and system UI)
                int[] workAreaX = new int[1];
                int[] workAreaY = new int[1];
                int[] workAreaWidth = new int[1];
                int[] workAreaHeight = new int[1];
                GLFW.glfwGetMonitorWorkarea(primaryMonitor, workAreaX, workAreaY, workAreaWidth, workAreaHeight);

                // Maximize to fill the work area (entire screen minus taskbar)
                ImGui.setWindowSize(WINDOW_TITLE, workAreaWidth[0], workAreaHeight[0]);
                ImGui.setWindowPos(WINDOW_TITLE, workAreaX[0], workAreaY[0]);
                isMaximized = true;
                logger.debug("Texture editor window maximized to {}x{} at ({}, {}) - work area",
                    workAreaWidth[0], workAreaHeight[0], workAreaX[0], workAreaY[0]);
            } else {
                // Fallback to viewport if monitor detection fails
                imgui.ImGuiViewport mainViewport = ImGui.getMainViewport();
                ImGui.setWindowSize(WINDOW_TITLE, mainViewport.getSizeX(), mainViewport.getSizeY());
                ImGui.setWindowPos(WINDOW_TITLE, mainViewport.getPosX(), mainViewport.getPosY());
                isMaximized = true;
                logger.warn("Failed to get primary monitor, using viewport fallback");
            }
        }
    }

    /**
     * Show the texture editor window.
     */
    public void show() {
        visible.set(true);
        logger.debug("Texture editor window shown");
    }

    /**
     * Hide the texture editor window.
     * State is preserved for next showing.
     */
    public void hide() {
        visible.set(false);
        logger.debug("Texture editor window hidden");
    }

    /**
     * Toggle the texture editor window visibility.
     */
    public void toggle() {
        visible.set(!visible.get());
        logger.debug("Texture editor window toggled: {}", visible.get());
    }

    /**
     * Check if the texture editor window is currently visible.
     *
     * @return true if visible, false otherwise
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Set the visibility of the texture editor window.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible.set(visible);
        logger.debug("Texture editor window visibility set to: {}", visible);
    }

    /**
     * Get the underlying TextureCreatorImGui instance.
     * Useful for setting callbacks or accessing texture creator state.
     *
     * @return the texture creator instance
     */
    public TextureCreatorImGui getTextureCreator() {
        return textureCreator;
    }
}
