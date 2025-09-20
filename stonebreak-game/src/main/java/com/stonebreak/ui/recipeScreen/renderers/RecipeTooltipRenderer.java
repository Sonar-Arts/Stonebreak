package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryTooltipRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of recipe-specific tooltips.
 * Extends InventoryTooltipRenderer functionality with recipe-specific styling.
 */
public class RecipeTooltipRenderer {

    private RecipeTooltipRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws a standard item tooltip using inventory tooltip renderer.
     * Delegates to InventoryTooltipRenderer to avoid code duplication.
     *
     * @param uiRenderer UI renderer instance
     * @param itemName Name of the item to display
     * @param x X position of tooltip
     * @param y Y position of tooltip
     */
    public static void drawItemTooltip(UIRenderer uiRenderer, String itemName, float x, float y) {
        // Delegate to existing inventory tooltip renderer to eliminate DRY violation
        InventoryTooltipRenderer.drawItemTooltip(uiRenderer, itemName, x, y, Game.getWindowWidth(), Game.getWindowHeight());
    }

    /**
     * Draws a recipe-specific tooltip with enhanced styling for recipe information.
     * Uses a simpler, recipe-focused design that matches the original RecipeBookScreen styling.
     *
     * @param uiRenderer UI renderer instance
     * @param itemName Name of the recipe/item to display
     * @param x X position of tooltip
     * @param y Y position of tooltip
     */
    public static void drawRecipeTooltip(UIRenderer uiRenderer, String itemName, float x, float y) {
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