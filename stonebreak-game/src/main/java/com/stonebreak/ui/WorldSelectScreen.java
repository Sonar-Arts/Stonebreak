package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.*;

import java.util.ArrayList;
import java.util.List;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;

/**
 * Screen for selecting and creating worlds
 */
public class WorldSelectScreen {
    private final UIRenderer uiRenderer;
    
    // World selection state
    private List<String> worldList = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollOffset = 0;
    private static final int VISIBLE_ITEMS = 5;
    
    // Dialog state
    private boolean showCreateDialog = false;
    private String newWorldName = "";
    private boolean createButtonSelected = false;
    private TextInputField worldNameField;
    private TextInputField seedField;
    private int hoveredIndex = -1; // Track which world item is hovered
    private boolean mouseControlActive = false; // Track if mouse or keyboard is controlling selection
    
    // Input state
    private boolean escKeyPressed = false;
    private boolean enterKeyPressed = false;
    private boolean upKeyPressed = false;
    private boolean downKeyPressed = false;
    private boolean tabKeyPressed = false;
    private boolean f2KeyPressed = false;
    
    // Double-click detection
    private long lastClickTime = 0;
    private int lastClickedIndex = -1;
    private static final long DOUBLE_CLICK_TIME = 400; // 400ms for double-click detection
    
    // Focus management
    private enum FocusState {
        WORLD_LIST,
        CREATE_BUTTON
    }
    private FocusState currentFocus = FocusState.WORLD_LIST;
    
    public WorldSelectScreen(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
        
        // Initialize text input fields with enhanced features
        worldNameField = new TextInputField("My Awesome World");
        worldNameField.setMaxLength(50);
        worldNameField.setIconType("world");
        worldNameField.setFieldLabel("World Name:");
        worldNameField.setShowValidationIndicator(true);
        worldNameField.setValidator(new TextInputField.InputValidator() {
            @Override
            public boolean isValid(String text) {
                return text != null && !text.trim().isEmpty() && text.length() <= 50 && text.length() >= 3;
            }
            
            @Override
            public String getErrorMessage() {
                return "World name must be between 3 and 50 characters";
            }
        });
        
        seedField = new TextInputField("12345 (optional)");
        seedField.setMaxLength(20);
        seedField.setIconType("seed");
        seedField.setFieldLabel("World Seed:");
        seedField.setShowValidationIndicator(false); // Optional field, no validation needed
        
        refreshWorldList();
    }
    
    /**
     * Refreshes the list of available worlds
     */
    private void refreshWorldList() {
        worldList.clear();
        
        // Scan for existing worlds in the worlds directory
        java.io.File worldsDir = new java.io.File("worlds");
        if (worldsDir.exists() && worldsDir.isDirectory()) {
            java.io.File[] worldDirs = worldsDir.listFiles(java.io.File::isDirectory);
            if (worldDirs != null) {
                for (java.io.File worldDir : worldDirs) {
                    worldList.add(worldDir.getName());
                }
            }
        }
        
        // If no worlds found, create some default placeholder worlds for testing
        if (worldList.isEmpty()) {
            System.out.println("No existing worlds found, creating default world options");
            // Don't add placeholder worlds automatically - let user create their own
        }
        
        // Ensure selected index is valid
        if (selectedIndex >= worldList.size()) {
            selectedIndex = worldList.size() - 1;
        }
        if (selectedIndex < 0 && !worldList.isEmpty()) {
            selectedIndex = 0;
        }
    }
    
