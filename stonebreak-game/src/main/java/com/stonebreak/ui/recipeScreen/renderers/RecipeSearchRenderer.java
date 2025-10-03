package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of the recipe search bar with typing states and visual feedback.
 */
public class RecipeSearchRenderer {

    private RecipeSearchRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws the recipe search bar with active state, typing cursor, and placeholder text.
     *
     * @param uiRenderer UI renderer instance
     * @param x X position of search bar
     * @param y Y position of search bar
     * @param width Search bar width
     * @param height Search bar height
     * @param searchText Current search text
     * @param searchActive Whether the search bar is active/focused
     * @param isTyping Whether the user is actively typing
     */
    public static void drawSearchBar(UIRenderer uiRenderer, int x, int y, int width, int height, String searchText, boolean searchActive, boolean isTyping) {
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

            RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

            if (searchText.isEmpty() && !searchActive && !isTyping) {
                nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack))); // Placeholder color
            } else {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack))); // Normal text color
            }
            nvgText(vg, x + 10, y + height / 2.0f, displayText);
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