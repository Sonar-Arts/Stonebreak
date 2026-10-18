package com.openmason.main.systems.menus.textureCreator;

import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.api.workspace.WorkspaceLayout;
import com.openmason.main.systems.menus.textureCreator.panels.status.StatusBarPanel;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorLayoutBuilder;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorStyleScope;
import com.openmason.main.systems.services.drop.PendingFileDrops;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiFocusedFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Texture Editor as a workspace tab ({@code Scene | UI | Texture}): its menu bar and tool
 * options are the workspace toolbar, and below them the fixed Color/Tools columns, the dockspace
 * (Canvas | Layers) and the status bar fill whichever window holds the tab: the main window, or
 * the workspace's own window when popped out. Docked panels and dialogs draw afterwards in
 * {@link #renderPanels(float, boolean)}, like the UI workspace's.
 *
 * <p>Opening and closing the tab is the editor's lifecycle (it replaced the standalone
 * TextureEditorWindow). Inside it, every open texture is a document tab above the canvas
 * ({@link TextureCreatorController} documents, #331); opening textures — from the UI or an agent —
 * adds or focuses documents and opens this tab only when it is closed. Only the user closes the
 * tab: unsaved textures are asked about first ({@link TextureCreatorImGui#confirmCloseAll}, the
 * workspace's close guard), then the app closes every document when the tab goes away.
 */
public final class TextureEditorWorkspace implements WorkspaceDock {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorWorkspace.class);

    /** The canvas panel, focused when the workspace comes forward so tool shortcuts work at once. */
    private static final String CANVAS_WINDOW = "Canvas";

    private final TextureCreatorImGui textureCreator;
    private final TextureEditorLayoutBuilder layoutBuilder;
    private final TextureEditorStyleScope styleScope = new TextureEditorStyleScope();

    private boolean wasShown;

    public TextureEditorWorkspace(TextureCreatorImGui textureCreator) {
        if (textureCreator == null) {
            throw new IllegalArgumentException("TextureCreatorImGui cannot be null");
        }
        this.textureCreator = textureCreator;
        this.layoutBuilder = new TextureEditorLayoutBuilder();
        // The host provides the dockspace and the windowed (non-absolute) tool options bar is used.
        textureCreator.setWindowedMode(true);
    }

    /** View → Reset Layout in the editor's menu: the host's layout gate rebuilds on the next frame. */
    public void setResetLayoutAction(Runnable resetLayout) {
        textureCreator.setOnResetLayout(resetLayout);
    }

    public TextureCreatorImGui getTextureCreator() {
        return textureCreator;
    }

    @Override
    public void buildDefaultLayout(int dockspaceId, float width, float height) {
        layoutBuilder.build(dockspaceId, width, height);
    }

    /** The editor's menu bar and tool options, above its body. */
    @Override
    public void renderHeader() {
        styleScope.push();
        try {
            textureCreator.renderWindowedMenuBar();
            textureCreator.renderWindowedToolbar();
        } catch (Exception e) {
            logger.error("Error rendering texture editor toolbar", e);
        } finally {
            styleScope.pop();
        }
    }

    /**
     * One content row (fixed left chrome Color | splitter | Tools, then the dockspace with Canvas |
     * Layers) and the status bar pinned below it, inside the window holding the workspace.
     */
    @Override
    public void submitBody(int dockspaceId, float width, float height, WorkspaceLayout layout) {
        styleScope.push();
        try {
            // The host window keeps the app theme's background; the editor body is flat dark.
            float x = ImGui.getCursorScreenPosX();
            float y = ImGui.getCursorScreenPosY();
            ImGui.getWindowDrawList().addRectFilled(x, y, x + ImGui.getContentRegionAvailX(),
                    y + ImGui.getContentRegionAvailY(), ImGui.getColorU32(imgui.flag.ImGuiCol.WindowBg));

            textureCreator.anchorDialogsToCurrentWindow();
            processPendingFileDrops();
            // Shortcuts act while focus is in this workspace: its window, its docked panels, or the
            // fixed columns. From another workspace (a popped-out one, say) they must not paint here.
            boolean focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows
                    | ImGuiFocusedFlags.DockHierarchy);
            boolean activelyTyping = ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput();
            if (focused && !activelyTyping) {
                textureCreator.handleKeyboardShortcuts();
            }

            float statusReserve = StatusBarPanel.HEIGHT + ImGui.getStyle().getItemSpacingY();
            float rowHeight = ImGui.getContentRegionAvailY() - statusReserve;

            textureCreator.renderLeftColumns(rowHeight);
            ImGui.sameLine(0, 0);

            float dockspaceWidth = ImGui.getContentRegionAvailX();
            ImGui.dockSpace(dockspaceId, 0.0f, rowHeight, ImGuiDockNodeFlags.None);
            layout.applyIfNeeded(dockspaceId, dockspaceWidth, rowHeight);

            textureCreator.renderStatusBar();
        } catch (Exception e) {
            logger.error("Error rendering texture editor", e);
            ThemedWidgets.statusText(ThemeColors.Tone.ERROR, "Error rendering texture editor");
            ImGui.text("Check logs for details");
        } finally {
            styleScope.pop();
        }
    }

    /**
     * The docked panels (Canvas, Layers, Noise Filter, Symmetry) and the editor's dialogs; call
     * every frame after the windows holding the workspaces were submitted.
     *
     * @param shown whether the workspace is in front of the main window or in its own visible window
     */
    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (!shown) {
            wasShown = false;
            return;
        }
        styleScope.push();
        try {
            textureCreator.renderWindowedPanels();
            if (!wasShown) {
                // The tab just came forward (opened, or switched to): hand the keyboard to the canvas.
                ImGui.setWindowFocus(CANVAS_WINDOW);
            }
        } catch (Exception e) {
            logger.error("Error rendering texture editor panels", e);
        } finally {
            styleScope.pop();
        }
        wasShown = true;
    }

    /** Pending file drops from the GLFW callbacks go to the editor while it is on screen. */
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
