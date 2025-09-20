package com.stonebreak.ui.recipeScreen.renderers;

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
 * Handles rendering of the detailed recipe popup with Minecraft-style design and pagination.
 */
public class RecipePopupRenderer {

    private RecipePopupRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Container class for returning hover information from popup rendering.
     */
    public static class PopupHoverResult {
        public final ItemStack hoveredItemStack;

        public PopupHoverResult(ItemStack hoveredItemStack) {
            this.hoveredItemStack = hoveredItemStack;
        }
    }

    /**
     * Draws the complete recipe popup with Minecraft styling.
     *
     * @param uiRenderer UI renderer instance
     * @param renderer Main renderer instance
     * @param inputHandler Input handler for interactions
     * @param screenWidth Screen width
     * @param screenHeight Screen height
     * @param selectedRecipe Current recipe to display
     * @param currentRecipeVariations List of recipe variations
     * @param currentVariationIndex Current variation index
     * @return PopupHoverResult containing hovered item stack
     */
    public static PopupHoverResult drawRecipePopup(UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, int screenWidth, int screenHeight, Recipe selectedRecipe, List<Recipe> currentRecipeVariations, int currentVariationIndex) {
        if (selectedRecipe == null) {
            System.out.println("ERROR: drawRecipePopup called but selectedRecipe is null");
            return new PopupHoverResult(null);
        }

        // Start new NanoVG frame for popup
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);

        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

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

            // Draw beveled outer border (raised edge)
            RecipeUIStyleRenderer.drawBeveledBorder(vg, popupX, popupY, popupWidth, popupHeight, stack, true);

            // Draw beveled inner border
            RecipeUIStyleRenderer.drawBeveledBorder(vg, popupX + innerPadding, popupY + innerPadding,
                                     popupWidth - 2 * innerPadding, popupHeight - 2 * innerPadding, stack, false);

            // Draw Minecraft-style close button
            drawCloseButton(vg, popupX, popupY, popupWidth, stack);

            // Draw Minecraft-style title section
            drawTitleSection(vg, popupX, popupY, popupWidth, selectedRecipe, stack);

            // Draw pagination controls if there are multiple variations
            int paginationY = popupY + 60;
            int recipeContentStartY = popupY + 80;
            if (currentRecipeVariations.size() > 1) {
                drawPaginationControls(vg, inputHandler, popupX, paginationY, popupWidth, currentRecipeVariations, currentVariationIndex, stack);
                recipeContentStartY = popupY + 100; // Move recipe content down to make room for pagination
            }

            // Draw recipe content with adjusted positioning
            ItemStack hoveredItem = drawDetailedRecipe(uiRenderer, renderer, inputHandler, selectedRecipe, popupX + 20, recipeContentStartY, popupWidth - 40);

