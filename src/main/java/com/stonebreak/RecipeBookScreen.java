package com.stonebreak;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.nanovg.NVGColor;
import static org.lwjgl.nanovg.NanoVG.*;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.system.MemoryStack.stackPush;
import org.joml.Vector2f;

public class RecipeBookScreen {
    private final UIRenderer uiRenderer;
    private final CraftingManager craftingManager;
    private final InputHandler inputHandler;
    private final Renderer renderer;
    private List<Recipe> recipes;

    private boolean visible = false;

    private int scrollOffset = 0;
    private final int RECIPE_DISPLAY_HEIGHT = 80;
    private final int ITEM_SLOT_SIZE = 40; // Match inventory slot size
    private final int PADDING = 20; // Match inventory padding
    private final int TITLE_HEIGHT = 30;

    private String searchText = "";
    private boolean searchActive = false;
    
    // Category filtering
    private String selectedCategory = "All";
    private static final String[] CATEGORIES = {"All", "Building", "Tools", "Food", "Decorative"};
    
    // UI state
    private ItemStack hoveredItemStack;
    private Recipe hoveredRecipe;
    
    // Popup state
    private boolean showingRecipePopup = false;
    private Recipe selectedRecipe = null;
    private boolean popupJustOpened = false; // Prevent immediate closure
    
    // Recipe pagination state
    private List<Recipe> currentRecipeVariations = new ArrayList<>();
    private int currentVariationIndex = 0;
    
    // Search state
    private boolean isTyping = false;
    private long lastTypingTime = 0;
    private static final long TYPING_TIMEOUT = 500; // 500ms timeout

    public RecipeBookScreen(UIRenderer uiRenderer, InputHandler inputHandler, Renderer renderer) {
        this.uiRenderer = uiRenderer;
        this.inputHandler = inputHandler;
        this.renderer = renderer;
        this.craftingManager = Game.getCraftingManager();
        this.recipes = new ArrayList<>();
        this.hoveredItemStack = null;
        this.hoveredRecipe = null;
        this.showingRecipePopup = false;
        this.selectedRecipe = null;
        this.popupJustOpened = false;
        this.isTyping = false;
        this.lastTypingTime = 0;
    }

    public void init() {
        if (craftingManager != null) {
            this.recipes = craftingManager.getAllRecipes();
            System.out.println("RecipeBookScreen: Loaded " + this.recipes.size() + " recipes.");
        }
        this.searchText = "";
        this.scrollOffset = 0;
        this.searchActive = false;
    }

    public boolean isVisible() {
        return visible;
    }

    public void update(double deltaTime) {
        // Handle typing timeout for search
        if (isTyping && System.currentTimeMillis() - lastTypingTime > TYPING_TIMEOUT) {
            isTyping = false;
        }
        
        // Reset popup just opened flag after one frame
        if (popupJustOpened) {
            popupJustOpened = false;
        }
    }

    public void render() {
        if (!visible || uiRenderer == null) {
            return;
        }

        // Reset hovered items
        hoveredItemStack = null;
        hoveredRecipe = null;

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        // Calculate panel dimensions to match inventory screen proportions
        int panelWidth = Math.min(screenWidth - 100, 900); // Max 900px width
        int panelHeight = Math.min(screenHeight - 100, 700); // Max 700px height
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;

        // Begin UIRenderer frame
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);

        // Draw main panel background (matching inventory style)
        drawInventoryPanel(panelX, panelY, panelWidth, panelHeight);

        // Draw title
        drawInventoryTitle(panelX + panelWidth / 2, panelY + PADDING + 12, "Recipe Book");

        // Category buttons area
        int categoryY = panelY + TITLE_HEIGHT + PADDING;
        drawCategoryButtons(panelX + PADDING, categoryY, panelWidth - 2 * PADDING);

        // Search bar
        int searchBarY = categoryY + 35 + PADDING;
        drawSearchBar(panelX + PADDING, searchBarY, panelWidth - 2 * PADDING, 30);

        // Recipe grid area
        int recipeGridY = searchBarY + 30 + PADDING;
        int recipeGridHeight = panelHeight - (recipeGridY - panelY) - PADDING;
        drawRecipeGrid(panelX + PADDING, recipeGridY, panelWidth - 2 * PADDING, recipeGridHeight);
        
        // End UIRenderer frame before drawing popup (to handle 3D items properly)
        uiRenderer.endFrame();
        
