package com.openmason.ui.preferences;

import com.openmason.ui.components.textureCreator.TextureCreatorImGui;
import com.openmason.ui.properties.PropertyPanelImGui;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.ViewportController;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified preferences window for Open Mason.
 * <p>
 * Provides a centralized interface for managing preferences across all tools:
 * - Model Viewer settings
 * - Texture Editor settings
 * - Common settings (theme, UI density)
 * </p>
 * <p>
 * Features:
 * - Floating standalone window (TextureEditorWindow style)
 * - Custom title bar with minimize/close buttons
 * - Left sidebar navigation with page selection
 * - Right content area with selected page
 * - KISS, YAGNI, DRY, and SOLID principles
 * </p>
 */
public class UnifiedPreferencesWindow {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedPreferencesWindow.class);

    private static final String WINDOW_TITLE = "Preferences";
    private static final float SIDEBAR_WIDTH = 150.0f;
    private static final float MIN_WINDOW_WIDTH = 800.0f;
    private static final float MIN_WINDOW_HEIGHT = 600.0f;

    // Window visibility state
    private final ImBoolean visible;

    // State management
    private final PreferencesState state;

    // Unified page renderer
    private final UnifiedPreferencesPageRenderer pageRenderer;

    // Window state
    private boolean iniFileSet = false;
    private boolean isDraggingWindow = false;
    private float cachedWindowWidth = 0.0f;

    /**
     * Creates a new unified preferences window.
     *
     * @param visible               external visibility state (typically from UIVisibilityState)
     * @param preferencesManager    the preferences manager for persistence
     * @param themeManager          the theme manager for appearance settings
     * @param textureCreatorImGui   the texture creator instance (for accessing preferences)
     * @param viewport              the 3D viewport for real-time camera updates (can be null)
     * @param propertyPanel         the property panel for real-time UI updates (can be null)
     */
    public UnifiedPreferencesWindow(ImBoolean visible,
                                    PreferencesManager preferencesManager,
                                    ThemeManager themeManager,
                                    TextureCreatorImGui textureCreatorImGui,
                                    ViewportController viewport,
                                    PropertyPanelImGui propertyPanel) {
        if (visible == null) {
            throw new IllegalArgumentException("Visibility state cannot be null");
        }

        this.visible = visible;
        this.state = new PreferencesState();

        // Initialize unified page renderer with all dependencies
        this.pageRenderer = new UnifiedPreferencesPageRenderer(
                preferencesManager,
                themeManager,
                textureCreatorImGui,
                viewport,
                propertyPanel
        );

        logger.debug("Unified preferences window created");
    }

    /**
     * Shows the preferences window.
     */
    public void show() {
        visible.set(true);
        logger.debug("Preferences window shown");
    }

    /**
     * Hides the preferences window.
     */
    public void hide() {
        visible.set(false);
        logger.debug("Preferences window hidden");
    }

    /**
     * Checks if the preferences window is visible.
     *
     * @return true if visible, false otherwise
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Gets the visibility state (for external control).
     *
     * @return the ImBoolean visibility state
     */
    public ImBoolean getVisibleState() {
        return visible;
    }

    /**
     * Renders the unified preferences window.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
            ImGui.setNextWindowPos(200, 100);
            iniFileSet = true;
        }

        // Set size constraints
        ImGui.setNextWindowSizeConstraints(
                MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT,
                Float.MAX_VALUE, Float.MAX_VALUE
        );

        // Configure window flags for standalone floating window
        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus |
                ImGuiWindowFlags.NoDocking |
                ImGuiWindowFlags.NoTitleBar |
                ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoScrollbar;

        // Remove window padding for tight layout
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        // Begin window
        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                // Render custom title bar
                renderCustomTitleBar();

                // Render sidebar and content area
                renderContent();

            } catch (Exception e) {
                logger.error("Error rendering preferences window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering preferences");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();

        // Pop window padding style
        ImGui.popStyleVar();
    }

    /**
     * Renders custom title bar with minimize and close buttons.
     */
    private void renderCustomTitleBar() {
        final float titleBarHeight = 30.0f;
        final float buttonSize = 25.0f;
        final float buttonSpacing = 2.0f;
        final float titlePadding = 10.0f;

        // Get window width - use cached value during drag to prevent flickering
        float windowWidth;
        if (!isDraggingWindow) {
            windowWidth = ImGui.getWindowWidth();
            cachedWindowWidth = windowWidth;
        } else {
            windowWidth = cachedWindowWidth;
        }

        // Title bar background
        ImGui.getWindowDrawList().addRectFilled(
                ImGui.getWindowPosX(),
                ImGui.getWindowPosY(),
                ImGui.getWindowPosX() + windowWidth,
                ImGui.getWindowPosY() + titleBarHeight,
                ImGui.getColorU32(0.15f, 0.15f, 0.15f, 1.0f)
        );

        // Make title bar draggable
        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##TitleBarDrag",
                windowWidth - (buttonSize + buttonSpacing) * 2, titleBarHeight);

        // Show move cursor when hovering
        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll);
        }

        // Track drag state and handle window movement
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

        // Render window title text
        ImGui.setCursorPos(titlePadding, (titleBarHeight - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(WINDOW_TITLE);

        // Calculate button positions (right-aligned, only minimize and close)
        float buttonStartX = windowWidth - (buttonSize + buttonSpacing) * 2 - buttonSpacing;
        float buttonStartY = (titleBarHeight - buttonSize) * 0.5f;
        ImGui.setCursorPos(buttonStartX, buttonStartY);

        // Push button text alignment for centering
        ImGui.pushStyleVar(ImGuiStyleVar.ButtonTextAlign, 0.5f, 0.5f);

        // Minimize button
        ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.4f, 0.4f, 0.4f, 1.0f);
        if (ImGui.button("-##Minimize", buttonSize, buttonSize)) {
            visible.set(false);
        }
        ImGui.popStyleColor(3);

        // Close button (red hover)
        ImGui.sameLine(0, buttonSpacing);
        ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.8f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 1.0f, 0.3f, 0.3f, 1.0f);
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
     * Renders the sidebar navigation and content area.
     */
    private void renderContent() {
        float windowWidth = ImGui.getContentRegionAvailX();
        float windowHeight = ImGui.getContentRegionAvailY();

        // Left sidebar (navigation)
        ImGui.beginChild("##Sidebar", SIDEBAR_WIDTH, windowHeight, false);
        renderSidebar();
        ImGui.endChild();

        // Vertical separator
        ImGui.sameLine();
        ImGui.getWindowDrawList().addLine(
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY(),
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY() + windowHeight,
                ImGui.getColorU32(0.5f, 0.5f, 0.5f, 0.5f),
                1.0f
        );

        // Right content area (selected page)
        ImGui.sameLine();
        float contentWidth = windowWidth - SIDEBAR_WIDTH - 10; // Account for separator
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10.0f, 10.0f);
        ImGui.beginChild("##Content", contentWidth, windowHeight, false);

        // Add horizontal indentation to push content away from separator
        ImGui.indent(15.0f);
        renderSelectedPage();
        ImGui.unindent(15.0f);

        ImGui.endChild();
        ImGui.popStyleVar();
    }

    /**
     * Renders the sidebar navigation with page buttons.
     */
    private void renderSidebar() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8.0f, 10.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0.0f, 2.0f);

        // Get current theme colors for selection highlighting
        int selectedColor = ImGui.getColorU32(ImGuiCol.Header);
        int hoveredColor = ImGui.getColorU32(ImGuiCol.ButtonHovered);

        for (PreferencesState.PreferencePage page : PreferencesState.PreferencePage.values()) {
            boolean isSelected = state.getCurrentPage() == page;

            // Highlight selected page
            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Button, selectedColor);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, selectedColor);
                ImGui.pushStyleColor(ImGuiCol.ButtonActive, selectedColor);
            }

            // Render navigation button (full width)
            if (ImGui.button(page.getDisplayName() + "##nav_" + page.name(),
                    SIDEBAR_WIDTH - 10, 40)) {
                state.setCurrentPage(page);
                logger.debug("Switched to page: {}", page.name());
            }

            if (isSelected) {
                ImGui.popStyleColor(3);
            }
        }

        ImGui.popStyleVar(2);
    }

    /**
     * Renders the currently selected page content.
     */
    private void renderSelectedPage() {
        pageRenderer.render(state.getCurrentPage());
    }

    /**
     * Sets the current page to display.
     *
     * @param page the page to display
     */
    public void setCurrentPage(PreferencesState.PreferencePage page) {
        state.setCurrentPage(page);
    }

    /**
     * Gets the current page.
     *
     * @return the current page
     */
    public PreferencesState.PreferencePage getCurrentPage() {
        return state.getCurrentPage();
    }

    /**
     * Sets the TextureCreatorImGui instance for texture editor preferences.
     * <p>
     * This allows the texture creator interface to be wired up after construction,
     * enabling real-time updates for texture editor settings.
     * </p>
     *
     * @param textureCreatorImGui the texture creator instance (can be null)
     */
    public void setTextureCreatorImGui(TextureCreatorImGui textureCreatorImGui) {
        pageRenderer.setTextureCreatorImGui(textureCreatorImGui);
    }
}
