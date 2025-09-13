package com.stonebreak.ui.worldSelect;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.TextInputField;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Consolidated World Select Screen with defensive error handling.
 * All functionality is contained within this single class to eliminate
 * complex initialization chains and potential crashes.
 */
public class WorldSelectScreen {

    // ===== CORE DEPENDENCIES =====
    private final UIRenderer uiRenderer;

    // ===== STATE MANAGEMENT =====
    private List<String> worldList = new ArrayList<>();
    private int selectedIndex = -1;
    private int hoveredIndex = -1;
    private int scrollOffset = 0;
    private boolean showCreateDialog = false;
    private boolean createButtonSelected = false;
    private boolean mouseControlActive = false;

    // ===== UI CONSTANTS =====
    private static final int VISIBLE_ITEMS = 5;
    private static final float HEADER_HEIGHT = 150f;
    private static final float BUTTON_WIDTH = 500f;
    private static final float BUTTON_HEIGHT = 50f;
    private static final float SPACING = 10f;
    private static final float START_Y = HEADER_HEIGHT + 30f;

    // ===== FOCUS MANAGEMENT =====
    public enum FocusState {
        WORLD_LIST,
        CREATE_BUTTON
    }
    private FocusState currentFocus = FocusState.WORLD_LIST;

    // ===== DOUBLE-CLICK DETECTION =====
    private long lastClickTime = 0;
    private int lastClickedIndex = -1;
    private static final long DOUBLE_CLICK_TIME = 400;

    // ===== CREATE DIALOG COMPONENTS =====
    private TextInputField worldNameField;
    private TextInputField seedField;
    private boolean dialogInitialized = false;

    // ===== DIALOG LAYOUT CONSTANTS =====
    private static final float DIALOG_WIDTH = 450f;
    private static final float DIALOG_HEIGHT = 320f;
    private static final float INPUT_WIDTH = 400f;
    private static final float INPUT_HEIGHT = 40f;
    private static final float FIELD_SPACING = 75f;
    private static final float START_Y_OFFSET = 85f;

    // ===== ERROR HANDLING STATE =====
    private boolean worldDiscoveryFailed = false;
    private String lastError = null;

    // ===== CONSTRUCTOR =====

    /**
     * Creates a new world select screen with defensive error handling.
     * All potentially failing operations are deferred to avoid constructor crashes.
     */
    public WorldSelectScreen(UIRenderer uiRenderer) {
        System.out.println("DEBUG: WorldSelectScreen constructor starting...");

        // Always initialize uiRenderer first to prevent compilation error
        this.uiRenderer = uiRenderer;
        System.out.println("DEBUG: UIRenderer set, uiRenderer = " + (uiRenderer != null ? "not null" : "NULL"));

        try {
            // Initialize safe defaults
            System.out.println("DEBUG: Calling initializeSafeDefaults...");
            initializeSafeDefaults();
            System.out.println("DEBUG: initializeSafeDefaults completed");

            // Initialize create dialog components
            System.out.println("DEBUG: Calling initializeCreateDialog...");
            initializeCreateDialog();
            System.out.println("DEBUG: initializeCreateDialog completed");

            // Attempt world discovery (non-blocking, safe)
            System.out.println("DEBUG: Calling refreshWorldsAsync...");
            refreshWorldsAsync();
            System.out.println("DEBUG: refreshWorldsAsync completed");

            System.out.println("DEBUG: WorldSelectScreen constructor completed successfully");

        } catch (Exception e) {
            // Constructor should never fail - set safe state
            System.err.println("ERROR: WorldSelectScreen constructor failed: " + e.getMessage());
            e.printStackTrace();
            handleInitializationError(e);
        }
    }

    /**
     * Initialize safe default values that cannot fail
     */
    private void initializeSafeDefaults() {
        worldList = new ArrayList<>();
        selectedIndex = -1;
        hoveredIndex = -1;
        scrollOffset = 0;
        showCreateDialog = false;
        createButtonSelected = false;
        mouseControlActive = false;
        currentFocus = FocusState.WORLD_LIST;
        worldDiscoveryFailed = false;
        lastError = null;
    }