        // Draw recipe popup if showing (after main UI frame)
        if (showingRecipePopup && selectedRecipe != null) {
            drawRecipePopup(screenWidth, screenHeight);
        }
    }
    
    public void renderTooltipsOnly() {
        if (!visible) {
            return;
        }
        
        // Begin frame for tooltips
        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
        
        // Draw item tooltip if hovering over an item
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty()) {
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                drawItemTooltip(item.getName(), mousePos.x + 15, mousePos.y + 15);
            }
        }
        
        // Draw recipe tooltip if hovering over a recipe
        if (hoveredRecipe != null) {
            Vector2f mousePos = inputHandler.getMousePosition();
            Item outputItem = hoveredRecipe.getOutput().getItem();
            if (outputItem != null) {
                String recipeName = "Recipe: " + outputItem.getName();
                drawItemTooltip(recipeName, mousePos.x + 15, mousePos.y + 15);
            }
        }
        
        uiRenderer.endFrame();
    }

    private List<Recipe> getFilteredRecipes() {
        List<Recipe> filtered = new ArrayList<>();
        String lowerSearchText = searchText.toLowerCase();
        
        // Group recipes by output block type to show only one recipe per output type
        Map<Integer, Recipe> uniqueOutputRecipes = new HashMap<>();
        
        for (Recipe recipe : recipes) {
            // Filter by category first
            if (!selectedCategory.equals("All")) {
                Item outputItem = recipe.getOutput().getItem();
                String category = getCategoryForItem(outputItem);
                if (!category.equals(selectedCategory)) {
                    continue;
                }
            }
            
            // Then filter by search text
            boolean matchesSearch = false;
            if (searchText.isEmpty()) {
                matchesSearch = true;
            } else {
                Item outputItem = recipe.getOutput().getItem();
                if (outputItem != null && outputItem.getName().toLowerCase().contains(lowerSearchText)) {
                    matchesSearch = true;
                } else if (recipe.getId() != null && recipe.getId().toLowerCase().contains(lowerSearchText)) {
                    matchesSearch = true;
                }
            }
            
            if (matchesSearch) {
                int outputBlockTypeId = recipe.getOutput().getBlockTypeId();
                // Only add one recipe per output type to the main grid
                if (!uniqueOutputRecipes.containsKey(outputBlockTypeId)) {
                    uniqueOutputRecipes.put(outputBlockTypeId, recipe);
                    filtered.add(recipe);
                }
            }
        }
        return filtered;
    }
    
    /**
     * Gets all recipe variations that produce the same output as the given recipe
     */
    private List<Recipe> getRecipeVariations(Recipe baseRecipe) {
        List<Recipe> variations = new ArrayList<>();
        int targetOutputBlockId = baseRecipe.getOutput().getBlockTypeId();
        
        for (Recipe recipe : recipes) {
            if (recipe.getOutput().getBlockTypeId() == targetOutputBlockId) {
                variations.add(recipe);
            }
        }
        return variations;
    }
    
    private String getCategoryForItem(Item item) {
        if (item == null) return "All";
        
        // Use the item's actual category if available
        if (item instanceof ItemType || item instanceof BlockType) {
            ItemCategory category = item.getCategory();
            switch (category) {
                case TOOLS -> { return "Tools"; }
                case FOOD -> { return "Food"; }
                case DECORATIVE -> { return "Decorative"; }
                case MATERIALS -> { return "Building"; } // Materials go under Building for now
                case BLOCKS -> { return "Building"; }
                default -> { return "Building"; }
            }
        }
        
        // Fallback to name-based categorization for compatibility
        String name = item.getName().toLowerCase();
        if (name.contains("tool") || name.contains("pick") || name.contains("axe") || name.contains("shovel")) {
            return "Tools";
        } else if (name.contains("food") || name.contains("bread") || name.contains("apple")) {
            return "Food";
        } else if (name.contains("flower") || name.contains("decoration")) {
            return "Decorative";
        } else {
            return "Building";
        }
    }

    // Helper method to draw inventory panel using UIRenderer (matching InventoryScreen style)
    private void drawInventoryPanel(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            
            // Main panel background
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(50, 50, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Panel border
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
    
    private void drawInventoryTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 24);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, title);
        }
    }
    
    private void drawCategoryButtons(int x, int y, int width) {
        int buttonWidth = (width - (CATEGORIES.length - 1) * 5) / CATEGORIES.length;
        
        for (int i = 0; i < CATEGORIES.length; i++) {
            int buttonX = x + i * (buttonWidth + 5);
            boolean isSelected = CATEGORIES[i].equals(selectedCategory);
            drawCategoryButton(buttonX, y, buttonWidth, 25, CATEGORIES[i], isSelected);
        }
    }
    
    private void drawCategoryButton(int x, int y, int width, int height, String text, boolean selected) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= x && mousePos.x <= x + width &&
                               mousePos.y >= y && mousePos.y <= y + height;
            
            // Button background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, 3);
            if (selected) {
                nvgFillColor(vg, nvgRGBA(120, 140, 160, 255, NVGColor.malloc(stack))); // Selected color
            } else if (isHovering) {
                nvgFillColor(vg, nvgRGBA(100, 120, 140, 255, NVGColor.malloc(stack))); // Hover color
            } else {
                nvgFillColor(vg, nvgRGBA(80, 100, 120, 255, NVGColor.malloc(stack))); // Normal color
            }
            nvgFill(vg);
            
            // Button border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 0.5f, y + 0.5f, width - 1, height - 1, 2.5f);
            nvgStrokeColor(vg, nvgRGBA(150, 170, 190, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 1.0f);
            nvgStroke(vg);
            
            // Button text
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, x + width / 2.0f, y + height / 2.0f, text);
        }
    }
    
    private void drawSearchBar(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            
            // Search bar background
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(70, 70, 70, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Search bar border with active state
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgStrokeWidth(vg, searchActive ? 2.0f : 1.0f);
            if (searchActive) {
                nvgStrokeColor(vg, nvgRGBA(120, 140, 180, 255, NVGColor.malloc(stack))); // Active border
            } else {
                nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack))); // Normal border
            }
            nvgStroke(vg);
            
            // Search text with blinking cursor
            String displayText;
            if (searchActive || isTyping) {
                long currentTime = System.currentTimeMillis();
                boolean showCursor = (currentTime / 500) % 2 == 0; // Blink every 500ms
                displayText = searchText + (showCursor ? "_" : "");
            } else {
                displayText = searchText.isEmpty() ? "Type to search recipes..." : searchText;
            }
            
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            
            if (searchText.isEmpty() && !searchActive && !isTyping) {
                nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack))); // Placeholder color
            } else {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack))); // Normal text color
            }
            nvgText(vg, x + 10, y + height / 2.0f, displayText);
        }
    }
    
    private void drawRecipeGrid(int x, int y, int width, int height) {
        List<Recipe> filteredRecipes = getFilteredRecipes();
        
        if (filteredRecipes.isEmpty()) {
            drawEmptyMessage(x + width / 2, y + height / 2, "No recipes found");
            return;
        }
        
        // Calculate grid layout
        int recipesPerRow = Math.max(1, width / (RECIPE_DISPLAY_HEIGHT + 10));
        int recipeRows = (int) Math.ceil((double) filteredRecipes.size() / recipesPerRow);
        int maxVisibleRows = height / (RECIPE_DISPLAY_HEIGHT + 10);
        
        // Adjust scroll offset
        int maxScrollRows = Math.max(0, recipeRows - maxVisibleRows);
        if (scrollOffset > maxScrollRows) {
            scrollOffset = maxScrollRows;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        
        // Draw visible recipes
        for (int row = 0; row < maxVisibleRows && row + scrollOffset < recipeRows; row++) {
            for (int col = 0; col < recipesPerRow; col++) {
                int recipeIndex = (row + scrollOffset) * recipesPerRow + col;
                if (recipeIndex >= filteredRecipes.size()) break;
                
                Recipe recipe = filteredRecipes.get(recipeIndex);
                int recipeX = x + col * (RECIPE_DISPLAY_HEIGHT + 10);
                int recipeY = y + row * (RECIPE_DISPLAY_HEIGHT + 10);
                
                drawRecipeCard(recipe, recipeX, recipeY, RECIPE_DISPLAY_HEIGHT - 10, RECIPE_DISPLAY_HEIGHT - 10);
            }
        }
    }
    
    private void drawRecipeCard(Recipe recipe, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= x && mousePos.x <= x + width &&
                               mousePos.y >= y && mousePos.y <= y + height;
            
            if (isHovering) {
                hoveredRecipe = recipe;
            }
            
            // Card background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, 4);
            if (isHovering) {
                nvgFillColor(vg, nvgRGBA(80, 80, 90, 255, NVGColor.malloc(stack))); // Hover color
            } else {
                nvgFillColor(vg, nvgRGBA(60, 60, 70, 255, NVGColor.malloc(stack))); // Normal color
            }
            nvgFill(vg);
            
            // Card border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 0.5f, y + 0.5f, width - 1, height - 1, 3.5f);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 1.0f);
            nvgStroke(vg);
            
            // Draw recipe output item as main display
            ItemStack output = recipe.getOutput();
            if (output != null && !output.isEmpty()) {
                Item item = output.getItem();
                if (item != null && item != BlockType.AIR) {
                    // Draw item slot for output
                    int itemSize = Math.min(ITEM_SLOT_SIZE, width - 10);
                    int itemX = x + (width - itemSize) / 2;
                    int itemY = y + 5;
                    
                    drawRecipeSlot(output, itemX, itemY, itemSize, false);
                    
                    // Draw item name
                    nvgFontSize(vg, 12);
                    nvgFontFace(vg, "sans");
                    nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);
                    nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                    
                    String itemName = item.getName();
                    if (itemName.length() > 8) {
                        itemName = itemName.substring(0, 8) + "...";
                    }
                    nvgText(vg, x + width / 2.0f, itemY + itemSize + 5, itemName);
                }
            }
        }
    }
    
    private void drawRecipeSlot(ItemStack itemStack, int slotX, int slotY, int slotSize, boolean isInput) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= slotX && mousePos.x <= slotX + slotSize &&
                               mousePos.y >= slotY && mousePos.y <= slotY + slotSize;
            
            if (isHovering && itemStack != null && !itemStack.isEmpty()) {
                hoveredItemStack = itemStack;
            }
            
            // Slot background
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, slotSize, slotSize);
            nvgFillColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Slot inner background
            nvgBeginPath(vg);
            nvgRect(vg, slotX + 1, slotY + 1, slotSize - 2, slotSize - 2);
            nvgFillColor(vg, nvgRGBA(70, 70, 70, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            if (itemStack != null && !itemStack.isEmpty()) {
                Item item = itemStack.getItem();
                int count = itemStack.getCount();
                
                if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                    try {
                        // End NanoVG frame temporarily to draw 3D item
                        uiRenderer.endFrame();
                        
                        // Draw 3D item using existing renderer - only works for BlockTypes for now
                        if (item instanceof BlockType) {
                            renderer.draw3DItemInSlot((BlockType) item, slotX + 2, slotY + 2, slotSize - 4, slotSize - 4);
                        } else {
                            // For ItemTypes, render a 2D sprite using UIRenderer
                            uiRenderer.renderItemIcon(slotX + 2, slotY + 2, slotSize - 4, slotSize - 4, item, renderer.getTextureAtlas());
                        }
                        
                        // Restart NanoVG frame
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                        
                        if (count > 1) {
                            String countText = String.valueOf(count);
                            nvgFontSize(vg, 10);
                            nvgFontFace(vg, "sans");
                            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                            
                            // Text shadow
                            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                            nvgText(vg, slotX + slotSize - 2, slotY + slotSize - 2, countText);
                            
                            // Main text
                            nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                            nvgText(vg, slotX + slotSize - 3, slotY + slotSize - 3, countText);
                        }
                    } catch (Exception e) {
                        System.err.println("Error rendering 3D item in recipe slot: " + e.getMessage());
                        try {
                            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                        } catch (Exception e2) {
                            System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                        }
                    }
                }
            }
        }
    }
    
    private void drawEmptyMessage(float centerX, float centerY, String message) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 18);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, message);
        }
    }
    
    private void drawItemTooltip(String itemName, float x, float y) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float padding = 8.0f;
            float cornerRadius = 4.0f;
            
            // Measure text
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "sans");
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, itemName, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];
            
            float tooltipWidth = textWidth + 2 * padding;
            float tooltipHeight = textHeight + 2 * padding;
            
            // Adjust position to stay within screen bounds
            int screenWidth = Game.getWindowWidth();
            int screenHeight = Game.getWindowHeight();
            if (x + tooltipWidth > screenWidth - 10) {
                x = screenWidth - tooltipWidth - 10;
            }
            if (y + tooltipHeight > screenHeight - 10) {
                y = screenHeight - tooltipHeight - 10;
            }
            if (x < 10) x = 10;
            if (y < 10) y = 10;
            
            // Tooltip background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(40, 40, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Tooltip border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 180, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Tooltip text
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, x + tooltipWidth / 2, y + tooltipHeight / 2, itemName);
        }
    }

    public void handleInput() {
        if (!visible) return;
        
        // Handle scrolling
        double scrollY = inputHandler.getAndResetScrollY();
        if (scrollY != 0) {
            List<Recipe> filteredRecipes = getFilteredRecipes();
            int screenWidth = Game.getWindowWidth();
            int recipesPerRow = Math.max(1, (screenWidth - 200) / (RECIPE_DISPLAY_HEIGHT + 10));
            int recipeRows = (int) Math.ceil((double) filteredRecipes.size() / recipesPerRow);
            int maxVisibleRows = (Game.getWindowHeight() - 300) / (RECIPE_DISPLAY_HEIGHT + 10);
            int maxScrollRows = Math.max(0, recipeRows - maxVisibleRows);
            
            scrollOffset -= (int) scrollY;
            if (scrollOffset < 0) {
                scrollOffset = 0;
            }
            if (scrollOffset > maxScrollRows) {
                scrollOffset = maxScrollRows;
            }
        }
        
        // Handle mouse clicks
        if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            Vector2f mousePos = inputHandler.getMousePosition();
            
            // Calculate UI areas
            int screenWidth = Game.getWindowWidth();
            int screenHeight = Game.getWindowHeight();
            int panelWidth = Math.min(screenWidth - 100, 900);
            int panelHeight = Math.min(screenHeight - 100, 700);
            int panelX = (screenWidth - panelWidth) / 2;
            int panelY = (screenHeight - panelHeight) / 2;
            
            // If popup is showing, check for popup interactions first (but not if just opened)
            if (showingRecipePopup && selectedRecipe != null && !popupJustOpened) {
                if (handlePopupClick(mousePos, screenWidth, screenHeight)) {
                    return; // Popup handled the click
                }
            }
            
            // Check category buttons
            int categoryY = panelY + TITLE_HEIGHT + PADDING;
            int buttonWidth = (panelWidth - 2 * PADDING - (CATEGORIES.length - 1) * 5) / CATEGORIES.length;
            boolean categoryClicked = false;
            
            for (int i = 0; i < CATEGORIES.length; i++) {
                int buttonX = panelX + PADDING + i * (buttonWidth + 5);
                if (mousePos.x >= buttonX && mousePos.x <= buttonX + buttonWidth &&
                    mousePos.y >= categoryY && mousePos.y <= categoryY + 25) {
                    selectedCategory = CATEGORIES[i];
                    scrollOffset = 0; // Reset scroll when changing category
                    categoryClicked = true;
                    // Consume the mouse click to prevent multiple triggers
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    break;
                }
            }
            
            if (!categoryClicked) {
                // Check search bar
                int searchBarY = categoryY + 35 + PADDING;
                int searchBarX = panelX + PADDING;
                int searchBarWidth = panelWidth - 2 * PADDING;
                
                boolean clickedSearchBar = (mousePos.x >= searchBarX && mousePos.x <= searchBarX + searchBarWidth &&
                                           mousePos.y >= searchBarY && mousePos.y <= searchBarY + 30);
                
                if (clickedSearchBar) {
                    searchActive = true;
                } else {
                    // Check for recipe clicks
                    Recipe clickedRecipe = getRecipeAtPosition(mousePos, panelX, panelY, panelWidth, panelHeight);
                    if (clickedRecipe != null) {
                        selectedRecipe = clickedRecipe;
                        currentRecipeVariations = getRecipeVariations(clickedRecipe);
                        currentVariationIndex = 0;
                        // Find the index of the clicked recipe in variations
                        for (int i = 0; i < currentRecipeVariations.size(); i++) {
                            if (currentRecipeVariations.get(i).equals(clickedRecipe)) {
                                currentVariationIndex = i;
                                break;
                            }
                        }
                        showingRecipePopup = true;
                        popupJustOpened = true; // Prevent immediate closure
                    } else {
                        searchActive = false;
                    }
                }
            }
        }
        
        // Handle ESC key to close popup
        if (inputHandler.isKeyPressedOnce(GLFW.GLFW_KEY_ESCAPE)) {
            if (showingRecipePopup) {
                showingRecipePopup = false;
                selectedRecipe = null;
                currentRecipeVariations.clear();
                currentVariationIndex = 0;
            }
        }
        
        // Search input is now handled via InputHandler callbacks (handleCharacterInput/handleKeyInput)
        // This prevents double input processing
    }

    public void onOpen() {
        this.visible = true;
        init(); // Load recipes, reset search, scroll
    }

    public void onClose() {
        this.visible = false;
        searchActive = false;
        searchText = "";
        showingRecipePopup = false;
        selectedRecipe = null;
        popupJustOpened = false;
        isTyping = false;
        currentRecipeVariations.clear();
        currentVariationIndex = 0;
        // scrollOffset = 0; // Optionally reset scroll on close
    }
    
    /**
     * Handle character input from InputHandler for search functionality
     */
    public void handleCharacterInput(char character) {
        if (!visible || !searchActive) return;
        
        // Add character to search text
        searchText += character;
        isTyping = true;
        lastTypingTime = System.currentTimeMillis();
        scrollOffset = 0; // Reset scroll when searching
    }
    
    /**
     * Handle key input from InputHandler for search functionality
     */
    public void handleKeyInput(int key, int action) {
        if (!visible) return;
        
        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (searchActive && !searchText.isEmpty()) {
                        searchText = searchText.substring(0, searchText.length() - 1);
                        isTyping = true;
                        lastTypingTime = System.currentTimeMillis();
                        scrollOffset = 0;
                    }
                }
                case GLFW.GLFW_KEY_ENTER -> {
                    if (searchActive) {
                        searchActive = false;
                        isTyping = false;
                    }
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    // Escape handling is done in the main handleInput method
                }
            }
        }
    }
    
    // Helper method to get recipe at mouse position
    private Recipe getRecipeAtPosition(Vector2f mousePos, int panelX, int panelY, int panelWidth, int panelHeight) {
        List<Recipe> filteredRecipes = getFilteredRecipes();
        if (filteredRecipes.isEmpty()) {
            return null;
        }
        
        // Calculate recipe grid area
        int categoryY = panelY + TITLE_HEIGHT + PADDING;
        int searchBarY = categoryY + 35 + PADDING;
        int recipeGridY = searchBarY + 30 + PADDING;
        int recipeGridX = panelX + PADDING;
        int recipeGridWidth = panelWidth - 2 * PADDING;
        int recipeGridHeight = panelHeight - (recipeGridY - panelY) - PADDING;
        
        // Calculate grid layout
        int recipesPerRow = Math.max(1, recipeGridWidth / (RECIPE_DISPLAY_HEIGHT + 10));
        int maxVisibleRows = recipeGridHeight / (RECIPE_DISPLAY_HEIGHT + 10);
        
        // Check if mouse is within recipe grid bounds
        if (mousePos.x < recipeGridX || mousePos.x > recipeGridX + recipeGridWidth ||
            mousePos.y < recipeGridY || mousePos.y > recipeGridY + recipeGridHeight) {
            return null;
        }
        
        // Calculate which recipe was clicked
        int relativeX = (int)(mousePos.x - recipeGridX);
        int relativeY = (int)(mousePos.y - recipeGridY);
        
        int col = relativeX / (RECIPE_DISPLAY_HEIGHT + 10);
        int row = relativeY / (RECIPE_DISPLAY_HEIGHT + 10);
        
        if (col >= recipesPerRow || row >= maxVisibleRows) {
            return null;
        }
        
        // Check if click is within the actual recipe card bounds (not in spacing)
        int cardStartX = col * (RECIPE_DISPLAY_HEIGHT + 10);
        int cardStartY = row * (RECIPE_DISPLAY_HEIGHT + 10);
        int cardEndX = cardStartX + (RECIPE_DISPLAY_HEIGHT - 10); // Subtract spacing
        int cardEndY = cardStartY + (RECIPE_DISPLAY_HEIGHT - 10);
        
        if (relativeX < cardStartX || relativeX > cardEndX ||
            relativeY < cardStartY || relativeY > cardEndY) {
            return null;
        }
        
        int recipeIndex = (row + scrollOffset) * recipesPerRow + col;
        
        if (recipeIndex >= 0 && recipeIndex < filteredRecipes.size()) {
            Recipe clickedRecipe = filteredRecipes.get(recipeIndex);
            return clickedRecipe;
        }
        
        return null;
    }
    
    // Handle popup click interactions
    private boolean handlePopupClick(Vector2f mousePos, int screenWidth, int screenHeight) {
        // Calculate popup dimensions
        int popupWidth = Math.min(600, screenWidth - 80);
        int popupHeight = Math.min(480, screenHeight - 80);
        int popupX = (screenWidth - popupWidth) / 2;
        int popupY = (screenHeight - popupHeight) / 2;
        
        // Check if clicked outside popup (close popup)
        if (mousePos.x < popupX || mousePos.x > popupX + popupWidth ||
            mousePos.y < popupY || mousePos.y > popupY + popupHeight) {
            showingRecipePopup = false;
            selectedRecipe = null;
            currentRecipeVariations.clear();
            currentVariationIndex = 0;
            // Consume the mouse click to prevent multiple triggers
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true; // Handled the click
        }
        
        // Check close button (X button in top right) - updated for new size
        int closeButtonSize = 24;
        int closeButtonX = popupX + popupWidth - closeButtonSize - 16;
        int closeButtonY = popupY + 16;
        
        if (mousePos.x >= closeButtonX && mousePos.x <= closeButtonX + closeButtonSize &&
            mousePos.y >= closeButtonY && mousePos.y <= closeButtonY + closeButtonSize) {
            showingRecipePopup = false;
            selectedRecipe = null;
            currentRecipeVariations.clear();
            currentVariationIndex = 0;
            // Consume the mouse click to prevent multiple triggers
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true; // Handled the click
        }
        
        // Check pagination buttons if there are multiple variations
        if (currentRecipeVariations.size() > 1) {
            int buttonSize = 30;
            int buttonY = popupY + 60; // Below title bar
            int prevButtonX = popupX + 20;
            int nextButtonX = popupX + popupWidth - buttonSize - 20;
            
            // Previous button
            if (mousePos.x >= prevButtonX && mousePos.x <= prevButtonX + buttonSize &&
                mousePos.y >= buttonY && mousePos.y <= buttonY + buttonSize) {
                if (currentVariationIndex > 0) {
                    currentVariationIndex--;
                    selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
                }
                // Consume the mouse click to prevent multiple triggers
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
            
            // Next button
            if (mousePos.x >= nextButtonX && mousePos.x <= nextButtonX + buttonSize &&
                mousePos.y >= buttonY && mousePos.y <= buttonY + buttonSize) {
                if (currentVariationIndex < currentRecipeVariations.size() - 1) {
                    currentVariationIndex++;
                    selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
                }
                // Consume the mouse click to prevent multiple triggers
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
        }
        
        return true; // Click was within popup area, don't close
    }
    
    // Draw detailed recipe popup
    private void drawRecipePopup(int screenWidth, int screenHeight) {
        if (selectedRecipe == null) {
            System.out.println("ERROR: drawRecipePopup called but selectedRecipe is null");
            return;
        }
        
        // Start new NanoVG frame for popup
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);
        
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            
            // Calculate popup dimensions
            // Calculate popup dimensions - larger for better Minecraft feel
            int popupWidth = Math.min(600, screenWidth - 80);
            int popupHeight = Math.min(480, screenHeight - 80);
            int popupX = (screenWidth - popupWidth) / 2;
            int popupY = (screenHeight - popupHeight) / 2;
            
            // Draw dark overlay background
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, screenWidth, screenHeight);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Draw main popup background with Minecraft-style wooden texture feel
            nvgBeginPath(vg);
            nvgRect(vg, popupX, popupY, popupWidth, popupHeight);
            nvgFillColor(vg, nvgRGBA(101, 67, 33, 255, NVGColor.malloc(stack))); // Dark wood brown
            nvgFill(vg);
            
            // Draw inner background panel (lighter wood)
            int innerPadding = 8;
            nvgBeginPath(vg);
            nvgRect(vg, popupX + innerPadding, popupY + innerPadding, 
                   popupWidth - 2 * innerPadding, popupHeight - 2 * innerPadding);
            nvgFillColor(vg, nvgRGBA(139, 92, 46, 255, NVGColor.malloc(stack))); // Medium wood brown
            nvgFill(vg);
            
            // Draw beveled outer border (Minecraft-style raised edge)
            drawMinecraftBeveledBorder(vg, popupX, popupY, popupWidth, popupHeight, stack, true);
            
            // Draw beveled inner border
            drawMinecraftBeveledBorder(vg, popupX + innerPadding, popupY + innerPadding, 
                                     popupWidth - 2 * innerPadding, popupHeight - 2 * innerPadding, stack, false);
            
            // Draw Minecraft-style close button
            int closeButtonSize = 24;
            int closeButtonX = popupX + popupWidth - closeButtonSize - 16;
            int closeButtonY = popupY + 16;
            
            // Close button background (wood texture)
            nvgBeginPath(vg);
            nvgRect(vg, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize);
            nvgFillColor(vg, nvgRGBA(139, 92, 46, 255, NVGColor.malloc(stack))); // Medium wood
            nvgFill(vg);
            
            // Beveled border for 3D button effect
            drawMinecraftBeveledBorder(vg, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, stack, true);
            
            // Draw X symbol with Minecraft styling
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Text shadow for depth
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 180, NVGColor.malloc(stack)));
            nvgText(vg, closeButtonX + closeButtonSize / 2.0f + 1, closeButtonY + closeButtonSize / 2.0f + 1, "×");
            
            // Main text (bright red for visibility)
            nvgFillColor(vg, nvgRGBA(255, 85, 85, 255, NVGColor.malloc(stack)));
            nvgText(vg, closeButtonX + closeButtonSize / 2.0f, closeButtonY + closeButtonSize / 2.0f, "×");
            
            // Draw Minecraft-style title section
            ItemStack output = selectedRecipe.getOutput();
            Item outputItem = output.getItem();
            String recipeTitle = (outputItem != null ? outputItem.getName() : "Unknown") + " Recipe";
            
            // Title background bar
            int titleBarHeight = 40;
            nvgBeginPath(vg);
            nvgRect(vg, popupX + 16, popupY + 16, popupWidth - 32, titleBarHeight);
            nvgFillColor(vg, nvgRGBA(85, 56, 28, 255, NVGColor.malloc(stack))); // Dark wood
            nvgFill(vg);
            
            // Title bar beveled border
            drawMinecraftBeveledBorder(vg, popupX + 16, popupY + 16, popupWidth - 32, titleBarHeight, stack, false);
            
            // Main title text with shadow (Minecraft-style golden text)
            nvgFontSize(vg, 24);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, popupX + popupWidth / 2.0f + 2, popupY + 36 + 2, recipeTitle);
            
            // Main title (golden/yellow like enchanted text)
            nvgFillColor(vg, nvgRGBA(255, 255, 85, 255, NVGColor.malloc(stack)));
            nvgText(vg, popupX + popupWidth / 2.0f, popupY + 36, recipeTitle);
            
            // Draw pagination controls if there are multiple variations
            int paginationY = popupY + 60;
            int recipeContentStartY = popupY + 80;
            if (currentRecipeVariations.size() > 1) {
                drawPaginationControls(vg, popupX, paginationY, popupWidth, stack);
                recipeContentStartY = popupY + 100; // Move recipe content down to make room for pagination
            }
            
            // Draw recipe content with adjusted positioning
            drawDetailedRecipe(selectedRecipe, popupX + 20, recipeContentStartY, popupWidth - 40, popupHeight - (recipeContentStartY - popupY) - 20);
        }
        
        uiRenderer.endFrame();
    }
    
    /**
     * Draws pagination controls for recipe variations
     */
    private void drawPaginationControls(long vg, int popupX, int paginationY, int popupWidth, MemoryStack stack) {
        int buttonSize = 30;
        int prevButtonX = popupX + 20;
        int nextButtonX = popupX + popupWidth - buttonSize - 20;
        
        Vector2f mousePos = inputHandler.getMousePosition();
        
        // Previous button
        boolean prevEnabled = currentVariationIndex > 0;
        boolean prevHovered = mousePos.x >= prevButtonX && mousePos.x <= prevButtonX + buttonSize &&
                             mousePos.y >= paginationY && mousePos.y <= paginationY + buttonSize;
        
        drawPaginationButton(vg, prevButtonX, paginationY, buttonSize, "‹", prevEnabled, prevHovered, stack);
        
        // Next button  
        boolean nextEnabled = currentVariationIndex < currentRecipeVariations.size() - 1;
        boolean nextHovered = mousePos.x >= nextButtonX && mousePos.x <= nextButtonX + buttonSize &&
                             mousePos.y >= paginationY && mousePos.y <= paginationY + buttonSize;
        
        drawPaginationButton(vg, nextButtonX, paginationY, buttonSize, "›", nextEnabled, nextHovered, stack);
        
        // Page indicator in center
        String pageText = (currentVariationIndex + 1) + " / " + currentRecipeVariations.size();
        nvgFontSize(vg, 16);
        nvgFontFace(vg, "sans");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        
        // Text shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f + 1, paginationY + buttonSize / 2.0f + 1, pageText);
        
        // Main text
        nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f, paginationY + buttonSize / 2.0f, pageText);
        
        // Recipe variation indicator below page number
        String variationText = "Recipe Variation";
        nvgFontSize(vg, 12);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);
        
        // Variation text shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f + 1, paginationY + buttonSize + 2 + 1, variationText);
        
        // Main variation text
        nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f, paginationY + buttonSize + 2, variationText);
    }
    
    /**
     * Draws a single pagination button
     */
    private void drawPaginationButton(long vg, int x, int y, int size, String symbol, boolean enabled, boolean hovered, MemoryStack stack) {
        // Button background
        nvgBeginPath(vg);
        nvgRect(vg, x, y, size, size);
        
        if (!enabled) {
            nvgFillColor(vg, nvgRGBA(60, 40, 20, 255, NVGColor.malloc(stack))); // Disabled dark wood
        } else if (hovered) {
            nvgFillColor(vg, nvgRGBA(160, 106, 53, 255, NVGColor.malloc(stack))); // Bright wood (hovered)
        } else {
            nvgFillColor(vg, nvgRGBA(139, 92, 46, 255, NVGColor.malloc(stack))); // Medium wood
        }
        nvgFill(vg);
        
        // Beveled border for 3D effect
        drawMinecraftBeveledBorder(vg, x, y, size, size, stack, enabled);
        
        // Button symbol
        nvgFontSize(vg, 20);
        nvgFontFace(vg, "sans");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        
        if (!enabled) {
            // Disabled text (darker)
            nvgFillColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
        } else {
            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, x + size / 2.0f + 1, y + size / 2.0f + 1, symbol);
            
            // Main text (white for good contrast)
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        }
        nvgText(vg, x + size / 2.0f, y + size / 2.0f, symbol);
    }
    
    // Draw detailed recipe with ingredients and output - Minecraft style
    private void drawDetailedRecipe(Recipe recipe, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            
            List<List<ItemStack>> pattern = recipe.getInputPattern();
            ItemStack output = recipe.getOutput();
            int recipeHeight = recipe.getRecipeHeight();
            int recipeWidth = recipe.getRecipeWidth();
            
            // Force workbench recipes to display as 3x3 grid
            if (requiresWorkbench(recipe)) {
                recipeHeight = 3;
                recipeWidth = 3;
            }
            
            // Calculate layout with larger Minecraft-style slots
            int slotSize = 52; // Larger slots for better visibility
            int slotSpacing = 12; // More spacing for better separation
            
            // Center the recipe horizontally
            int totalRecipeWidth = recipeWidth * slotSize + (recipeWidth - 1) * slotSpacing + 100 + slotSize; // +100 for arrow area, +slotSize for output
            int startX = x + (width - totalRecipeWidth) / 2;
            int startY = y + 40;
            
            // Draw "Ingredients" section background
            int ingredientsWidth = recipeWidth * slotSize + (recipeWidth - 1) * slotSpacing + 16;
            int ingredientsHeight = recipeHeight * slotSize + (recipeHeight - 1) * slotSpacing + 40;
            
            nvgBeginPath(vg);
            nvgRect(vg, startX - 8, startY - 30, ingredientsWidth, ingredientsHeight);
            nvgFillColor(vg, nvgRGBA(101, 67, 33, 180, NVGColor.malloc(stack))); // Semi-transparent dark wood
            nvgFill(vg);
            
            drawMinecraftBeveledBorder(vg, startX - 8, startY - 30, ingredientsWidth, ingredientsHeight, stack, false);
            
            // Draw "Ingredients" label with Minecraft styling
            nvgFontSize(vg, 18);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);
            
            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, startX + ingredientsWidth / 2.0f - 8 + 1, startY - 25 + 1, "Ingredients");
            
            // Main text (light color)
            nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
            nvgText(vg, startX + ingredientsWidth / 2.0f - 8, startY - 25, "Ingredients");
            
            // Draw input pattern with Minecraft-style slots
            for (int r = 0; r < recipeHeight; r++) {
                List<ItemStack> rowPattern = (pattern != null && r < pattern.size()) ? pattern.get(r) : null;
                for (int c = 0; c < recipeWidth; c++) {
                    ItemStack item = (rowPattern != null && c < rowPattern.size()) ? rowPattern.get(c) : null;
                    int slotX = startX + c * (slotSize + slotSpacing);
                    int slotY = startY + r * (slotSize + slotSpacing);
                    
                    // Always draw the slot background first
                    drawMinecraftSlot(slotX, slotY, slotSize, stack);
                    
                    // Then draw the item if it exists
                    if (item != null && !item.isEmpty()) {
                        drawDetailedRecipeSlot(item, slotX, slotY, slotSize);
                    } else {
                        // Draw subtle dot pattern for empty slots
                        drawEmptySlotPattern(vg, slotX, slotY, slotSize, stack);
                    }
                }
            }
            
            // Draw crafting arrow with background
            int arrowX = startX + recipeWidth * (slotSize + slotSpacing) + 30;
            int arrowY = startY + (recipeHeight * (slotSize + slotSpacing) - 40) / 2;
            int arrowWidth = 40;
            int arrowHeight = 24;
            
            // Arrow background
            nvgBeginPath(vg);
            nvgRect(vg, arrowX, arrowY, arrowWidth, arrowHeight);
            nvgFillColor(vg, nvgRGBA(85, 56, 28, 255, NVGColor.malloc(stack))); // Dark wood
            nvgFill(vg);
            
            drawMinecraftBeveledBorder(vg, arrowX, arrowY, arrowWidth, arrowHeight, stack, false);
            
            // Arrow symbol with shadow
            nvgFontSize(vg, 28);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Arrow shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, arrowX + arrowWidth / 2.0f + 1, arrowY + arrowHeight / 2.0f + 1, "→");
            
            // Main arrow (bright for visibility)
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, arrowX + arrowWidth / 2.0f, arrowY + arrowHeight / 2.0f, "→");
            
            // Draw output section
            int outputX = arrowX + arrowWidth + 30;
            int outputY = startY + (recipeHeight * (slotSize + slotSpacing) - slotSize) / 2;
            
            // Output section background
            int outputSectionWidth = slotSize + 16;
            int outputSectionHeight = slotSize + 40;
            
            nvgBeginPath(vg);
            nvgRect(vg, outputX - 8, outputY - 8, outputSectionWidth, outputSectionHeight);
            nvgFillColor(vg, nvgRGBA(101, 67, 33, 180, NVGColor.malloc(stack))); // Semi-transparent dark wood
            nvgFill(vg);
            
            drawMinecraftBeveledBorder(vg, outputX - 8, outputY - 8, outputSectionWidth, outputSectionHeight, stack, false);
            
            // Draw output slot
            drawMinecraftSlot(outputX, outputY, slotSize, stack);
            drawDetailedRecipeSlot(output, outputX, outputY, slotSize);
            
            // Draw "Result" label and item info
            if (output != null && !output.isEmpty()) {
                Item item = output.getItem();
                if (item != null) {
                    String outputLabel = "Result";
                    String itemInfo = item.getName();
                    if (output.getCount() > 1) {
                        itemInfo += " x" + output.getCount();
                    }
                    
                    // "Result" label
                    nvgFontSize(vg, 16);
                    nvgFontFace(vg, "sans");
                    nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);
                    
                    // Label shadow
                    nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                    nvgText(vg, outputX + slotSize / 2.0f + 1, outputY - 25 + 1, outputLabel);
                    
                    // Main label
                    nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
                    nvgText(vg, outputX + slotSize / 2.0f, outputY - 25, outputLabel);
                    
                    // Item info below slot
                    nvgFontSize(vg, 14);
                    nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);
                    
                    // Info shadow
                    nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                    nvgText(vg, outputX + slotSize / 2.0f + 1, outputY + slotSize + 8 + 1, itemInfo);
                    
                    // Main info
                    nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
                    nvgText(vg, outputX + slotSize / 2.0f, outputY + slotSize + 8, itemInfo);
                }
            }
        }
    }
    
    // Helper method to draw empty slot pattern
    private void drawEmptySlotPattern(long vg, int slotX, int slotY, int slotSize, MemoryStack stack) {
        int centerX = slotX + slotSize / 2;
        int centerY = slotY + slotSize / 2;
        int dotSize = 2;
        
        // Draw subtle 3x3 dot pattern
        for (int dy = -6; dy <= 6; dy += 6) {
            for (int dx = -6; dx <= 6; dx += 6) {
                nvgBeginPath(vg);
                nvgRect(vg, centerX + dx - dotSize/2, centerY + dy - dotSize/2, dotSize, dotSize);
                nvgFillColor(vg, nvgRGBA(120, 120, 120, 80, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
    }
    
    // Draw detailed recipe slot (larger version) - item only, slot background drawn separately
    private void drawDetailedRecipeSlot(ItemStack itemStack, int slotX, int slotY, int slotSize) {
        if (itemStack == null || itemStack.isEmpty()) {
            return; // Slot background already drawn
        }
        
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= slotX && mousePos.x <= slotX + slotSize &&
                               mousePos.y >= slotY && mousePos.y <= slotY + slotSize;
            
            if (isHovering) {
                hoveredItemStack = itemStack;
                // Draw hover highlight
                nvgBeginPath(vg);
                nvgRect(vg, slotX + 2, slotY + 2, slotSize - 4, slotSize - 4);
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 30, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            Item item = itemStack.getItem();
            int count = itemStack.getCount();
            
            if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                try {
                    // End NanoVG frame temporarily to draw 3D item
                    uiRenderer.endFrame();
                    
                    // Draw 3D item using existing renderer with more padding for better look - only works for BlockTypes for now
                    if (item instanceof BlockType) {
                        renderer.draw3DItemInSlot((BlockType) item, slotX + 6, slotY + 6, slotSize - 12, slotSize - 12);
                    } else {
                        // For ItemTypes, render a 2D sprite using UIRenderer
                        uiRenderer.renderItemIcon(slotX + 6, slotY + 6, slotSize - 12, slotSize - 12, item, renderer.getTextureAtlas());
                    }
                    
                    // Restart NanoVG frame
                    uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                    
                    if (count > 1) {
                        String countText = String.valueOf(count);
                        nvgFontSize(vg, 16); // Slightly larger for better visibility
                        nvgFontFace(vg, "sans");
                        nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                        
                        // Bold text shadow
                        nvgFillColor(vg, nvgRGBA(0, 0, 0, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + slotSize - 4, slotY + slotSize - 4, countText);
                        
                        // Main text (bright yellow/gold)
                        nvgFillColor(vg, nvgRGBA(255, 255, 85, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + slotSize - 5, slotY + slotSize - 5, countText);
                    }
                } catch (Exception e) {
                    System.err.println("Error rendering 3D item in detailed recipe slot: " + e.getMessage());
                    try {
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                    } catch (Exception e2) {
                        System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                    }
                }
            }
        }
    }
    
    // Draw Minecraft-style beveled border
    private void drawMinecraftBeveledBorder(long vg, int x, int y, int width, int height, MemoryStack stack, boolean raised) {
        int borderWidth = 2;
        
        if (raised) {
            // Light edges (top and left)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + height);
            nvgLineTo(vg, x, y);
            nvgLineTo(vg, x + width, y);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(198, 132, 66, 255, NVGColor.malloc(stack))); // Light wood
            nvgStroke(vg);
            
            // Dark edges (bottom and right)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width, y);
            nvgLineTo(vg, x + width, y + height);
            nvgLineTo(vg, x, y + height);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(66, 44, 22, 255, NVGColor.malloc(stack))); // Dark wood
            nvgStroke(vg);
        } else {
            // Dark edges (top and left) - inset appearance
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + height);
            nvgLineTo(vg, x, y);
            nvgLineTo(vg, x + width, y);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(66, 44, 22, 255, NVGColor.malloc(stack))); // Dark wood
            nvgStroke(vg);
            
            // Light edges (bottom and right)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width, y);
            nvgLineTo(vg, x + width, y + height);
            nvgLineTo(vg, x, y + height);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(198, 132, 66, 255, NVGColor.malloc(stack))); // Light wood
            nvgStroke(vg);
        }
    }
    
    // Draw Minecraft-style inventory slot
    private void drawMinecraftSlot(int slotX, int slotY, int slotSize, MemoryStack stack) {
        long vg = uiRenderer.getVG();
        
        // Dark outer background
        nvgBeginPath(vg);
        nvgRect(vg, slotX, slotY, slotSize, slotSize);
        nvgFillColor(vg, nvgRGBA(55, 55, 55, 255, NVGColor.malloc(stack))); // Dark gray
        nvgFill(vg);
        
        // Slot inner background (slightly lighter)
        int innerPadding = 2;
        nvgBeginPath(vg);
        nvgRect(vg, slotX + innerPadding, slotY + innerPadding, 
               slotSize - 2 * innerPadding, slotSize - 2 * innerPadding);
        nvgFillColor(vg, nvgRGBA(75, 75, 75, 255, NVGColor.malloc(stack))); // Medium gray
        nvgFill(vg);
        
        // Beveled border for 3D effect
        drawMinecraftBeveledBorder(vg, slotX, slotY, slotSize, slotSize, stack, false);
    }
    
    /**
     * Determines if a recipe requires a workbench (3x3 crafting grid).
     * @param recipe The recipe to check
     * @return true if this recipe should always display as 3x3 grid
     */
    private boolean requiresWorkbench(Recipe recipe) {
        String recipeId = recipe.getId();
        return recipeId.equals("wooden_pickaxe") ||
               recipeId.equals("pine_wooden_pickaxe") ||
               recipeId.equals("elm_wooden_pickaxe") ||
               recipeId.equals("wooden_axe") ||
               recipeId.equals("pine_wooden_axe") ||
               recipeId.equals("elm_wooden_axe");
    }
    
    // Utility method for creating NanoVG colors
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        color.r(r / 255.0f);
        color.g(g / 255.0f);
        color.b(b / 255.0f);
        color.a(a / 255.0f);
        return color;
    }
}