package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of the recipe grid layout with recipe cards and empty states.
 */
public class RecipeGridRenderer {

    private static final int RECIPE_DISPLAY_HEIGHT = 80;
    private static final int ITEM_SLOT_SIZE = 40;

    private RecipeGridRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Container class for returning both hovered recipe and item stack.
     */
    public static class GridHoverResult {
        public final Recipe hoveredRecipe;
        public final ItemStack hoveredItemStack;

        public GridHoverResult(Recipe hoveredRecipe, ItemStack hoveredItemStack) {
            this.hoveredRecipe = hoveredRecipe;
            this.hoveredItemStack = hoveredItemStack;
        }
    }

    /**
     * Draws the complete recipe grid with scrolling support.
     *
     * @param uiRenderer UI renderer instance
     * @param renderer Main renderer instance
     * @param inputHandler Input handler for hover detection
     * @param x X position of grid
     * @param y Y position of grid
     * @param width Grid width
     * @param height Grid height
     * @param filteredRecipes List of recipes to display
     * @param scrollOffset Current scroll offset
     * @return GridHoverResult containing hovered recipe and item stack
     */
    public static GridHoverResult drawRecipeGrid(UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, int x, int y, int width, int height, List<Recipe> filteredRecipes, int scrollOffset) {
        if (filteredRecipes.isEmpty()) {
            drawEmptyMessage(uiRenderer, x + width / 2, y + height / 2, "No recipes found");
            return new GridHoverResult(null, null);
        }

        // Calculate grid layout
        int recipesPerRow = Math.max(1, width / (RECIPE_DISPLAY_HEIGHT + 10));
        int recipeRows = (int) Math.ceil((double) filteredRecipes.size() / recipesPerRow);
        int maxVisibleRows = height / (RECIPE_DISPLAY_HEIGHT + 10);

        Recipe hoveredRecipe = null;
        ItemStack hoveredItemStack = null;

        // Draw visible recipes
        for (int row = 0; row < maxVisibleRows && row + scrollOffset < recipeRows; row++) {
            for (int col = 0; col < recipesPerRow; col++) {
                int recipeIndex = (row + scrollOffset) * recipesPerRow + col;
                if (recipeIndex >= filteredRecipes.size()) break;

                Recipe recipe = filteredRecipes.get(recipeIndex);
                int recipeX = x + col * (RECIPE_DISPLAY_HEIGHT + 10);
                int recipeY = y + row * (RECIPE_DISPLAY_HEIGHT + 10);

                GridHoverResult cardResult = drawRecipeCard(uiRenderer, renderer, inputHandler, recipe, recipeX, recipeY, RECIPE_DISPLAY_HEIGHT - 10, RECIPE_DISPLAY_HEIGHT - 10);
                if (cardResult.hoveredRecipe != null) {
                    hoveredRecipe = cardResult.hoveredRecipe;
                }
                if (cardResult.hoveredItemStack != null) {
                    hoveredItemStack = cardResult.hoveredItemStack;
                }
            }
        }

        return new GridHoverResult(hoveredRecipe, hoveredItemStack);
    }

    /**
     * Draws a single recipe card with item display and hover effects.
     *
     * @param uiRenderer UI renderer instance
     * @param renderer Main renderer instance
     * @param inputHandler Input handler for hover detection
     * @param recipe Recipe to display
     * @param x X position of card
     * @param y Y position of card
     * @param width Card width
     * @param height Card height
     * @return GridHoverResult containing hovered recipe and item stack
     */
    public static GridHoverResult drawRecipeCard(UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, Recipe recipe, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= x && mousePos.x <= x + width &&
                               mousePos.y >= y && mousePos.y <= y + height;

            Recipe hoveredRecipe = null;
            ItemStack hoveredItemStack = null;

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

                    ItemStack slotHovered = RecipeSlotRenderer.drawRecipeSlot(output, itemX, itemY, itemSize, uiRenderer, renderer, inputHandler);
                    if (slotHovered != null) {
                        hoveredItemStack = slotHovered;
                    }

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

            return new GridHoverResult(hoveredRecipe, hoveredItemStack);
        }
    }

    /**
     * Draws a message when no recipes are found or grid is empty.
     *
     * @param uiRenderer UI renderer instance
     * @param centerX Center X position for message
     * @param centerY Center Y position for message
     * @param message Message text to display
     */
    public static void drawEmptyMessage(UIRenderer uiRenderer, float centerX, float centerY, String message) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 18);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, message);
        }
    }

    /**
     * Calculates the maximum scroll offset for the given recipes and grid dimensions.
     *
     * @param width Grid width
     * @param height Grid height
     * @param recipeCount Total number of recipes
     * @return Maximum scroll offset
     */
    public static int calculateMaxScrollOffset(int width, int height, int recipeCount) {
        int recipesPerRow = Math.max(1, width / (RECIPE_DISPLAY_HEIGHT + 10));
        int recipeRows = (int) Math.ceil((double) recipeCount / recipesPerRow);
        int maxVisibleRows = height / (RECIPE_DISPLAY_HEIGHT + 10);
        return Math.max(0, recipeRows - maxVisibleRows);
    }

    /**
     * Utility method for creating NanoVG RGBA colors.
     *
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @param a Alpha component (0-255)
     * @param color NVGColor instance to populate
     * @return The populated NVGColor instance
     */
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        color.r(r / 255.0f);
        color.g(g / 255.0f);
        color.b(b / 255.0f);
        color.a(a / 255.0f);
        return color;
    }
}