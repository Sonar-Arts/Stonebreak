package com.stonebreak;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

public class RecipeBookScreen {
    // private final Game game; // Removed as unused
    private final UIRenderer uiRenderer;
    private final CraftingManager craftingManager;
    private List<Recipe> recipes;
    private final Font font;

    private boolean visible = false; // Added to control visibility

    private int scrollOffset = 0;
    private final int RECIPE_DISPLAY_HEIGHT = 100;
    private final int ITEM_SLOT_SIZE = 32;
    private final int ITEM_SPACING = 4;
    private final int PADDING = 10;

    private String searchText = "";
    private boolean searchActive = false;

    public RecipeBookScreen(UIRenderer uiRenderer) { // Removed Game game parameter
        // this.game = game; // Removed as unused
        this.uiRenderer = uiRenderer;
        this.craftingManager = Game.getCraftingManager();
        this.font = Game.getInstance().getFont();
        this.recipes = new ArrayList<>();
        // this.visible is false by default
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
        // Update logic for the screen, e.g., handling scroll, search text changes
        // Ensure blinking cursor for search bar updates even if list is empty or font is null.
        // The searchActive state will handle the blinking effect.
    }

    public void render() {
        if (!visible || uiRenderer == null) {
            return;
        }

        Renderer mainGameRenderer = Game.getRenderer(); // Keep for potential future use, but shader isn't used for text
        if (mainGameRenderer == null) {
             System.err.println("RecipeBookScreen: Main Game Renderer is null. Cannot render.");
             return;
        }
        // ShaderProgram shader = mainGameRenderer.getShaderProgram(); // No longer needed for NanoVG text
        // if (shader == null) {
        // System.err.println("RecipeBookScreen: ShaderProgram is null. Cannot render text with old method.");
        // return; // If we were still using font.drawString with shader
        // }

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight(); // Changed to int

        // Begin UIRenderer frame
        // Use a default pixel ratio of 1.0f. UIRenderer might handle HiDPI internally if needed.
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);


        // Darken the game view behind the panel instead of a full screen quad.
        // The main panel will be drawn on top.
        // For now, we achieve a similar effect as InventoryScreen by not drawing a full screen darkening quad,
        // and relying on the panel's own background. If desired, a darkening effect can be added around the panel later.

        // Panel dimensions (similar to InventoryScreen)
        float panelWidth = screenWidth * 0.8f; // 80% of screen width
        float panelHeight = screenHeight * 0.8f; // 80% of screen height
        float panelX = (screenWidth - panelWidth) / 2;
        float panelY = (screenHeight - panelHeight) / 2;

        // Draw panel background
        uiRenderer.renderQuad(panelX, panelY, panelWidth, panelHeight, 0.1f, 0.1f, 0.15f, 0.9f); // Dark semi-transparent panel
        uiRenderer.renderOutline(panelX, panelY, panelWidth, panelHeight, 2f, new float[]{0.3f, 0.3f, 0.4f, 1f});


        // Title within the panel
        float titleFontSize = (font != null) ? font.getFontSize() + 4 : 22f; // Slightly larger for title
        uiRenderer.drawText("Recipe Book", panelX + PADDING, panelY + PADDING + titleFontSize / 2f, "sans-bold", titleFontSize, 1f, 1f, 1f, 1f);


        // Search bar placeholder within the panel
        float searchBarX = panelX + PADDING;
        float searchBarY = panelY + PADDING + titleFontSize + PADDING; // Position below title
        float searchBarWidth = panelWidth - 2 * PADDING;
        float searchBarHeight = 30;
        float searchFontSize = (font != null) ? font.getFontSize() : 18f;
        float textYOffset = searchBarY + searchBarHeight / 2f; // Center text vertically in search bar

        uiRenderer.renderQuad(searchBarX, searchBarY, searchBarWidth, searchBarHeight, 0.2f, 0.2f, 0.2f, 1f);
        String displayText = searchActive ? searchText + "_" : (searchText.isEmpty() ? "Search..." : searchText);
        uiRenderer.drawText(displayText, searchBarX + 5, textYOffset, "sans", searchFontSize, 0.8f, 0.8f, 0.8f, 1f);


        // Recipe list area within the panel
        float listStartY = searchBarY + searchBarHeight + PADDING;
        float listEndY = panelY + panelHeight - PADDING; // Relative to panel
        int recipesToShowPerPage = (int) ((listEndY - listStartY) / RECIPE_DISPLAY_HEIGHT);
        if (recipesToShowPerPage <=0) recipesToShowPerPage = 1; // Ensure at least one if space is tight

        List<Recipe> filteredRecipes = getFilteredRecipes();

        if (filteredRecipes.isEmpty()) {
            float noRecipesFontSize = (font != null) ? font.getFontSize() : 18f;
            uiRenderer.drawText("No recipes found.", panelX + PADDING, listStartY + noRecipesFontSize / 2f, "sans", noRecipesFontSize, 1f, 1f, 1f, 1f);
            uiRenderer.endFrame(); // End UIRenderer frame before returning
            return;
        }

