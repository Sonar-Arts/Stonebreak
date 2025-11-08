package com.stonebreak.ui.worldSelect;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.handlers.WorldInputHandler;
import com.stonebreak.ui.worldSelect.handlers.WorldMouseHandler;
import com.stonebreak.ui.worldSelect.handlers.WorldActionHandler;
import com.stonebreak.ui.worldSelect.renderers.WorldListRenderer;

import java.util.List;

/**
 * Simplified WorldSelectScreen implementation following SOLID principles.
 * Serves as a facade that coordinates all modular components for world selection,
 * creation, and management functionality.
 */
public class WorldSelectScreen {

    // ===== MODULAR COMPONENTS =====
    private final WorldStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;
    private final WorldActionHandler actionHandler;
    private final WorldInputHandler inputHandler;
    private final WorldMouseHandler mouseHandler;
    private final WorldListRenderer listRenderer;

    // ===== CONSTRUCTOR =====

    /**
     * Creates a new WorldSelectScreen with the specified UI renderer.
     * Initializes all modular components and their dependencies.
     */
    public WorldSelectScreen(UIRenderer uiRenderer) {
        // Initialize managers
        this.stateManager = new WorldStateManager();
        this.discoveryManager = new WorldDiscoveryManager();

        // Initialize action handler
        this.actionHandler = new WorldActionHandler(stateManager, discoveryManager);

        // Initialize input handlers
        this.inputHandler = new WorldInputHandler(stateManager, actionHandler);
        this.mouseHandler = new WorldMouseHandler(stateManager, actionHandler);

        // Initialize renderers
        this.listRenderer = new WorldListRenderer(uiRenderer, stateManager, discoveryManager);

        // Set up component callbacks
        initializeCallbacks();

        // Initial world discovery
        refreshWorlds();
    }

    /**
     * Initializes the callback connections between components.
     */
    private void initializeCallbacks() {
        actionHandler.setCallbacks(
            this::onReturnToMainMenu,
            this::onWorldLoaded,
            this::onRefreshWorlds
        );
    }

    // ===== INPUT HANDLING =====

    /**
     * Handles all keyboard input for the world select screen.
     */
    public void handleInput(long window) {
        inputHandler.handleInput(window);
    }

    /**
     * Handles character input for text fields in dialogs.
     */
    public void handleCharacterInput(char character) {
        inputHandler.handleCharacterInput(character);
    }

    /**
     * Handles key input events for special key handling.
     */
    public void handleKeyInput(int key, int action, int mods) {
        inputHandler.handleKeyInput(key, action, mods);
    }

    // ===== MOUSE HANDLING =====

    /**
     * Handles mouse movement for hover effects.
     */
    public void handleMouseMove(double x, double y, int width, int height) {
        mouseHandler.handleMouseMove(x, y, width, height);
    }

    /**
     * Handles mouse click events.
     */
    public void handleMouseClick(double x, double y, int width, int height, int button, int action) {
        mouseHandler.handleMouseClick(x, y, width, height, button, action);
    }

    /**
     * Handles mouse wheel scrolling.
     */
    public void handleMouseWheel(double yOffset) {
        mouseHandler.handleMouseWheel(yOffset);
    }

    // ===== WORLD MANAGEMENT =====

    /**
     * Refreshes the world list from the file system.
     */
    public void refreshWorlds() {
        actionHandler.refreshWorlds();
    }

    /**
     * Creates a new world with the specified name and seed.
     */
    public boolean createNewWorld(String worldName, String seedText) {
        return actionHandler.createNewWorld(worldName, seedText);
    }

    /**
     * Loads a specific world by name.
     */
    public void loadWorld(String worldName) {
        actionHandler.loadWorld(worldName);
    }

    // ===== DIALOG MANAGEMENT =====

    /**
     * Opens the create world dialog.
     */
    public void openCreateDialog() {
        actionHandler.openCreateWorldDialog();
    }

    /**
     * Closes the create world dialog.
     */
    public void closeCreateDialog() {
        actionHandler.closeCreateWorldDialog();
    }

    // ===== RENDERING =====

    /**
     * Renders the complete world select screen using modular rendering system.
     */
    public void render(int width, int height) {
        // Render main world list
        listRenderer.renderWorldList(width, height);
    }

    // ===== PUBLIC API (COMPATIBILITY) =====

    /**
     * Gets the list of available worlds.
     */
    public List<String> getWorldList() {
        return stateManager.getWorldList();
    }

    /**
     * Gets the currently selected world index.
     */
    public int getSelectedIndex() {
        return stateManager.getSelectedIndex();
    }

    /**
     * Gets the current scroll offset.
     */
    public int getScrollOffset() {
        return stateManager.getScrollOffset();
    }

    /**
     * Gets the currently hovered world index.
     */
    public int getHoveredIndex() {
        return stateManager.getHoveredIndex();
    }

    /**
     * Checks if the create world dialog is currently open.
     */
    public boolean isShowCreateDialog() {
        return stateManager.isShowCreateDialog();
    }

    /**
     * Gets the current world name input text.
     */
    public String getNewWorldName() {
        return stateManager.getNewWorldName();
    }

    /**
     * Gets the current seed input text.
     */
    public String getNewWorldSeed() {
        return stateManager.getNewWorldSeed();
    }

    // ===== CALLBACK HANDLERS =====

    /**
     * Handles returning to the main menu.
     */
    private void onReturnToMainMenu() {
        // Reset input state when leaving screen
        inputHandler.resetInputState();
        stateManager.reset();
    }

    /**
     * Handles world loading completion.
     */
    private void onWorldLoaded() {
        // Reset input state when transitioning to world
        inputHandler.resetInputState();
        stateManager.reset();
    }

    /**
     * Handles world list refresh completion.
     */
    private void onRefreshWorlds() {
        // World list has been updated - no additional action needed
        // The state manager has already been updated by the action handler
    }

    // ===== UTILITY METHODS =====

    /**
     * Gets display information for a specific world.
     */
    public String getWorldDisplayInfo(String worldName) {
        return actionHandler.getWorldDisplayInfo(worldName);
    }

    /**
     * Checks if a world with the given name exists.
     */
    public boolean worldExists(String worldName) {
        return actionHandler.worldExists(worldName);
    }

    /**
     * Resets the screen to its initial state.
     */
    public void reset() {
        stateManager.reset();
        inputHandler.resetInputState();
        refreshWorlds();
    }

    // ===== COMPONENT ACCESS (FOR TESTING/DEBUGGING) =====

    /**
     * Gets the state manager (for testing/debugging).
     */
    public WorldStateManager getStateManager() {
        return stateManager;
    }

    /**
     * Gets the discovery manager (for testing/debugging).
     */
    public WorldDiscoveryManager getDiscoveryManager() {
        return discoveryManager;
    }

    /**
     * Gets the action handler (for testing/debugging).
     */
    public WorldActionHandler getActionHandler() {
        return actionHandler;
    }
}