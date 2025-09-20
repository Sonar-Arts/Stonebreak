package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class InventoryTooltipRenderer {

    private InventoryTooltipRenderer() {
        // Utility class - prevent instantiation
    }

    public static void drawItemTooltip(UIRenderer uiRenderer, String itemName, float x, float y, int screenWidth, int screenHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float padding = 12.0f;
            float cornerRadius = 6.0f;

            // Measure text with better font
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "minecraft");
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, itemName, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];

            float tooltipWidth = textWidth + 2 * padding;
            float tooltipHeight = textHeight + 2 * padding;

            // Adjust position to stay within screen bounds with margin
            float margin = 10.0f;
            if (x + tooltipWidth > screenWidth - margin) {
                x = screenWidth - tooltipWidth - margin;
            }
            if (y + tooltipHeight > screenHeight - margin) {
                y = screenHeight - tooltipHeight - margin;
            }
            if (x < margin) x = margin;
            if (y < margin) y = margin;

            // Drop shadow for depth
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 3, y + 3, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 100, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Tooltip background with gradient
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(40, 40, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Inner highlight for 3D effect
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 1, y + 1, tooltipWidth - 2, tooltipHeight - 2, cornerRadius - 1);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(80, 80, 100, 120, NVGColor.malloc(stack)));
            nvgStroke(vg);

            // Outer border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 180, NVGColor.malloc(stack)));
            nvgStroke(vg);

            // Text shadow for better readability
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, x + tooltipWidth / 2 + 1, y + tooltipHeight / 2 + 1, itemName);

            // Main tooltip text
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, x + tooltipWidth / 2, y + tooltipHeight / 2, itemName);
        }
    }

    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}