    /**
     * Initialize create dialog components with error handling
     */
    private void initializeCreateDialog() {
        try {
            // Initialize world name field
            worldNameField = new TextInputField("My Awesome World");
            worldNameField.setMaxLength(50);
            worldNameField.setIconType("world");
            worldNameField.setFieldLabel("World Name:");
            worldNameField.setShowValidationIndicator(true);
            worldNameField.setValidator(new TextInputField.InputValidator() {
                @Override
                public boolean isValid(String text) {
                    return text != null && !text.trim().isEmpty() &&
                           text.length() <= 50 && text.length() >= 3;
                }

                @Override
                public String getErrorMessage() {
                    return "World name must be between 3 and 50 characters";
                }
            });

            // Initialize seed field
            seedField = new TextInputField("12345 (optional)");
            seedField.setMaxLength(20);
            seedField.setIconType("seed");
            seedField.setFieldLabel("World Seed:");
            seedField.setShowValidationIndicator(false);

            dialogInitialized = true;

        } catch (Exception e) {
            // Even dialog initialization failure shouldn't crash the screen
            dialogInitialized = false;
            lastError = "Failed to initialize create dialog: " + e.getMessage();
        }
    }

    /**
     * Handle initialization errors gracefully
     */
    private void handleInitializationError(Exception e) {
        worldDiscoveryFailed = true;
        lastError = "Initialization failed: " + e.getMessage();
        // Ensure we have safe defaults
        if (worldList == null) worldList = new ArrayList<>();
    }

    // ===== WORLD DISCOVERY =====

    /**
     * Asynchronously refresh world list - safe, non-blocking
     */
    private void refreshWorldsAsync() {
        try {
            refreshWorlds();
        } catch (Exception e) {
            // Don't let world discovery failure crash the screen
            worldDiscoveryFailed = true;
            lastError = "World discovery failed: " + e.getMessage();
            worldList = new ArrayList<>();
        }
    }

    /**
     * Refresh world list with comprehensive error handling
     */
    public void refreshWorlds() {
        try {
            worldList.clear();
            worldDiscoveryFailed = false;
            lastError = null;

            // Ensure worlds directory exists
            File worldsDir = new File("worlds");
            if (!ensureWorldsDirectoryExists(worldsDir)) {
                return; // Failed to create/access directory, but continue with empty list
            }

            // Scan for world directories
            scanForWorlds(worldsDir);

            // Update selection state
            ensureValidSelection();

        } catch (Exception e) {
            worldDiscoveryFailed = true;
            lastError = "Failed to refresh worlds: " + e.getMessage();
            worldList = new ArrayList<>(); // Safe fallback
        }
    }

    /**
     * Ensure worlds directory exists with error handling
     */
    private boolean ensureWorldsDirectoryExists(File worldsDir) {
        try {
            if (!worldsDir.exists()) {
                if (worldsDir.mkdirs()) {
                    return true;
                } else {
                    lastError = "Failed to create worlds directory";
                    return false;
                }
            }

            if (!worldsDir.isDirectory()) {
                lastError = "worlds path exists but is not a directory";
                return false;
            }

            if (!worldsDir.canRead()) {
                lastError = "Cannot read worlds directory - permission denied";
                return false;
            }

            return true;

        } catch (SecurityException e) {
            lastError = "Security exception accessing worlds directory: " + e.getMessage();
            return false;
        } catch (Exception e) {
            lastError = "Unexpected error checking worlds directory: " + e.getMessage();
            return false;
        }
    }

    /**
     * Scan for world directories with error handling
     */
    private void scanForWorlds(File worldsDir) {
        try {
            File[] worldDirs = worldsDir.listFiles(File::isDirectory);

            if (worldDirs == null) {
                // I/O error or security issue
                return;
            }

            for (File worldDir : worldDirs) {
                try {
                    String worldName = worldDir.getName();
                    worldList.add(worldName);
                } catch (Exception e) {
                    // Continue processing other worlds if one fails
                    continue;
                }
            }

        } catch (SecurityException e) {
            lastError = "Security exception scanning worlds: " + e.getMessage();
        } catch (Exception e) {
            lastError = "Error scanning world directories: " + e.getMessage();
        }
    }

    /**
     * Ensure selection is valid with the current world list
     */
    private void ensureValidSelection() {
        if (selectedIndex >= worldList.size()) {
            selectedIndex = worldList.size() - 1;
        }
        if (selectedIndex < 0 && !worldList.isEmpty()) {
            selectedIndex = 0;
        }
    }