    /**
     * Handles keyboard input for the world select screen
     */
    public void handleInput(long window) {
        boolean escPressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        boolean enterPressed = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS;
        boolean upPressed = glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean downPressed = glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        boolean tabPressed = glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS;
        boolean f2Pressed = glfwGetKey(window, GLFW_KEY_F2) == GLFW_PRESS;
        
        // Handle escape key (edge detection)
        if (escPressed && !escKeyPressed) {
            if (showCreateDialog) {
                // Close dialog
                showCreateDialog = false;
                newWorldName = "";
            } else {
                // Return to main menu
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
        escKeyPressed = escPressed;
        
        // Handle enter key (edge detection)
        if (enterPressed && !enterKeyPressed) {
            handleEnterWithFocus();
        }
        enterKeyPressed = enterPressed;
        
        // Handle navigation (edge detection)
        if (upPressed && !upKeyPressed) {
            if (!showCreateDialog && selectedIndex > 0) {
                mouseControlActive = false; // Switch to keyboard control
                selectedIndex--;
                // Adjust scroll offset if needed
                if (selectedIndex < scrollOffset) {
                    scrollOffset = selectedIndex;
                }
            }
        }
        upKeyPressed = upPressed;
        
        if (downPressed && !downKeyPressed) {
            if (!showCreateDialog && selectedIndex < worldList.size() - 1) {
                mouseControlActive = false; // Switch to keyboard control
                selectedIndex++;
                // Adjust scroll offset if needed
                if (selectedIndex >= scrollOffset + VISIBLE_ITEMS) {
                    scrollOffset = selectedIndex - VISIBLE_ITEMS + 1;
                }
            }
        }
        downKeyPressed = downPressed;
        
        // Handle Tab key for focus switching (edge detection)
        if (tabPressed && !tabKeyPressed) {
            if (!showCreateDialog) {
                mouseControlActive = false; // Switch to keyboard control
                switchFocus();
            }
        }
        tabKeyPressed = tabPressed;
        
        // Handle F2 key for world editing (edge detection)
        if (f2Pressed && !f2KeyPressed) {
            if (!showCreateDialog && selectedIndex >= 0 && selectedIndex < worldList.size()) {
                mouseControlActive = false; // Switch to keyboard control
                startEditingWorld(worldList.get(selectedIndex));
            }
        }
        f2KeyPressed = f2Pressed;
    }
    
    /**
     * Calculates the bounds for a world list item at the given index
     */
    private boolean isClickWithinWorldItem(double mouseX, double mouseY, int worldIndex, int width, int height) {
        // Use same layout calculations as UIRenderer
        float headerHeight = 150f;
        float footerHeight = 80f;
        float buttonWidth = 500f;
        float buttonHeight = 50f;
        float spacing = 10f;
        float startY = headerHeight + 30f;
        float centerX = width / 2.0f;
        
        // Calculate which items are visible (same logic as UIRenderer)
        int startIndex = Math.max(0, scrollOffset);
        int endIndex = Math.min(worldList.size(), startIndex + VISIBLE_ITEMS);
        
        // Check if this world index is currently visible
        if (worldIndex < startIndex || worldIndex >= endIndex) {
            return false; // Not visible, can't click it
        }
        
        // Calculate the rendered position (accounting for scroll offset)
        int renderedPosition = worldIndex - startIndex;
        float itemY = startY + renderedPosition * (buttonHeight + spacing);
        float itemX = centerX - buttonWidth / 2;
        
        // Check bounds
        return mouseX >= itemX && mouseX <= itemX + buttonWidth &&
               mouseY >= itemY && mouseY <= itemY + buttonHeight;
    }

    /**
     * Handles mouse click events
     */
    public void handleMouseClick(double x, double y, int width, int height) {
        // Handle TextInputField clicks if create dialog is open
        if (showCreateDialog) {
            boolean nameFieldClicked = worldNameField.handleMouseClick(x, y);
            boolean seedFieldClicked = seedField.handleMouseClick(x, y);
            
            // If neither field was clicked, blur both
            if (!nameFieldClicked && !seedFieldClicked) {
                worldNameField.setFocused(false);
                seedField.setFocused(false);
            }
            return;
        }
        
        // Handle world list item clicks
        if (!showCreateDialog) {
            for (int i = 0; i < worldList.size(); i++) {
                if (isClickWithinWorldItem(x, y, i, width, height)) {
                    long currentTime = System.currentTimeMillis();
                    
                    // Check for double-click
                    if (i == lastClickedIndex && 
                        i == selectedIndex && 
                        (currentTime - lastClickTime) < DOUBLE_CLICK_TIME) {
                        // Double-click detected - load the world
                        if (i < worldList.size()) {
                            loadWorld(worldList.get(i));
                        }
                        return;
                    }
                    
                    // Single click - select the world
                    selectedIndex = i;
                    mouseControlActive = true;
                    lastClickedIndex = i;
                    lastClickTime = currentTime;
                    return;
                }
            }
        }
        
        // Handle create button (updated with correct positioning from UIRenderer)
        float footerHeight = 80f;
        float footerStart = height - footerHeight;
        float footerButtonWidth = 300f;
        float footerButtonHeight = 40f;
        float centerX = width / 2.0f;
        float createButtonX = centerX - footerButtonWidth / 2;
        float createButtonY = footerStart + 15f;
        
        if (x >= createButtonX && x <= createButtonX + footerButtonWidth &&
            y >= createButtonY && y <= createButtonY + footerButtonHeight) {
            // Set up text input fields for the dialog BEFORE showing dialog
            setupCreateDialog(width, height);
            showCreateDialog = true;
            newWorldName = "";
            
            // Ensure name field gets focus after dialog is shown
            worldNameField.setFocused(true);
            seedField.setFocused(false);
        }
    }
    
    /**
     * Handles mouse right click events
     */
    public void handleMouseRightClick(double x, double y, int width, int height) {
        // TODO: Implement context menu for world options (delete, etc.)
    }
    
    /**
     * Handles mouse move events for hover states
     */
    public void handleMouseMove(double x, double y, int width, int height) {
        // Reset hover state
        hoveredIndex = -1;
        
        // Check world list item hover (only when dialog is not shown)
        if (!showCreateDialog) {
            for (int i = 0; i < worldList.size(); i++) {
                if (isClickWithinWorldItem(x, y, i, width, height)) {
                    hoveredIndex = i;
                    // Only update selectedIndex if mouse moved significantly or clicked
                    // This prevents mouse movement from overriding keyboard navigation accidentally
                    mouseControlActive = true; // Switch to mouse control
                    selectedIndex = i;
                    break; // Only one item can be hovered at a time
                }
            }
        }
        
        // Update create button hover state (using correct positioning)
        float footerHeight = 80f;
        float footerStart = height - footerHeight;
        float footerButtonWidth = 300f;
        float footerButtonHeight = 40f;
        float centerX = width / 2.0f;
        float createButtonX = centerX - footerButtonWidth / 2;
        float createButtonY = footerStart + 15f;
        
        createButtonSelected = (x >= createButtonX && x <= createButtonX + footerButtonWidth &&
                               y >= createButtonY && y <= createButtonY + footerButtonHeight);
    }
    
    /**
     * Handles mouse wheel scrolling
     */
    public void handleMouseWheel(double yOffset) {
        if (!showCreateDialog) {
            scrollOffset -= (int) yOffset;
            
            // Clamp scroll offset
            int maxScroll = Math.max(0, worldList.size() - VISIBLE_ITEMS);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            
            // If we're using keyboard control and the selected item is no longer visible,
            // adjust the selection to stay within the visible area
            if (!mouseControlActive && selectedIndex != -1) {
                if (selectedIndex < scrollOffset) {
                    selectedIndex = scrollOffset; // Move selection to first visible item
                } else if (selectedIndex >= scrollOffset + VISIBLE_ITEMS) {
                    selectedIndex = scrollOffset + VISIBLE_ITEMS - 1; // Move selection to last visible item
                }
            }
        }
    }
    
    /**
     * Renders the world select screen
     */
    public void render(int width, int height) {
        // Update text field positions before rendering
        if (showCreateDialog) {
            setupCreateDialog(width, height);
        }
        
        // Render the main screen
        uiRenderer.renderWorldSelectScreen(width, height, worldList, selectedIndex, 
                                         showCreateDialog, scrollOffset, VISIBLE_ITEMS, createButtonSelected);
        
        // Render create dialog container and text input fields if dialog is shown
        if (showCreateDialog) {
            // Render the visual container first (behind the input fields)
            uiRenderer.renderCreateDialogContainer(width, height);
            
            // Then render text input fields on top
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                renderTextInputFields(stack);
            }
        }
    }
    
    /**
     * Creates a new world with the given name
     */
    private void createNewWorld(String worldName) {
        System.out.println("Creating new world: " + worldName);
        
        // Generate a random seed for the new world
        long seed = System.currentTimeMillis();
        
        // Create the world directory structure
        java.io.File worldsDir = new java.io.File("worlds");
        if (!worldsDir.exists()) {
            worldsDir.mkdirs();
        }
        
        java.io.File worldDir = new java.io.File(worldsDir, worldName);
        if (!worldDir.exists()) {
            worldDir.mkdirs();
            
            // Add to the list and select it
            worldList.add(worldName);
            selectedIndex = worldList.size() - 1;
            
            // Start world generation for the new world
            Game.getInstance().startWorldGeneration(worldName, seed);
        } else {
            System.err.println("World '" + worldName + "' already exists!");
        }
    }
    
    /**
     * Loads the specified world
     */
    private void loadWorld(String worldName) {
        System.out.println("Loading world: " + worldName);
        
        // Generate or use existing seed for the world
        long seed = worldName.hashCode(); // Use world name hash as seed for consistency
        
        // Call proper world generation with loading screen
        Game.getInstance().startWorldGeneration(worldName, seed);
    }
    
    /**
     * Gets the list of available worlds
     */
    public List<String> getWorldList() {
        return new ArrayList<>(worldList);
    }
    
    /**
     * Gets the currently selected world index
     */
    public int getSelectedIndex() {
        return selectedIndex;
    }
    
    /**
     * Gets the current scroll offset
     */
    public int getScrollOffset() {
        return scrollOffset;
    }
    
    /**
     * Gets the currently hovered world index
     */
    public int getHoveredIndex() {
        return hoveredIndex;
    }
    
    /**
     * Checks if the create dialog is shown
     */
    public boolean isShowCreateDialog() {
        return showCreateDialog;
    }
    
    /**
     * Gets the new world name being entered
     */
    public String getNewWorldName() {
        return newWorldName;
    }
    
    /**
     * Sets the new world name
     */
    public void setNewWorldName(String name) {
        this.newWorldName = name;
    }
    
    /**
     * Handles character input for world name entry
     */
    public void handleCharacterInput(char character) {
        if (!showCreateDialog) return;
        
        // Route character input to focused text field only
        if (worldNameField.isFocused()) {
            worldNameField.handleCharacterInput(character);
            newWorldName = worldNameField.getText();
        } else if (seedField.isFocused()) {
            seedField.handleCharacterInput(character);
        }
    }
    
    /**
     * Handles key input for world name entry (backspace, enter, etc.)
     */
    public void handleKeyInput(int key, int action, int mods) {
        if (!showCreateDialog) return;
        
        // Route key input to focused text field only
        if (worldNameField.isFocused()) {
            worldNameField.handleKeyInput(key, action, mods);
            newWorldName = worldNameField.getText();
        } else if (seedField.isFocused()) {
            seedField.handleKeyInput(key, action, mods);
        }
        
        // Handle special dialog keys
        if (action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_ENTER:
                    if (worldNameField.isValid() && !worldNameField.getText().trim().isEmpty()) {
                        createNewWorld(worldNameField.getText().trim(), seedField.getText().trim());
                        closeCreateDialog();
                    }
                    break;
                case GLFW_KEY_ESCAPE:
                    closeCreateDialog();
                    break;
                case GLFW_KEY_TAB:
                    // Switch focus between name and seed fields
                    if (worldNameField.isFocused()) {
                        worldNameField.setFocused(false);
                        seedField.setFocused(true);
                    } else {
                        seedField.setFocused(false);
                        worldNameField.setFocused(true);
                    }
                    break;
            }
        }
    }
    
