package com.stonebreak.ui.worldSelect;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.worldSelect.managers.WorldBackupService;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.handlers.WorldInputHandler;
import com.stonebreak.ui.worldSelect.handlers.WorldMouseHandler;
import com.stonebreak.ui.worldSelect.handlers.WorldActionHandler;
import com.stonebreak.ui.worldSelect.renderers.SkijaWorldSelectRenderer;

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
    private final WorldBackupService backupService;
    private final WorldActionHandler actionHandler;
    private final WorldInputHandler inputHandler;
    private final WorldMouseHandler mouseHandler;
    private final SkijaWorldSelectRenderer skijaRenderer;

    // ===== CONSTRUCTOR =====

    /**
     * Creates a new WorldSelectScreen backed by the Skija UI renderer.
     */
    public WorldSelectScreen(SkijaUIBackend skijaBackend) {
        this(skijaBackend, new WorldDiscoveryManager(), new WorldBackupService());
    }

    /** Test seam (#299 fidelity fixtures): worlds and backups from somewhere other than the save folder. */
    public WorldSelectScreen(SkijaUIBackend skijaBackend, WorldDiscoveryManager discoveryManager,
                             WorldBackupService backupService) {
        // Initialize managers
        this.stateManager = new WorldStateManager();
        this.discoveryManager = discoveryManager;
        this.backupService = backupService;

        // Initialize action handler
        this.actionHandler = new WorldActionHandler(stateManager, discoveryManager, backupService);

        // Initialize input handlers
        this.inputHandler = new WorldInputHandler(stateManager, actionHandler);
        this.mouseHandler = new WorldMouseHandler(stateManager, actionHandler, inputHandler);

        // Skija-backed renderer
        this.skijaRenderer = new SkijaWorldSelectRenderer(skijaBackend, stateManager, discoveryManager,
                inputHandler, backupService);

        initializeCallbacks();
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
     * Renders the complete world select screen via the Skija backend. The
     * renderer brackets its own GL state — the caller does not need to begin
     * or end a NanoVG frame around this call.
     */
    public void render(int width, int height) {
        // The hover card opens and closes on a delay, so its state machine has to advance
        // even on frames where the cursor produced no move events.
        stateManager.tickCard(System.currentTimeMillis());
        skijaRenderer.render(width, height);
    }

    /** The legacy renderer (its layout sink feeds the #299 fidelity gates). */
    public SkijaWorldSelectRenderer renderer() {
        return skijaRenderer;
    }

    // ===== DOCUMENT ACTIONS (#299) =====
    // The shipped document (ui/documents/world_select.sbui) reaches the screen through these, by
    // the same rules the legacy mouse and keyboard handlers apply.

    /** Advances the info card's open/close delays: once a frame while the document shows. */
    public void tick(long nowMs) {
        stateManager.tickCard(nowMs);
    }

    /** A click on the world at {@code index} (into the whole list): selects it. */
    public boolean selectWorld(int index) {
        if (stateManager.isAnyDialogOpen() || index < 0 || index >= stateManager.getWorldList().size()) {
            return false;
        }
        stateManager.setSelectedIndex(index);
        return true;
    }

    /**
     * Where the pointer rests: the world row at {@code index} (-1 for none) or, with
     * {@code overCard}, the open info card, which keeps it open and lights its row.
     */
    public void hover(int index, boolean overCard) {
        long now = System.currentTimeMillis();
        if (stateManager.isAnyDialogOpen()) {
            stateManager.setHoveredIndex(-1);
            return;
        }
        if (overCard && stateManager.isCardOpen()) {
            stateManager.setHoveredIndex(stateManager.getCardIndex());
            stateManager.updateCardHover(-1, true, now);
            return;
        }
        stateManager.setHoveredIndex(index);
        stateManager.updateCardHover(stateManager.getHoveredIndex(), false, now);
    }

    /** A mouse wheel tick ({@code yOffset} as GLFW reports it): one row per tick. */
    public void wheel(double yOffset) {
        mouseHandler.handleMouseWheel(yOffset);
    }

    /** Up/W ({@code -1}) and Down/S ({@code +1}). */
    public void moveSelection(int delta) {
        if (stateManager.isAnyDialogOpen()) return;
        if (delta < 0) stateManager.moveSelectionUp();
        else if (delta > 0) stateManager.moveSelectionDown();
    }

    /** Enter/Space: plays the selection, or goes to world creation when there are no worlds. */
    public void activate() {
        if (stateManager.isAnyDialogOpen()) return;
        if (stateManager.hasWorlds()) {
            actionHandler.loadSelectedWorld();
        } else {
            actionHandler.openCreateWorldDialog();
        }
    }

    public boolean hasSelection() {
        return stateManager.getSelectedWorld() != null;
    }

    public boolean playSelected() {
        if (stateManager.isAnyDialogOpen() || !hasSelection()) return false;
        actionHandler.loadSelectedWorld();
        return true;
    }

    /** Create World (and N): on to character creation, then the terrain mapper. */
    public void createWorld() {
        actionHandler.openCreateWorldDialog();
    }

    public boolean requestDelete() {
        if (stateManager.isAnyDialogOpen() || !hasSelection()) return false;
        actionHandler.requestDeleteSelectedWorld();
        return true;
    }

    public boolean confirmDelete() {
        if (!stateManager.isShowDeleteDialog()) return false;
        actionHandler.confirmDeleteWorld();
        return true;
    }

    public boolean cancelDelete() {
        if (!stateManager.isShowDeleteDialog()) return false;
        actionHandler.cancelDeleteWorld();
        return true;
    }

    public void back() {
        actionHandler.returnToMainMenu();
    }

    /** The info card's Open Folder. */
    public boolean openCardFolder() {
        String world = stateManager.getCardWorld();
        if (world == null) return false;
        actionHandler.openWorldFolder(world);
        return true;
    }

    /** The info card's Back Up (refused while that world's backup runs). */
    public boolean backupCardWorld() {
        String world = stateManager.getCardWorld();
        if (world == null || backupService.isRunning(world)) return false;
        actionHandler.backupWorld(world);
        return true;
    }

    public WorldBackupService getBackupService() {
        return backupService;
    }

    public void dispose() {
        if (skijaRenderer != null) skijaRenderer.dispose();
        if (discoveryManager != null) discoveryManager.dispose();
        if (backupService != null) backupService.shutdown();
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
     * Opens the folder of the given world (or the worlds folder itself when null)
     * in the OS file manager.
     */
    public void openWorldFolder(String worldName) {
        actionHandler.openWorldFolder(worldName);
    }

    /**
     * Starts a background backup of the named world into the backups folder.
     */
    public void backupWorld(String worldName) {
        actionHandler.backupWorld(worldName);
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