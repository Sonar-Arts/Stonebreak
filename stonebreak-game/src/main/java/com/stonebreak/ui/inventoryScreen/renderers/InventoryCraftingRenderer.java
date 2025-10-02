package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class InventoryCraftingRenderer {

    private InventoryCraftingRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws a modern styled crafting arrow with theme-based colors and improved visual design.
     * Features refined appearance with proper fill and stroke styling.
     */
    public static void drawCraftingArrow(UIRenderer uiRenderer, float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Create modern arrow path with filled background
            nvgBeginPath(vg);
            float centerY = y + height / 2;
            float arrowTip = x + width;
            float arrowBodyEnd = x + width * 0.7f;
            float arrowHeadHeight = height * 0.4f;

            // Arrow body (rectangle)
            nvgRect(vg, x, centerY - height * 0.1f, width * 0.7f, height * 0.2f);

            // Arrow head (triangle)
            nvgMoveTo(vg, arrowBodyEnd, centerY - arrowHeadHeight / 2);
            nvgLineTo(vg, arrowTip, centerY);
            nvgLineTo(vg, arrowBodyEnd, centerY + arrowHeadHeight / 2);
            nvgClosePath(vg);

            // Fill with modern theme color
            nvgFillColor(vg, InventoryTheme.Crafting.ARROW_FILL.toNVG(stack));
            nvgFill(vg);

            // Add subtle border for definition
            nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
            nvgStrokeColor(vg, InventoryTheme.Crafting.ARROW_BORDER.toNVG(stack));
            nvgStroke(vg);
        }
    }

    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}