        // Adjust scroll offset if it's out of bounds due to filtering
        int maxScroll = Math.max(0, filteredRecipes.size() - recipesToShowPerPage);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }

        for (int i = 0; i < recipesToShowPerPage; i++) {
            int recipeIndex = scrollOffset + i;
            if (recipeIndex >= filteredRecipes.size()) {
                break;
            }
            Recipe recipe = filteredRecipes.get(recipeIndex);
            renderRecipe(recipe, panelX + PADDING, listStartY + i * RECIPE_DISPLAY_HEIGHT); // Removed shader
        }

        // End UIRenderer frame
        uiRenderer.endFrame();
    }

    private List<Recipe> getFilteredRecipes() {
        if (searchText.isEmpty()) {
            return new ArrayList<>(recipes); // Return a copy
        }
        List<Recipe> filtered = new ArrayList<>();
        String lowerSearchText = searchText.toLowerCase();
        for (Recipe recipe : recipes) {
            BlockType outputType = BlockType.getById(recipe.getOutput().getBlockTypeId());
            if (outputType != null && outputType.getName().toLowerCase().contains(lowerSearchText)) {
                filtered.add(recipe);
            } else if (recipe.getId() != null && recipe.getId().toLowerCase().contains(lowerSearchText)) { // Use getId()
                 filtered.add(recipe);
            }
        }
        return filtered;
    }

    private void renderRecipe(Recipe recipe, float x, float y) { // Removed shader parameter
        List<List<ItemStack>> pattern = recipe.getInputPattern();
        ItemStack output = recipe.getOutput();
        int recipeHeight = recipe.getRecipeHeight();
        int recipeWidth = recipe.getRecipeWidth();
        float baseFontSize = (font != null) ? font.getFontSize() : 18f;

        float currentX = x;
        Renderer mainGameRenderer = Game.getRenderer();

        // Render input pattern
        for (int r = 0; r < recipeHeight; r++) {
            List<ItemStack> rowPattern = (pattern != null && r < pattern.size()) ? pattern.get(r) : null;
            for (int c = 0; c < recipeWidth; c++) {
                ItemStack item = (rowPattern != null && c < rowPattern.size()) ? rowPattern.get(c) : null;
                float itemX = currentX + c * (ITEM_SLOT_SIZE + ITEM_SPACING);
                float itemY = y + r * (ITEM_SLOT_SIZE + ITEM_SPACING);
                
                uiRenderer.renderQuad(itemX, itemY, ITEM_SLOT_SIZE, ITEM_SLOT_SIZE, 0.1f, 0.1f, 0.1f, 1f); // Background for slot
                if (item != null && !item.isEmpty()) {
                    BlockType type = BlockType.getById(item.getBlockTypeId());
                    if (type != null && type != BlockType.AIR && mainGameRenderer != null && uiRenderer != null) {
                        uiRenderer.endFrame(); // End NanoVG frame for 3D rendering
                        mainGameRenderer.draw3DItemInSlot(type, (int)(itemX + 2), (int)(itemY + 2), ITEM_SLOT_SIZE - 4, ITEM_SLOT_SIZE - 4);
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f); // Restart NanoVG frame
                    }
                }
                uiRenderer.renderOutline(itemX, itemY, ITEM_SLOT_SIZE, ITEM_SLOT_SIZE, 1f, new float[]{0.3f, 0.3f, 0.3f, 1f}); // Border for slot
            }
        }
        currentX += recipeWidth * (ITEM_SLOT_SIZE + ITEM_SPACING) + PADDING;

        // Render arrow
        float arrowX = currentX;
        float arrowTextYPos = y + (recipeHeight * (ITEM_SLOT_SIZE + ITEM_SPACING) - baseFontSize) / 2f; // Use baseFontSize for arrow vertical centering
        // Estimate width of "->" for currentX advance
        float arrowTextWidth = uiRenderer.getTextWidth("->", baseFontSize, "sans");


        uiRenderer.drawText("->", arrowX, arrowTextYPos, "sans", baseFontSize, 1f,1f,1f,1f);
        currentX += arrowTextWidth + PADDING;


        // Render output item
        float outputItemX = currentX;
        float outputItemY = y + (recipeHeight * (ITEM_SLOT_SIZE + ITEM_SPACING) - ITEM_SLOT_SIZE) / 2f;
        uiRenderer.renderQuad(outputItemX, outputItemY, ITEM_SLOT_SIZE, ITEM_SLOT_SIZE, 0.1f, 0.1f, 0.1f, 1f); // Background for slot
        if (output != null && !output.isEmpty()) {
            BlockType type = BlockType.getById(output.getBlockTypeId());
            if (type != null && type != BlockType.AIR && mainGameRenderer != null && uiRenderer != null) {
                uiRenderer.endFrame(); // End NanoVG frame for 3D rendering
                mainGameRenderer.draw3DItemInSlot(type, (int)(outputItemX + 2), (int)(outputItemY + 2), ITEM_SLOT_SIZE - 4, ITEM_SLOT_SIZE - 4);
                uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f); // Restart NanoVG frame
            }
            uiRenderer.renderOutline(outputItemX, outputItemY, ITEM_SLOT_SIZE, ITEM_SLOT_SIZE, 1f, new float[]{0.3f, 0.3f, 0.3f, 1f}); // Border for slot

            // String name = (type != null ? type.getName() : "Unknown") + " x" + output.getCount(); // Type already fetched above
            String nameToDisplay = (type != null ? type.getName() : "Unknown") + " x" + output.getCount();
            float nameYPos = outputItemY + (ITEM_SLOT_SIZE - baseFontSize) / 2f; // Use baseFontSize for name vertical centering
            uiRenderer.drawText(nameToDisplay, outputItemX + ITEM_SLOT_SIZE + ITEM_SPACING, nameYPos, "sans", baseFontSize, 1f,1f,1f,1f);
        }
    }

    public void handleInput(InputHandler inputHandler) {
        // Escape key to close RecipeBookScreen is now handled by InputHandler.handleEscapeKey()
        // No need for direct Escape key check here anymore.

        // Existing scroll and search input logic:

        double scrollY = inputHandler.getAndResetScrollY();
        if (scrollY != 0) {
            float listStartY = PADDING + (font != null ? font.getLineHeight() : 20) + PADDING + 30 + PADDING;
            float listEndY = Game.getWindowHeight() - PADDING; // Static access
            int recipesToShowPerPage = (int) ((listEndY - listStartY) / RECIPE_DISPLAY_HEIGHT);
             if (recipesToShowPerPage <=0) recipesToShowPerPage = 1;
            
            int maxScroll = Math.max(0, getFilteredRecipes().size() - recipesToShowPerPage);
            scrollOffset -= (int) scrollY; 
            if (scrollOffset < 0) {
                scrollOffset = 0;
            }
            if (scrollOffset > maxScroll) {
                scrollOffset = maxScroll;
            }
        }

        float searchBarX = PADDING;
        float searchBarY = PADDING + (font != null ? font.getLineHeight() : 20) + PADDING;
        float searchBarWidth = Game.getWindowWidth() - 2 * PADDING; // Static access
        float searchBarHeight = 30;        if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            double mouseX = inputHandler.getMousePosition().x;
            double mouseY = inputHandler.getMousePosition().y;
            searchActive = (mouseX >= searchBarX && mouseX <= searchBarX + searchBarWidth &&
                           mouseY >= searchBarY && mouseY <= searchBarY + searchBarHeight);
        }

        if (searchActive) {
            boolean shiftPressed = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
            for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
                if (inputHandler.isKeyPressedOnce(key)) {
                    searchText += (char) (shiftPressed ? key : key + ('a' - 'A'));
                    scrollOffset = 0;
                }
            }
            for (int key = GLFW.GLFW_KEY_0; key <= GLFW.GLFW_KEY_9; key++) {
                if (inputHandler.isKeyPressedOnce(key)) {
                    if (shiftPressed) {
                        switch (key) {
                            case GLFW.GLFW_KEY_0 -> searchText += ')';
                            case GLFW.GLFW_KEY_1 -> searchText += '!';
                            case GLFW.GLFW_KEY_2 -> searchText += '@';
                            case GLFW.GLFW_KEY_3 -> searchText += '#';
                            case GLFW.GLFW_KEY_4 -> searchText += '$';
                            case GLFW.GLFW_KEY_5 -> searchText += '%';
                            case GLFW.GLFW_KEY_6 -> searchText += '^';
                            case GLFW.GLFW_KEY_7 -> searchText += '&';
                            case GLFW.GLFW_KEY_8 -> searchText += '*';
                            case GLFW.GLFW_KEY_9 -> searchText += '(';
                        }
                    } else {
                        searchText += (char) key;
                    }
                    scrollOffset = 0;
                }
            }
            if (inputHandler.isKeyPressedOnce(GLFW.GLFW_KEY_SPACE)) {
                searchText += " ";
                scrollOffset = 0;
            }
            if (inputHandler.isKeyPressedOnce(GLFW.GLFW_KEY_BACKSPACE) && !searchText.isEmpty()) {
                searchText = searchText.substring(0, searchText.length() - 1);
                scrollOffset = 0;
            }
            if (inputHandler.isKeyPressedOnce(GLFW.GLFW_KEY_ENTER)) {
                searchActive = false;
            }
        }
    }

    public void onOpen() {
        this.visible = true;
        init(); // Load recipes, reset search, scroll
    }

    public void onClose() {
        this.visible = false;
        searchActive = false;
        searchText = "";
        // scrollOffset = 0; // Optionally reset scroll on close
    }
}