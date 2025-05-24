package com.stonebreak;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER; // For alignment constants
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_TOP;

public class RecipeBookScreen {

    private boolean visible;
    private final RecipeManager recipeManager;
    private final UIRenderer uiRenderer; // Added UIRenderer
    private List<CraftingRecipe> recipes;
    // private int scrollOffset = 0; // For recipe list scrolling - REMOVED FOR NOW

    // Dimensions and positions for UI elements - these are placeholders
    private final int bookX = 100, bookY = 50, bookWidth = 600, bookHeight = 400;
    private final int closeButtonX = bookX + bookWidth - 30, closeButtonY = bookY + 10, closeButtonSize = 20;
    private final int recipeEntryHeight = 60; // Height of each recipe entry
    private final int maxVisibleRecipes; // Calculated based on book height and entry height

    // Constructor updated to accept UIRenderer
    public RecipeBookScreen(UIRenderer uiRenderer) {
        this.visible = false;
        this.uiRenderer = uiRenderer; // Store UIRenderer instance
        // Calculate maxVisibleRecipes, ensure it's at least 1 if bookHeight is very small
        this.maxVisibleRecipes = Math.max(1, (bookHeight - 60) / recipeEntryHeight);
        this.recipeManager = RecipeManager.getInstance();
        this.recipes = recipeManager.getAllRecipes();
    }

    public boolean isVisible() {
        return visible;
    }

    public void show() {
        this.visible = true;
        this.recipes = recipeManager.getAllRecipes(); // Refresh recipes when shown
        // this.scrollOffset = 0; // Reset scroll on show - REMOVED FOR NOW
    }

    public void hide() {
        this.visible = false;
    }

    public void toggleVisibility() {
        if (visible) {
            hide();
        } else {
            show();
        }
    }

    public void handleInput(InputHandler inputHandler, Game game) {
        if (!visible) {
            return;
        }

        // Handle closing the recipe book (e.g., clicking a close button or escape key)
        if (inputHandler.isMouseButtonPressed(0)) { // Assuming 0 is left mouse button
            int mouseX = (int) inputHandler.getMousePosition().x;
            int mouseY = (int) inputHandler.getMousePosition().y;

            // Check for close button click
            if (mouseX >= closeButtonX && mouseX <= closeButtonX + closeButtonSize &&
                mouseY >= closeButtonY && mouseY <= closeButtonY + closeButtonSize) {
                hide();
                inputHandler.consumeMouseButtonPress(0); // Consume the click
            }
        }
        
        // Scroll handling removed for now as InputHandler.getScrollY() is not available.
        // The game's main scroll handler updates hotbar; RecipeBook needs its own logic or InputHandler change.

        // Potentially other input handling for the recipe book itself (e.g., pagination)
    }