    /**
     * Switches focus between world list and create button
     */
    private void switchFocus() {
        if (currentFocus == FocusState.WORLD_LIST) {
            currentFocus = FocusState.CREATE_BUTTON;
            createButtonSelected = true;
        } else {
            currentFocus = FocusState.WORLD_LIST;
            createButtonSelected = false;
            // Ensure we have a valid selection in the world list
            if (selectedIndex < 0 && !worldList.isEmpty()) {
                selectedIndex = 0;
            }
        }
    }
    
    /**
     * Starts editing mode for the specified world (F2 key functionality)
     */
    private void startEditingWorld(String worldName) {
        // For now, this is a placeholder. In a full implementation,
        // this would open a rename dialog or inline editing mode
        System.out.println("Edit world requested for: " + worldName);
        
        // TODO: Implement world editing functionality
        // This could involve:
        // - Opening a rename dialog
        // - Inline text editing
        // - World settings modification
    }
    
    /**
     * Updates the enter key handling to work with focus state
     */
    private void handleEnterWithFocus() {
        if (showCreateDialog) {
            // Create new world using TextInputField data
            if (worldNameField.isValid() && !worldNameField.getText().trim().isEmpty()) {
                createNewWorld(worldNameField.getText().trim(), seedField.getText().trim());
                closeCreateDialog();
            } else {
                // Trigger error shake animation for invalid input
                worldNameField.triggerErrorShake();
            }
        } else if (currentFocus == FocusState.CREATE_BUTTON || createButtonSelected) {
            // Open create dialog
            setupCreateDialog(800, 600); // Default size, will be adjusted in render
            showCreateDialog = true;
            
            // Ensure name field gets focus
            worldNameField.setFocused(true);
            seedField.setFocused(false);
        } else if (selectedIndex >= 0 && selectedIndex < worldList.size()) {
            // Load selected world
            loadWorld(worldList.get(selectedIndex));
        }
    }
    