            uiRenderer.endFrame();
            return new PopupHoverResult(hoveredItem);
        }
    }

    /**
     * Draws the close button (X) in the top right corner of the popup.
     */
    private static void drawCloseButton(long vg, int popupX, int popupY, int popupWidth, MemoryStack stack) {
        int closeButtonSize = 24;
        int closeButtonX = popupX + popupWidth - closeButtonSize - 16;
        int closeButtonY = popupY + 16;

        // Close button background (wood texture)
        nvgBeginPath(vg);
        nvgRect(vg, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize);
        nvgFillColor(vg, nvgRGBA(139, 92, 46, 255, NVGColor.malloc(stack))); // Medium wood
        nvgFill(vg);

        // Beveled border for 3D button effect
        RecipeUIStyleRenderer.drawBeveledBorder(vg, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, stack, true);

        // Draw X symbol with Minecraft styling
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Text shadow for depth
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 180, NVGColor.malloc(stack)));
        nvgText(vg, closeButtonX + closeButtonSize / 2.0f + 1, closeButtonY + closeButtonSize / 2.0f + 1, "×");

        // Main text (bright red for visibility)
        nvgFillColor(vg, nvgRGBA(255, 85, 85, 255, NVGColor.malloc(stack)));
        nvgText(vg, closeButtonX + closeButtonSize / 2.0f, closeButtonY + closeButtonSize / 2.0f, "×");
    }

    /**
     * Draws the title section with recipe name and Minecraft styling.
     */
    private static void drawTitleSection(long vg, int popupX, int popupY, int popupWidth, Recipe selectedRecipe, MemoryStack stack) {
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
        RecipeUIStyleRenderer.drawBeveledBorder(vg, popupX + 16, popupY + 16, popupWidth - 32, titleBarHeight, stack, false);

        // Main title text with shadow (Minecraft-style golden text)
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_MEDIUM);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Text shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f + 2, popupY + 36 + 2, recipeTitle);

        // Main title (golden/yellow like enchanted text)
        nvgFillColor(vg, nvgRGBA(255, 255, 85, 255, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f, popupY + 36, recipeTitle);
    }

    /**
     * Draws pagination controls for recipe variations.
     */
    private static void drawPaginationControls(long vg, InputHandler inputHandler, int popupX, int paginationY, int popupWidth, List<Recipe> currentRecipeVariations, int currentVariationIndex, MemoryStack stack) {
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
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Text shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f + 1, paginationY + buttonSize / 2.0f + 1, pageText);

        // Main text
        nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f, paginationY + buttonSize / 2.0f, pageText);

        // Recipe variation indicator below page number
        String variationText = "Recipe Variation";
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);

        // Variation text shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f + 1, paginationY + buttonSize + 2 + 1, variationText);

        // Main variation text
        nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
        nvgText(vg, popupX + popupWidth / 2.0f, paginationY + buttonSize + 2, variationText);
    }

    /**
     * Draws a single pagination button.
     */
    private static void drawPaginationButton(long vg, int x, int y, int size, String symbol, boolean enabled, boolean hovered, MemoryStack stack) {
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
        RecipeUIStyleRenderer.drawBeveledBorder(vg, x, y, size, size, stack, enabled);

        // Button symbol
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_SMALL);
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

    /**
     * Draws the detailed recipe with ingredients and output - Minecraft style.
     */
    private static ItemStack drawDetailedRecipe(UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, Recipe recipe, int x, int y, int width) {
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

            RecipeUIStyleRenderer.drawBeveledBorder(vg, startX - 8, startY - 30, ingredientsWidth, ingredientsHeight, stack, false);

            // Draw "Ingredients" label with Minecraft styling
            RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_SMALL);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);

            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, startX + ingredientsWidth / 2.0f - 8 + 1, startY - 25 + 1, "Ingredients");

            // Main text (light color)
            nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
            nvgText(vg, startX + ingredientsWidth / 2.0f - 8, startY - 25, "Ingredients");

            ItemStack hoveredItem = null;

            // Draw input pattern with Minecraft-style slots
            for (int r = 0; r < recipeHeight; r++) {
                List<ItemStack> rowPattern = (pattern != null && r < pattern.size()) ? pattern.get(r) : null;
                for (int c = 0; c < recipeWidth; c++) {
                    ItemStack item = (rowPattern != null && c < rowPattern.size()) ? rowPattern.get(c) : null;
                    int slotX = startX + c * (slotSize + slotSpacing);
                    int slotY = startY + r * (slotSize + slotSpacing);

                    // Always draw the slot background first
                    RecipeUIStyleRenderer.drawInventorySlot(uiRenderer, slotX, slotY, slotSize);

                    // Then draw the item if it exists
                    if (item != null && !item.isEmpty()) {
                        ItemStack slotHovered = RecipeSlotRenderer.drawDetailedRecipeSlot(item, slotX, slotY, slotSize, uiRenderer, renderer, inputHandler);
                        if (slotHovered != null) {
                            hoveredItem = slotHovered;
                        }
                    } else {
                        // Draw subtle dot pattern for empty slots
                        RecipeUIStyleRenderer.drawEmptySlotPattern(vg, slotX, slotY, slotSize, stack);
                    }
                }
            }

            // Draw crafting arrow with background
            drawCraftingArrow(vg, startX, startY, recipeWidth, recipeHeight, slotSize, slotSpacing, stack);

            // Draw output section
            ItemStack outputHovered = drawOutputSection(uiRenderer, renderer, inputHandler, output, startX, startY, recipeWidth, recipeHeight, slotSize, slotSpacing, stack);
            if (outputHovered != null) {
                hoveredItem = outputHovered;
            }

            return hoveredItem;
        }
    }

    /**
     * Draws the crafting arrow between ingredients and output.
     */
    private static void drawCraftingArrow(long vg, int startX, int startY, int recipeWidth, int recipeHeight, int slotSize, int slotSpacing, MemoryStack stack) {
        int arrowX = startX + recipeWidth * (slotSize + slotSpacing) + 30;
        int arrowY = startY + (recipeHeight * (slotSize + slotSpacing) - 40) / 2;
        int arrowWidth = 40;
        int arrowHeight = 24;

        // Arrow background
        nvgBeginPath(vg);
        nvgRect(vg, arrowX, arrowY, arrowWidth, arrowHeight);
        nvgFillColor(vg, nvgRGBA(85, 56, 28, 255, NVGColor.malloc(stack))); // Dark wood
        nvgFill(vg);

        RecipeUIStyleRenderer.drawBeveledBorder(vg, arrowX, arrowY, arrowWidth, arrowHeight, stack, false);

        // Arrow symbol with shadow
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_MEDIUM);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Arrow shadow
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
        nvgText(vg, arrowX + arrowWidth / 2.0f + 1, arrowY + arrowHeight / 2.0f + 1, "→");

        // Main arrow (bright for visibility)
        nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        nvgText(vg, arrowX + arrowWidth / 2.0f, arrowY + arrowHeight / 2.0f, "→");
    }

    /**
     * Draws the output section with result slot and labels.
     */
    private static ItemStack drawOutputSection(UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, ItemStack output, int startX, int startY, int recipeWidth, int recipeHeight, int slotSize, int slotSpacing, MemoryStack stack) {
        long vg = uiRenderer.getVG();
        int arrowWidth = 40;
        int outputX = startX + recipeWidth * (slotSize + slotSpacing) + 30 + arrowWidth + 30;
        int outputY = startY + (recipeHeight * (slotSize + slotSpacing) - slotSize) / 2;

        // Output section background
        int outputSectionWidth = slotSize + 16;
        int outputSectionHeight = slotSize + 40;

        nvgBeginPath(vg);
        nvgRect(vg, outputX - 8, outputY - 8, outputSectionWidth, outputSectionHeight);
        nvgFillColor(vg, nvgRGBA(101, 67, 33, 180, NVGColor.malloc(stack))); // Semi-transparent dark wood
        nvgFill(vg);

        RecipeUIStyleRenderer.drawBeveledBorder(vg, outputX - 8, outputY - 8, outputSectionWidth, outputSectionHeight, stack, false);

        // Draw output slot
        RecipeUIStyleRenderer.drawInventorySlot(uiRenderer, outputX, outputY, slotSize);
        ItemStack hoveredItem = RecipeSlotRenderer.drawDetailedRecipeSlot(output, outputX, outputY, slotSize, uiRenderer, renderer, inputHandler);

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
                RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_MEDIUM);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);

                // Info shadow
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                nvgText(vg, outputX + slotSize / 2.0f + 1, outputY + slotSize + 8 + 1, itemInfo);

                // Main info
                nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
                nvgText(vg, outputX + slotSize / 2.0f, outputY + slotSize + 8, itemInfo);
            }
        }

        return hoveredItem;
    }

    /**
     * Determines if a recipe requires a workbench (3x3 crafting grid).
     */
    private static boolean requiresWorkbench(Recipe recipe) {
        String recipeId = recipe.getId();
        return recipeId.equals("wooden_pickaxe") ||
               recipeId.equals("pine_wooden_pickaxe") ||
               recipeId.equals("elm_wooden_pickaxe") ||
               recipeId.equals("wooden_axe") ||
               recipeId.equals("pine_wooden_axe") ||
               recipeId.equals("elm_wooden_axe");
    }

    /**
     * Utility method for creating NanoVG RGBA colors.
     */
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        color.r(r / 255.0f);
        color.g(g / 255.0f);
        color.b(b / 255.0f);
        color.a(a / 255.0f);
        return color;
    }
}