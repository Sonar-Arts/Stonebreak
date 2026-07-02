package com.openmason.main.systems.menus.windows;

import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorLayoutBuilder;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorStyleScope;
import com.openmason.main.systems.services.drop.PendingFileDrops;
import imgui.ImGui;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiViewportFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
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
    private final TextureEditorLayoutBuilder layoutBuilder;
    private final TextureEditorStyleScope styleScope = new TextureEditorStyleScope();

    // Window class that promotes the popped-out editor to a real, WM-decorated OS
    // window: clearing NoDecoration lets the window manager (e.g. KWin) draw the
    // title bar and own drag/resize/minimize/maximize/close — no app-driven
    // setWindowPos, so no XWayland async-move "earthquake" shake. NoAutoMerge keeps
    // it a standalone window instead of merging back into the main window.
    private final ImGuiWindowClass windowClass = new ImGuiWindowClass();

    private boolean iniFileSet = false;
    private boolean wasVisible = false;

    /**
     * Create a new TextureEditorWindow.
     */
    public TextureEditorWindow(TextureCreatorImGui textureCreator) {
        if (textureCreator == null) {
            throw new IllegalArgumentException("TextureCreatorImGui cannot be null");
        }

        this.textureCreator = textureCreator;
        this.visible = new ImBoolean(false);
        this.layoutBuilder = new TextureEditorLayoutBuilder(PreferencesManager.getInstance());

        // Decorated, standalone OS window when popped out (see field comment).
        windowClass.setViewportFlagsOverrideClear(
                ImGuiViewportFlags.NoDecoration | ImGuiViewportFlags.NoTaskBarIcon);
        windowClass.setViewportFlagsOverrideSet(ImGuiViewportFlags.NoAutoMerge);

        textureCreator.setWindowedMode(true);
        textureCreator.setOnResetLayout(layoutBuilder::requestReset);
        logger.debug("TextureEditorWindow created with windowed mode enabled");
    }

    /**
     * Render the texture editor window.
     */
    public void render() {
        if (!visible.get()) {
            wasVisible = false;
            return;
        }

        if (!wasVisible) {
            ImGui.setNextWindowFocus();
            wasVisible = true;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(1200, 800);
            imgui.ImGuiViewport vp = ImGui.getMainViewport();
            ImGui.setNextWindowPos(
                    vp.getPosX() + vp.getSizeX() / 2f - 600f,
                    vp.getPosY() + vp.getSizeY() / 2f - 400f
            );
            iniFileSet = true;
        }

        // Minimum size ensures all UI elements remain visible: the fixed left
        // chrome (Color 320 + Tools 48) plus a workable canvas and Layers column
        ImGui.setNextWindowSizeConstraints(1100, 700, Float.MAX_VALUE, Float.MAX_VALUE);
        // Promote the popped-out window to a WM-decorated, standalone OS window so
        // the window manager owns drag/resize/min/max/close (fixes the XWayland shake).
        ImGui.setNextWindowClass(windowClass);
        // NoScrollWithMouse matters: if content ever overflows by a pixel,
        // a scrollable host window lets the wheel nudge the entire editor
        int windowFlags = ImGuiWindowFlags.NoDocking |
                          ImGuiWindowFlags.NoTitleBar |
                          ImGuiWindowFlags.NoCollapse |
                          ImGuiWindowFlags.NoScrollbar |
                          ImGuiWindowFlags.NoScrollWithMouse;

        styleScope.push();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            processPendingFileDrops();

            try {
                // Handle shortcuts unless actively typing
                boolean activelyTyping = ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput();
                if (!activelyTyping) {
                    textureCreator.handleKeyboardShortcuts();
                }

                // No custom title bar: when popped out, the window is WM-decorated
                // (see windowClass) so the compositor draws the title bar and its
                // drag/minimize/maximize/close controls. The WM close button flips
                // the `visible` ImBoolean via ImGui's platform close callback.
                textureCreator.renderWindowedMenuBar();
                textureCreator.renderWindowedToolbar();

                // One content row: fixed left chrome (Color | splitter | Tools)
                // then the dockspace (Canvas | Layers) filling the rest. Height
                // excludes the status bar strip pinned at the window bottom.
                float statusReserve =
                        com.openmason.main.systems.menus.textureCreator.panels.status.StatusBarPanel.HEIGHT
                        + ImGui.getStyle().getItemSpacingY();
                float rowHeight = ImGui.getContentRegionAvailY() - statusReserve;

                textureCreator.renderLeftColumns(rowHeight);
                ImGui.sameLine(0, 0);

                float dockspaceWidth = ImGui.getContentRegionAvailX();
                int dockspaceId = ImGui.getID(DOCKSPACE_NAME);
                ImGui.dockSpace(dockspaceId, 0.0f, rowHeight, ImGuiDockNodeFlags.None);

                layoutBuilder.applyIfNeeded(dockspaceId, dockspaceWidth, rowHeight);

                textureCreator.renderWindowedPanels();
                textureCreator.renderStatusBar();

            } catch (Exception e) {
                logger.error("Error rendering texture creator", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering texture editor");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
        styleScope.pop();
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
