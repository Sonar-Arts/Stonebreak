package com.openmason.ui.windows;

import com.openmason.ui.components.textureCreator.TextureCreatorImGui;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
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

        // Configure window flags for a truly separate window
        // NoDocking prevents this window from being docked into the main interface
        // Note: No MenuBar flag - we render menu bar manually via child window
        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus |
                          ImGuiWindowFlags.NoDocking;

        // Remove window padding to eliminate space above menu bar
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        // Begin standalone window (MenuBar flag allows menu bar inside window)
        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                // Render menu bar and toolbar at the top (BEFORE dockspace)
                textureCreator.renderWindowedMenuBar();
                textureCreator.renderWindowedToolbar();

                // Create dockspace below menu/toolbar for panels
                int dockspaceId = ImGui.getID(DOCKSPACE_NAME);
                ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);

                // Render panels, dialogs, etc.
                textureCreator.renderWindowedPanels();

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
