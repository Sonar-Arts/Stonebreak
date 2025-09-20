package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of recipe category buttons and category selection UI.
 */
public class RecipeCategoryRenderer {

    private RecipeCategoryRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws all category buttons in a horizontal row.
     *
     * @param uiRenderer UI renderer instance
     * @param inputHandler Input handler for mouse position
     * @param x X position of button row
     * @param y Y position of button row
     * @param width Total width available for buttons
     * @param categories Array of category names
     * @param selectedCategory Currently selected category
     */
    public static void drawCategoryButtons(UIRenderer uiRenderer, InputHandler inputHandler, int x, int y, int width, String[] categories, String selectedCategory) {
        int buttonWidth = (width - (categories.length - 1) * 5) / categories.length;

        for (int i = 0; i < categories.length; i++) {
            int buttonX = x + i * (buttonWidth + 5);
            boolean isSelected = categories[i].equals(selectedCategory);
            drawCategoryButton(uiRenderer, inputHandler, buttonX, y, buttonWidth, 25, categories[i], isSelected);
        }
    }

    /**
     * Draws a single category button with hover and selection states.
     *
     * @param uiRenderer UI renderer instance
     * @param inputHandler Input handler for mouse position
     * @param x X position of button
     * @param y Y position of button
     * @param width Button width
     * @param height Button height
     * @param text Button text
     * @param selected Whether this button is selected
     */
    public static void drawCategoryButton(UIRenderer uiRenderer, InputHandler inputHandler, int x, int y, int width, int height, String text, boolean selected) {
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