    // Modified render signature - no longer needs Font or mainRenderer
    public void render(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }
        // UIRenderer.beginFrame would be called by the Game loop before calling this screen's render
        // Or if each screen is fully independent:
        this.uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);


        // Render background for the recipe book
        this.uiRenderer.renderQuad((float)bookX, (float)bookY, (float)bookWidth, (float)bookHeight, 0.2f, 0.2f, 0.2f, 0.8f); // Semi-transparent dark background

        // Render Title
        this.uiRenderer.drawString("Recipe Book", (float)bookX + 10, (float)bookY + 15, UIRenderer.FONT_SANS_BOLD, 20, 255, 255, 255, 255, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        
        // Render Close button (simple 'X')
        this.uiRenderer.renderQuad((float)closeButtonX, (float)closeButtonY, (float)closeButtonSize, (float)closeButtonSize, 0.8f, 0.1f, 0.1f, 1.0f);
        this.uiRenderer.drawString("X", (float)closeButtonX + closeButtonSize / 2.0f, (float)closeButtonY + closeButtonSize / 2.0f, UIRenderer.FONT_SANS_BOLD, 18, 255, 255, 255, 255, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);


        // Render recipes - No scrolling for now
        // int currentY = bookY + 40 - scrollOffset;
        int recipesToDisplay = Math.min(recipes.size(), maxVisibleRecipes);


        for (int i = 0; i < recipesToDisplay; i++) { // Iterate only up to maxVisibleRecipes
            CraftingRecipe recipe = recipes.get(i);
            // int recipeDisplayY = bookY + 40 + (i * recipeEntryHeight) - scrollOffset;
            int recipeDisplayY = bookY + 40 + (i * recipeEntryHeight);


            // Only render if within the visible area of the book (still relevant even without scroll if many recipes fit)
            if (recipeDisplayY + recipeEntryHeight > bookY + 40 && recipeDisplayY < bookY + bookHeight - 20) {
                renderRecipe(recipe, bookX + 10, recipeDisplayY, bookWidth - 20); // Removed uiRenderer, font, shaderProgram args
            }
        }
        
        // this.uiRenderer.endFrame(); // If each screen is fully independent
        // Basic Scrollbar (Conceptual) - REMOVED FOR NOW
        // if (recipes.size() > maxVisibleRecipes) {
        //     float scrollBarHeightPercentage = (float)maxVisibleRecipes / recipes.size();
        //     float scrollBarHeight = scrollBarHeightPercentage * (bookHeight - 60); // 60 for header/footer
        //     if (scrollBarHeight < 20) scrollBarHeight = 20; // Min height

        //     float totalScrollableHeight = (recipes.size() - maxVisibleRecipes) * recipeEntryHeight;
        //     float scrollPositionRatio = 0;
        //     if (totalScrollableHeight > 0) {
        //          scrollPositionRatio = (float)scrollOffset / totalScrollableHeight;
        //     }
           
        //     float scrollBarY = bookY + 40 + scrollPositionRatio * ((bookHeight - 60) - scrollBarHeight);

        //     uiRenderer.renderQuad((float)bookX + bookWidth - 20, (float)bookY + 40, 10f, (float)bookHeight - 60, 0.1f, 0.1f, 0.1f, 0.8f); // Scroll track
        //     uiRenderer.renderQuad((float)bookX + bookWidth - 18, scrollBarY, 6f, scrollBarHeight, 0.5f, 0.5f, 0.5f, 1.0f); // Scroll thumb
        // }
    }

    // Removed UIRenderer, Font, ShaderProgram parameters
    private void renderRecipe(CraftingRecipe recipe, int x, int y, int width) {
        ItemStack result = recipe.getOutput();
        // Display result: Icon + Name (Count)
        // Placeholder for icon rendering - uiRenderer.drawTexturedQuadUI can be used if item icons are part of a texture.
        // For now, drawing a colored quad.
        this.uiRenderer.renderQuad((float)x + 5, (float)y + 5, 32f, 32f, 0.5f, 0.5f, 0.5f, 0.5f); // Placeholder for item icon
        String itemName = (result.getItemType() != null) ? result.getItemType().name() : "Unknown Item";
        this.uiRenderer.drawString(itemName + " (" + result.getCount() + ")",(float)x + 45, (float)y + 5 + 32/2.0f , UIRenderer.FONT_SANS, 16, 255, 255, 255, 255, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

        // Display ingredients
        int ingredientsX = x + 5;
        int ingredientsY = y + 40; // Below the result item
        
        this.uiRenderer.drawString("Requires:", (float)ingredientsX, (float)ingredientsY - 15, UIRenderer.FONT_SANS, 14, 200, 200, 200, 255, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);

        if (!recipe.isShapeless()) { // isShaped() -> !isShapeless()
            // Display shaped recipe ingredients in a grid
            ItemStack[] shape = recipe.getShapedInputPattern(); // getShape() -> getShapedInputPattern()
            int gridDimension = recipe.getGridDimension(); // getWidth()/getHeight() -> getGridDimension()
            int cellSize = 20; // Size of each cell in the grid
            for (int r = 0; r < gridDimension; r++) { // Use gridDimension for height
                for (int c = 0; c < gridDimension; c++) { // Use gridDimension for width
                    ItemStack ingredient = shape[r * gridDimension + c]; // Use gridDimension
                    if (ingredient != null && ingredient.getItemType() != ItemType.AIR) {
                        // Placeholder for item icon
                        this.uiRenderer.renderQuad((float)(ingredientsX + c * cellSize), (float)(ingredientsY + r * cellSize), (float)cellSize - 2, (float)cellSize - 2, 0.4f, 0.4f, 0.4f, 0.5f);
                        // Potentially draw small text for item type if no icon
                    } else {
                         this.uiRenderer.renderQuad((float)(ingredientsX + c * cellSize), (float)(ingredientsY + r * cellSize), (float)cellSize - 2, (float)cellSize - 2, 0.25f, 0.25f, 0.25f, 0.5f); // Empty slot
                    }
                }
            }
             // ingredientsX += gridDimension * cellSize + 10; // Move to the right for next text, if there was other info. Not strictly needed here.
        } else {
            // Display shapeless recipe ingredients as a list
            List<ItemStack> ingredients = recipe.getShapelessIngredients(); // getIngredients() -> getShapelessIngredients()
            int currentIngredientX = ingredientsX;
            int currentIngredientY = ingredientsY; // Allow Y to change for wrapping
            for (ItemStack ingredient : ingredients) {
                if (ingredient != null && ingredient.getItemType() != ItemType.AIR) {
                     // Placeholder for item icon
                     this.uiRenderer.renderQuad((float)currentIngredientX, (float)currentIngredientY, 18f, 18f, 0.4f, 0.4f, 0.4f, 0.5f);
                    currentIngredientX += 22; // Next ingredient position
                    if (currentIngredientX > x + width - 40) { // Wrap if too wide
                        currentIngredientX = ingredientsX;
                        currentIngredientY += 22;
                    }
                }
            }
        }
        // Draw a separator line for the next recipe (visual only)
        this.uiRenderer.renderQuad((float)x, (float)(y + recipeEntryHeight -1) , (float)width, 1f, 0.4f, 0.4f, 0.4f, 0.8f);
    }
}