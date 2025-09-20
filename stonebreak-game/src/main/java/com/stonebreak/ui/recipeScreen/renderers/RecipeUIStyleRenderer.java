package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Utility renderer for shared Recipe UI components.
 * Contains reusable rendering methods for beveled borders, slots, and UI patterns.
 */
public class RecipeUIStyleRenderer {

    private RecipeUIStyleRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws beveled border for 3D effect.
     *
     * @param vg NanoVG context
     * @param x X position
     * @param y Y position
     * @param width Border width
     * @param height Border height
     * @param stack Memory stack for allocations
     * @param raised Whether the border should appear raised (true) or inset (false)
     */
    public static void drawBeveledBorder(long vg, int x, int y, int width, int height, MemoryStack stack, boolean raised) {
        int borderWidth = 2;

        if (raised) {
            // Light edges (top and left)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + height);
            nvgLineTo(vg, x, y);
            nvgLineTo(vg, x + width, y);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(198, 132, 66, 255, NVGColor.malloc(stack))); // Light border
            nvgStroke(vg);

            // Dark edges (bottom and right)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width, y);
            nvgLineTo(vg, x + width, y + height);
            nvgLineTo(vg, x, y + height);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(66, 44, 22, 255, NVGColor.malloc(stack))); // Dark border
            nvgStroke(vg);
        } else {
            // Dark edges (top and left) - inset appearance
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + height);
            nvgLineTo(vg, x, y);
            nvgLineTo(vg, x + width, y);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(66, 44, 22, 255, NVGColor.malloc(stack))); // Dark border
            nvgStroke(vg);

            // Light edges (bottom and right)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width, y);
            nvgLineTo(vg, x + width, y + height);
            nvgLineTo(vg, x, y + height);
            nvgStrokeWidth(vg, borderWidth);
            nvgStrokeColor(vg, nvgRGBA(198, 132, 66, 255, NVGColor.malloc(stack))); // Light border
            nvgStroke(vg);
        }
    }

    /**
     * Draws inventory slot with dark background and beveled border.
     *
     * @param uiRenderer UI renderer instance
     * @param slotX X position of slot
     * @param slotY Y position of slot
     * @param slotSize Size of the slot
     */
    public static void drawInventorySlot(UIRenderer uiRenderer, int slotX, int slotY, int slotSize) {
        try (MemoryStack stack = stackPush()) {
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
            drawBeveledBorder(vg, slotX, slotY, slotSize, slotSize, stack, false);
        }
    }

    /**
     * Draws subtle dot pattern for empty slots to indicate they can accept items.
     *
     * @param vg NanoVG context
     * @param slotX X position of slot
     * @param slotY Y position of slot
     * @param slotSize Size of the slot
     * @param stack Memory stack for allocations
     */
    public static void drawEmptySlotPattern(long vg, int slotX, int slotY, int slotSize, MemoryStack stack) {
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
    public static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        color.r(r / 255.0f);
        color.g(g / 255.0f);
        color.b(b / 255.0f);
        color.a(a / 255.0f);
        return color;
    }
}