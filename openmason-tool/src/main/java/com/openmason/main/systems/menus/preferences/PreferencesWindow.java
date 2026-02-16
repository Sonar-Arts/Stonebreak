package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.ViewportController;
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
 * Uses a deferred-apply model with OK and Apply buttons.
 * Settings are synced from persistence when the window opens,
 * and only saved/applied when the user clicks OK or Apply.
 * </p>
 */
public class PreferencesWindow {

    private static final Logger logger = LoggerFactory.getLogger(PreferencesWindow.class);

    private static final String WINDOW_TITLE = "Preferences";
    private static final float SIDEBAR_WIDTH = 150.0f;
    private static final float MIN_WINDOW_WIDTH = 800.0f;
    private static final float MIN_WINDOW_HEIGHT = 600.0f;
    private static final float FOOTER_HEIGHT = 45.0f;

    // Window visibility state
    private final ImBoolean visible;

    // State management
    private final PreferencesState state;

    // Unified page renderer
    private final PreferencesPageRenderer pageRenderer;

    // Window state
    private boolean iniFileSet = false;
    private boolean isDraggingWindow = false;
    private float cachedWindowWidth = 0.0f;
    private boolean wasVisible = false;

    /**
     * Creates a new unified preferences window.
     */
    public PreferencesWindow(ImBoolean visible,
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

        this.pageRenderer = new PreferencesPageRenderer(
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
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Renders the unified preferences window.
     */
    public void render() {
        if (!visible.get()) {
            wasVisible = false;
            return;
        }

        // Detect window open transition and sync state from persistence
        if (!wasVisible) {
            pageRenderer.onWindowOpened();
            wasVisible = true;
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
                renderCustomTitleBar();
                renderContent();
            } catch (Exception e) {
                logger.error("Error rendering preferences window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering preferences");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();

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

        // Window title text
        ImGui.setCursorPos(titlePadding, (titleBarHeight - ImGui.getFrameHeight()) * 0.5f);
        ImGui.text(WINDOW_TITLE);

        // Title bar buttons (right-aligned)
        float buttonStartX = windowWidth - (buttonSize + buttonSpacing) * 2 - buttonSpacing;
        float buttonStartY = (titleBarHeight - buttonSize) * 0.5f;
        ImGui.setCursorPos(buttonStartX, buttonStartY);

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

        ImGui.popStyleVar();

        // Separator below title bar
        ImGui.setCursorPosY(titleBarHeight);
        ImGui.separator();

        ImGui.setCursorPosY(titleBarHeight + 2);
    }

    /**
     * Renders the sidebar navigation, content area, and footer buttons.
     */
    private void renderContent() {
        float windowWidth = ImGui.getContentRegionAvailX();
        float windowHeight = ImGui.getContentRegionAvailY();
        float contentHeight = windowHeight - FOOTER_HEIGHT;

        // Left sidebar (navigation)
        ImGui.beginChild("##Sidebar", SIDEBAR_WIDTH, contentHeight, false);
        renderSidebar();
        ImGui.endChild();

        // Vertical separator
        ImGui.sameLine();
        ImGui.getWindowDrawList().addLine(
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY(),
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY() + contentHeight,
                ImGui.getColorU32(0.5f, 0.5f, 0.5f, 0.5f),
                1.0f
        );

        // Right content area (selected page)
        ImGui.sameLine();
        float contentWidth = windowWidth - SIDEBAR_WIDTH - 10;
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10.0f, 10.0f);
        ImGui.beginChild("##Content", contentWidth, contentHeight, false);

        ImGui.indent(15.0f);
        renderSelectedPage();
        ImGui.unindent(15.0f);

        ImGui.endChild();
        ImGui.popStyleVar();

        // Footer with OK and Apply buttons
        renderFooter(windowWidth);
    }

    /**
     * Renders the footer bar with Apply and OK buttons.
     */
    private void renderFooter(float windowWidth) {
        // Separator line above footer
        ImGui.separator();
        ImGui.spacing();

        float buttonWidth = 100.0f;
        float buttonHeight = 28.0f;
        float buttonSpacing = 8.0f;
        float totalButtonsWidth = buttonWidth * 2 + buttonSpacing;

        // Right-align buttons
        float startX = windowWidth - totalButtonsWidth - 15.0f;
        ImGui.setCursorPosX(startX);

        // Apply button
        if (ImGui.button("Apply", buttonWidth, buttonHeight)) {
            pageRenderer.applyAllSettings();
            logger.info("Preferences applied");
        }

        ImGui.sameLine(0, buttonSpacing);

        // OK button (Apply + close)
        if (ImGui.button("OK", buttonWidth, buttonHeight)) {
            pageRenderer.applyAllSettings();
            visible.set(false);
            logger.info("Preferences applied and window closed");
        }
    }

    /**
     * Renders the sidebar navigation with page buttons.
     */
    private void renderSidebar() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8.0f, 10.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0.0f, 2.0f);

        int selectedColor = ImGui.getColorU32(ImGuiCol.Header);

        for (PreferencesState.PreferencePage page : PreferencesState.PreferencePage.values()) {
            boolean isSelected = state.getCurrentPage() == page;

            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Button, selectedColor);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, selectedColor);
                ImGui.pushStyleColor(ImGuiCol.ButtonActive, selectedColor);
            }

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
     */
    public void setCurrentPage(PreferencesState.PreferencePage page) {
        state.setCurrentPage(page);
    }

    /**
     * Sets the TextureCreatorImGui instance for texture editor preferences.
     */
    public void setTextureCreatorImGui(TextureCreatorImGui textureCreatorImGui) {
        pageRenderer.setTextureCreatorImGui(textureCreatorImGui);
    }
}