    /**
     * Sets up the create dialog text fields with enhanced layout and spacing
     */
    private void setupCreateDialog(float width, float height) {
        // Calculate enhanced dialog dimensions for better proportions
        float dialogWidth = 450f;
        float dialogHeight = 320f;
        float dialogX = (width - dialogWidth) / 2;
        float dialogY = (height - dialogHeight) / 2;
        
        // Enhanced input field dimensions
        float inputWidth = 400f;
        float inputHeight = 40f; // Increased height for better visibility
        float inputX = dialogX + 25f;
        
        // Improved spacing for labels and fields
        float labelSpacing = 25f; // Space for labels above fields
        float fieldSpacing = 75f; // Space between fields
        float startY = dialogY + 85f; // Starting position for first field
        
        // Set up world name field with enhanced positioning
        float nameInputY = startY;
        worldNameField.setBounds(inputX, nameInputY, inputWidth, inputHeight);
        
        // Only reset text if dialog is just being opened
        if (!showCreateDialog) {
            worldNameField.setText(""); // Start with empty field
            seedField.setText(""); // Start with empty field
            newWorldName = "";
        }
        
        // Ensure world name field gets initial focus when dialog opens
        if (!worldNameField.isFocused() && !seedField.isFocused()) {
            worldNameField.setFocused(true);
            seedField.setFocused(false);
        }
        
        // Set up seed field with proper spacing
        float seedInputY = nameInputY + fieldSpacing;
        seedField.setBounds(inputX, seedInputY, inputWidth, inputHeight);
    }
    