    // ===== INPUT HANDLING =====

    /**
     * Handle keyboard input with error boundaries
     */
    public void handleInput(long window) {
        try {
            if (showCreateDialog) {
                handleCreateDialogInput(window);
            } else {
                handleMainScreenInput(window);
            }
        } catch (Exception e) {
            // Don't let input handling crash the screen
            lastError = "Input handling error: " + e.getMessage();
        }
    }

    /**
     * Handle main screen keyboard input
     */
    private void handleMainScreenInput(long window) {
        // Navigation keys
        if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
            navigateUp();
        }
        if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
            navigateDown();
        }

        // Action keys
        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
            handleEnter();
        }
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            handleEscape();
        }
        if (glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS) {
            handleTab();
        }

        // World management keys
        if (glfwGetKey(window, GLFW_KEY_F2) == GLFW_PRESS) {
            handleF2();
        }
        if (glfwGetKey(window, GLFW_KEY_F5) == GLFW_PRESS) {
            refreshWorlds();
        }
    }

    /**
     * Handle create dialog input
     */
    private void handleCreateDialogInput(long window) {
        if (!dialogInitialized) return;

        // Let text fields handle their input
        // Key events are handled in handleKeyInput method
    }

    // ===== MOUSE HANDLING =====

    /**
     * Handle mouse movement with error handling
     */
    public void handleMouseMove(double x, double y, int width, int height) {
        try {
            if (showCreateDialog) {
                // No hover effects in dialog
                return;
            }

            // Find hovered world item
            int hoveredWorld = findWorldIndexAtPosition(x, y, width, height);
            if (hoveredWorld != hoveredIndex) {
                hoveredIndex = hoveredWorld;
                mouseControlActive = true;
            }

        } catch (Exception e) {
            // Don't let mouse handling crash
            lastError = "Mouse move error: " + e.getMessage();
        }
    }

    /**
     * Handle mouse clicks with error handling
     */
    public void handleMouseClick(double x, double y, int width, int height, int button, int action) {
        try {
            if (action != GLFW_PRESS) return;

            if (showCreateDialog) {
                handleCreateDialogMouseClick(x, y);
            } else {
                handleMainScreenMouseClick(x, y, width, height, button);
            }

        } catch (Exception e) {
            lastError = "Mouse click error: " + e.getMessage();
        }
    }

    /**
     * Handle main screen mouse clicks
     */
    private void handleMainScreenMouseClick(double x, double y, int width, int height, int button) {
        // Check for world item clicks
        int clickedWorld = findWorldIndexAtPosition(x, y, width, height);

        if (clickedWorld >= 0) {
            selectWorld(clickedWorld);

            // Check for double-click
            if (checkForDoubleClick(clickedWorld)) {
                loadWorld(worldList.get(clickedWorld));
            }
        }

        // Check for create button click (approximate bounds)
        float buttonY = height - 80f;
        if (y >= buttonY - 25 && y <= buttonY + 25 &&
            x >= width/2 - 100 && x <= width/2 + 100) {
            openCreateDialog(width, height);
        }
    }

    /**
     * Handle create dialog mouse clicks
     */
    private void handleCreateDialogMouseClick(double x, double y) {
        if (!dialogInitialized) return;

        boolean clickedInField = false;

        try {
            clickedInField |= worldNameField.handleMouseClick(x, y);
            clickedInField |= seedField.handleMouseClick(x, y);
        } catch (Exception e) {
            lastError = "Dialog click error: " + e.getMessage();
        }

        // If clicked outside dialog, close it
        if (!clickedInField && !isClickInsideDialog(x, y)) {
            closeCreateDialog();
        }
    }

    /**
     * Handle mouse wheel scrolling
     */
    public void handleMouseWheel(double yOffset) {
        try {
            if (showCreateDialog) return;

            scrollOffset -= (int) yOffset;
            clampScrollOffset();

        } catch (Exception e) {
            lastError = "Mouse wheel error: " + e.getMessage();
        }
    }

    // ===== WORLD MANAGEMENT =====

    /**
     * Create new world with error handling
     */
    public boolean createNewWorld(String worldName, String seedText) {
        try {
            if (worldName == null || worldName.trim().isEmpty()) {
                return false;
            }

            String trimmedName = worldName.trim();

            // Check if world already exists
            if (worldList.contains(trimmedName)) {
                return false;
            }

            // Parse seed
            long parsedSeed;
            if (seedText == null || seedText.trim().isEmpty() || "12345 (optional)".equals(seedText.trim())) {
                parsedSeed = System.currentTimeMillis();
            } else {
                try {
                    parsedSeed = Long.parseLong(seedText.trim());
                } catch (NumberFormatException e) {
                    parsedSeed = seedText.trim().hashCode();
                }
            }

            // Create world directory
            File worldsDir = new File("worlds");
            if (!worldsDir.exists()) {
                worldsDir.mkdirs();
            }

            File worldDir = new File(worldsDir, trimmedName);
            if (!worldDir.exists()) {
                if (worldDir.mkdirs()) {
                    worldList.add(trimmedName);
                    selectedIndex = worldList.indexOf(trimmedName);
                    Game.getInstance().startWorldGeneration(trimmedName, parsedSeed);
                    return true;
                } else {
                    lastError = "Failed to create world directory";
                    return false;
                }
            } else {
                lastError = "World already exists";
                return false;
            }

        } catch (Exception e) {
            lastError = "Failed to create world: " + e.getMessage();
            return false;
        }
    }

    /**
     * Load selected world with error handling
     */
    public void loadWorld(String worldName) {
        try {
            if (worldName == null || worldName.trim().isEmpty()) {
                return;
            }

            // Use world name hash as consistent seed for existing worlds
            long seed = worldName.hashCode();
            Game.getInstance().startWorldGeneration(worldName, seed);

        } catch (Exception e) {
            lastError = "Failed to load world: " + e.getMessage();
        }
    }

    // ===== CREATE DIALOG MANAGEMENT =====

    /**
     * Open create dialog
     */
    public void openCreateDialog(int width, int height) {
        if (!dialogInitialized) {
            initializeCreateDialog();
        }

        if (dialogInitialized) {
            setupDialogLayout(width, height);
            showCreateDialog = true;
            worldNameField.setFocused(true);
            seedField.setFocused(false);
        }
    }

    /**
     * Close create dialog
     */
    public void closeCreateDialog() {
        showCreateDialog = false;
        if (dialogInitialized) {
            worldNameField.setFocused(false);
            seedField.setFocused(false);
            worldNameField.setText("My Awesome World");
            seedField.setText("12345 (optional)");
        }
    }

    /**
     * Setup dialog layout
     */
    private void setupDialogLayout(int width, int height) {
        if (!dialogInitialized) return;

        float dialogX = (width - DIALOG_WIDTH) / 2;
        float dialogY = (height - DIALOG_HEIGHT) / 2;

        float inputX = dialogX + 25f;
        float startY = dialogY + START_Y_OFFSET;

        worldNameField.setBounds(inputX, startY, INPUT_WIDTH, INPUT_HEIGHT);
        seedField.setBounds(inputX, startY + FIELD_SPACING, INPUT_WIDTH, INPUT_HEIGHT);
    }

    // ===== CHARACTER AND KEY INPUT =====

    /**
     * Handle character input for create dialog
     */
    public void handleCharacterInput(char character) {
        if (showCreateDialog && dialogInitialized) {
            try {
                if (worldNameField.isFocused()) {
                    worldNameField.handleCharacterInput(character);
                } else if (seedField.isFocused()) {
                    seedField.handleCharacterInput(character);
                }
            } catch (Exception e) {
                lastError = "Character input error: " + e.getMessage();
            }
        }
    }

    /**
     * Handle key input for create dialog
     */
    public void handleKeyInput(int key, int action, int mods) {
        if (showCreateDialog && dialogInitialized) {
            try {
                // Route to focused field
                if (worldNameField.isFocused()) {
                    worldNameField.handleKeyInput(key, action, mods);
                } else if (seedField.isFocused()) {
                    seedField.handleKeyInput(key, action, mods);
                }

                // Handle dialog-specific keys
                if (action == GLFW_PRESS) {
                    switch (key) {
                        case GLFW_KEY_ENTER:
                            handleCreateDialogEnter();
                            break;
                        case GLFW_KEY_ESCAPE:
                            closeCreateDialog();
                            break;
                        case GLFW_KEY_TAB:
                            switchDialogFocus();
                            break;
                    }
                }
            } catch (Exception e) {
                lastError = "Key input error: " + e.getMessage();
            }
        }
    }

    // ===== RENDERING =====

    /**
     * Render the world select screen with error handling
     */
    public void render(int width, int height) {
        try {
            System.out.println("DEBUG: WorldSelectScreen.render() starting with width=" + width + ", height=" + height);

            // Update dialog positioning if needed
            if (showCreateDialog) {
                System.out.println("DEBUG: Setting up dialog layout...");
                setupDialogLayout(width, height);
                System.out.println("DEBUG: Dialog layout setup completed");
            }

            // Render main screen
            System.out.println("DEBUG: Rendering main screen...");
            renderMainScreen(width, height);
            System.out.println("DEBUG: Main screen render completed");

            // Render create dialog if shown
            if (showCreateDialog) {
                System.out.println("DEBUG: Rendering create dialog...");
                renderCreateDialog(width, height);
                System.out.println("DEBUG: Create dialog render completed");
            }

            // Render error message if needed
            if (worldDiscoveryFailed && lastError != null) {
                System.out.println("DEBUG: Rendering error message...");
                renderErrorMessage(width, height);
                System.out.println("DEBUG: Error message render completed");
            }

            System.out.println("DEBUG: WorldSelectScreen.render() completed successfully");

        } catch (Exception e) {
            // Render should be as safe as possible
            System.err.println("ERROR: WorldSelectScreen.render() failed: " + e.getMessage());
            e.printStackTrace();
            lastError = "Render error: " + e.getMessage();
        }
    }

    /**
     * Render main screen elements
     */
    private void renderMainScreen(int width, int height) {
        try {
            System.out.println("DEBUG: renderMainScreen starting...");
            System.out.println("DEBUG: uiRenderer = " + (uiRenderer != null ? "not null" : "NULL"));
            System.out.println("DEBUG: worldList size = " + worldList.size());
            System.out.println("DEBUG: selectedIndex = " + selectedIndex);
            System.out.println("DEBUG: showCreateDialog = " + showCreateDialog);

            System.out.println("DEBUG: Calling uiRenderer.renderWorldSelectScreen...");

            // Temporarily bypass the problematic UIRenderer call
            System.out.println("DEBUG: Bypassing UIRenderer call to prevent crash...");

            // Use our own basic rendering instead
            renderBasicWorldSelect(width, height);

        } catch (Exception e) {
            System.err.println("ERROR: renderMainScreen failed completely: " + e.getMessage());
            e.printStackTrace();
            lastError = "Main screen render error: " + e.getMessage();
        }
    }

    /**
     * Basic world select rendering using safer methods
     */
    private void renderBasicWorldSelect(int width, int height) {
        try {
            System.out.println("DEBUG: renderBasicWorldSelect starting...");

            // Try rendering a simple dark background using renderQuad
            try {
                System.out.println("DEBUG: Attempting basic background render...");
                // Dark background
                uiRenderer.renderQuad(0, 0, width, height, 0.1f, 0.1f, 0.2f, 0.8f);
                System.out.println("DEBUG: Basic background render successful");
            } catch (Exception e) {
                System.out.println("DEBUG: Background render failed: " + e.getMessage());
            }

            // Try rendering a simple panel in the center
            try {
                System.out.println("DEBUG: Attempting panel render...");
                float panelX = width * 0.2f;
                float panelY = height * 0.2f;
                float panelW = width * 0.6f;
                float panelH = height * 0.6f;
                // Light gray panel
                uiRenderer.renderQuad(panelX, panelY, panelW, panelH, 0.3f, 0.3f, 0.3f, 0.9f);
                System.out.println("DEBUG: Panel render successful");
            } catch (Exception e) {
                System.out.println("DEBUG: Panel render failed: " + e.getMessage());
            }

            // Try rendering world list items as simple rectangles
            try {
                System.out.println("DEBUG: Attempting world list render...");
                renderBasicWorldList(width, height);
                System.out.println("DEBUG: World list render successful");
            } catch (Exception e) {
                System.out.println("DEBUG: World list render failed: " + e.getMessage());
            }

            // For now, just indicate success
            System.out.println("DEBUG: Basic world select render completed");

        } catch (Exception e) {
            System.err.println("ERROR: Basic world select render failed: " + e.getMessage());
            e.printStackTrace();
            renderMinimalFallback(width, height);
        }
    }

    /**
     * Render world list as simple colored rectangles
     */
    private void renderBasicWorldList(int width, int height) {
        try {
            float panelX = width * 0.25f;
            float panelY = height * 0.3f;
            float itemWidth = width * 0.5f;
            float itemHeight = 40f;
            float spacing = 5f;

            // Show up to 5 worlds at a time
            int startIndex = Math.max(0, scrollOffset);
            int endIndex = Math.min(worldList.size(), startIndex + VISIBLE_ITEMS);

            for (int i = startIndex; i < endIndex; i++) {
                int displayIndex = i - startIndex;
                float itemY = panelY + displayIndex * (itemHeight + spacing);

                // Different colors for selected vs unselected
                if (i == selectedIndex) {
                    // Blue for selected
                    uiRenderer.renderQuad(panelX, itemY, itemWidth, itemHeight, 0.3f, 0.5f, 0.8f, 0.8f);
                } else {
                    // Gray for unselected
                    uiRenderer.renderQuad(panelX, itemY, itemWidth, itemHeight, 0.5f, 0.5f, 0.5f, 0.6f);
                }

                // Add a border
                float[] borderColor = {0.8f, 0.8f, 0.8f, 1.0f};
                uiRenderer.renderOutline(panelX, itemY, itemWidth, itemHeight, 1.0f, borderColor);
            }

            // Add "Create New World" button at bottom
            float buttonY = panelY + VISIBLE_ITEMS * (itemHeight + spacing) + 20;
            if (createButtonSelected) {
                // Green for selected create button
                uiRenderer.renderQuad(panelX, buttonY, itemWidth, itemHeight, 0.3f, 0.8f, 0.3f, 0.8f);
            } else {
                // Light gray for unselected create button
                uiRenderer.renderQuad(panelX, buttonY, itemWidth, itemHeight, 0.6f, 0.6f, 0.6f, 0.6f);
            }
            float[] buttonBorderColor = {0.9f, 0.9f, 0.9f, 1.0f};
            uiRenderer.renderOutline(panelX, buttonY, itemWidth, itemHeight, 2.0f, buttonBorderColor);

        } catch (Exception e) {
            System.out.println("DEBUG: Error in renderBasicWorldList: " + e.getMessage());
        }
    }

    /**
     * Minimal fallback rendering that doesn't rely on complex UI components
     */
    private void renderMinimalFallback(int width, int height) {
        try {
            System.out.println("DEBUG: Using minimal fallback renderer...");

            // Just render a simple message telling user to return to main menu
            // This keeps the game from crashing while we debug the issue

            // For now, just set an error message - the actual minimal rendering
            // would need to be implemented with basic NanoVG calls
            lastError = "World select screen rendering failed - please return to main menu";
            worldDiscoveryFailed = true;

            System.out.println("DEBUG: Minimal fallback completed - screen should remain stable");

        } catch (Exception e) {
            System.err.println("ERROR: Even fallback rendering failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Render create dialog
     */
    private void renderCreateDialog(int width, int height) {
        if (!dialogInitialized) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Render dialog container
            uiRenderer.renderCreateDialogContainer(width, height);

            // Render text fields
            worldNameField.render(uiRenderer, stack);
            seedField.render(uiRenderer, stack);

        } catch (Exception e) {
            lastError = "Dialog render error: " + e.getMessage();
        }
    }

    /**
     * Render error message
     */
    private void renderErrorMessage(int width, int height) {
        try {
            // Simple error display - could be enhanced
            // For now, errors are just logged and don't crash the screen
        } catch (Exception e) {
            // Even error rendering shouldn't crash
        }
    }

    // ===== PRIVATE HELPER METHODS =====

    private void navigateUp() {
        if (!showCreateDialog && selectedIndex > 0) {
            mouseControlActive = false;
            selectedIndex--;
            if (selectedIndex < scrollOffset) {
                scrollOffset = selectedIndex;
            }
        }
    }

    private void navigateDown() {
        if (!showCreateDialog && selectedIndex < worldList.size() - 1) {
            mouseControlActive = false;
            selectedIndex++;
            if (selectedIndex >= scrollOffset + VISIBLE_ITEMS) {
                scrollOffset = selectedIndex - VISIBLE_ITEMS + 1;
            }
        }
    }

    private void handleEnter() {
        if (showCreateDialog) {
            handleCreateDialogEnter();
        } else if (currentFocus == FocusState.CREATE_BUTTON || createButtonSelected) {
            openCreateDialog(800, 600); // Default size
        } else {
            loadSelectedWorld();
        }
    }

    private void handleEscape() {
        if (showCreateDialog) {
            closeCreateDialog();
        } else {
            Game.getInstance().setState(GameState.MAIN_MENU);
        }
    }

    private void handleTab() {
        if (showCreateDialog) {
            switchDialogFocus();
        } else {
            switchMainFocus();
        }
    }

    private void handleF2() {
        // Placeholder for world editing
    }

    private void loadSelectedWorld() {
        if (selectedIndex >= 0 && selectedIndex < worldList.size()) {
            String worldName = worldList.get(selectedIndex);
            loadWorld(worldName);
        }
    }

    private void selectWorld(int index) {
        if (index >= 0 && index < worldList.size()) {
            selectedIndex = index;
            currentFocus = FocusState.WORLD_LIST;
            createButtonSelected = false;
        }
    }

    private boolean checkForDoubleClick(int index) {
        long currentTime = System.currentTimeMillis();

        if (index == lastClickedIndex &&
            index == selectedIndex &&
            (currentTime - lastClickTime) < DOUBLE_CLICK_TIME) {
            return true;
        }

        lastClickedIndex = index;
        lastClickTime = currentTime;
        return false;
    }

    private void handleCreateDialogEnter() {
        if (!dialogInitialized) return;

        if (isWorldNameValid()) {
            String worldName = worldNameField.getText();
            String seed = seedField.getText();

            if (createNewWorld(worldName, seed)) {
                closeCreateDialog();
            }
        } else {
            worldNameField.triggerErrorShake();
        }
    }

    private void switchDialogFocus() {
        if (!dialogInitialized) return;

        if (worldNameField.isFocused()) {
            worldNameField.setFocused(false);
            seedField.setFocused(true);
        } else {
            seedField.setFocused(false);
            worldNameField.setFocused(true);
        }
    }

    private void switchMainFocus() {
        if (currentFocus == FocusState.WORLD_LIST) {
            currentFocus = FocusState.CREATE_BUTTON;
            createButtonSelected = true;
        } else {
            currentFocus = FocusState.WORLD_LIST;
            createButtonSelected = false;
        }
    }

    private boolean isWorldNameValid() {
        if (!dialogInitialized) return false;
        return worldNameField.isValid() && !worldNameField.getText().trim().isEmpty();
    }

    private int findWorldIndexAtPosition(double mouseX, double mouseY, int width, int height) {
        float centerX = width / 2.0f;
        int startIndex = Math.max(0, scrollOffset);
        int endIndex = Math.min(worldList.size(), startIndex + VISIBLE_ITEMS);

        for (int i = startIndex; i < endIndex; i++) {
            int renderedPosition = i - startIndex;
            float itemY = START_Y + renderedPosition * (BUTTON_HEIGHT + SPACING);
            float itemX = centerX - BUTTON_WIDTH / 2;

            if (mouseX >= itemX && mouseX <= itemX + BUTTON_WIDTH &&
                mouseY >= itemY && mouseY <= itemY + BUTTON_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    private void clampScrollOffset() {
        int maxScroll = Math.max(0, worldList.size() - VISIBLE_ITEMS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private boolean isClickInsideDialog(double x, double y) {
        // Simplified dialog bounds check
        return x >= 100 && x <= 700 && y >= 100 && y <= 500;
    }

    // ===== PUBLIC GETTERS =====

    public List<String> getWorldList() {
        return new ArrayList<>(worldList);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public int getHoveredIndex() {
        return hoveredIndex;
    }

    public boolean isShowCreateDialog() {
        return showCreateDialog;
    }

    public String getNewWorldName() {
        if (dialogInitialized && worldNameField != null) {
            return worldNameField.getText();
        }
        return "";
    }

    @Deprecated
    public void setNewWorldName(String name) {
        // Deprecated - handled internally
    }
}