    /**
     * Closes the create dialog and resets state
     */
    private void closeCreateDialog() {
        showCreateDialog = false;
        worldNameField.setFocused(false);
        seedField.setFocused(false);
        worldNameField.setText("");
        seedField.setText("");
        newWorldName = "";
    }
    
    /**
     * Renders the TextInputFields when create dialog is open
     */
    public void renderTextInputFields(org.lwjgl.system.MemoryStack stack) {
        if (showCreateDialog) {
            worldNameField.render(uiRenderer, stack);
            seedField.render(uiRenderer, stack);
        }
    }
    
    /**
     * Creates a new world with the given name and seed
     */
    private void createNewWorld(String worldName, String seed) {
        System.out.println("Creating new world: " + worldName + " with seed: " + (seed.isEmpty() ? "[random]" : seed));
        
        // Parse seed - if empty or invalid, use random seed
        long parsedSeed;
        if (seed == null || seed.trim().isEmpty()) {
            // Generate random seed
            parsedSeed = System.currentTimeMillis();
            System.out.println("Using random seed: " + parsedSeed);
        } else {
            // Try to parse as numeric seed first
            try {
                parsedSeed = Long.parseLong(seed.trim());
                System.out.println("Using numeric seed: " + parsedSeed);
            } catch (NumberFormatException e) {
                // If not numeric, use string hashcode as seed
                parsedSeed = seed.trim().hashCode();
                System.out.println("Using string seed '" + seed.trim() + "' as numeric: " + parsedSeed);
            }
        }
        
        // Create the world directory structure
        java.io.File worldsDir = new java.io.File("worlds");
        if (!worldsDir.exists()) {
            worldsDir.mkdirs();
        }
        
        java.io.File worldDir = new java.io.File(worldsDir, worldName);
        if (!worldDir.exists()) {
            worldDir.mkdirs();
            
            // Add to the list and select it
            worldList.add(worldName);
            selectedIndex = worldList.size() - 1;
            
            // Start world generation for the new world with specified seed
            Game.getInstance().startWorldGeneration(worldName, parsedSeed);
        } else {
            System.err.println("World '" + worldName + "' already exists!");
        }
    }
}