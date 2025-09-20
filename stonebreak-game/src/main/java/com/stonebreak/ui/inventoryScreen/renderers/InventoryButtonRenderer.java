package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class InventoryButtonRenderer {

    private InventoryButtonRenderer() {
        // Utility class - prevent instantiation
    }

    public static void drawRecipeButton(UIRenderer uiRenderer, InputHandler inputHandler, float x, float y, float w, float h, String text) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            NVGColor color = NVGColor.malloc(stack);

            // Button background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, w, h, 4);
            boolean isHovering = inputHandler.getMousePosition().x >= x && inputHandler.getMousePosition().x <= x + w &&
                                 inputHandler.getMousePosition().y >= y && inputHandler.getMousePosition().y <= y + h;
            if (isHovering) {
                nvgFillColor(vg, nvgRGBA(100, 120, 140, 255, color)); // Hover color
            } else {
                nvgFillColor(vg, nvgRGBA(80, 100, 120, 255, color)); // Normal color
            }
            nvgFill(vg);

            // Button border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 0.5f, y + 0.5f, w - 1, h - 1, 3.5f);
            nvgStrokeColor(vg, nvgRGBA(150, 170, 190, 255, color));
            nvgStrokeWidth(vg, 1.0f);
            nvgStroke(vg);

            // Button text
            nvgFontSize(vg, 18); // Use a reasonable font size
            nvgFontFace(vg, "sans"); // Or "minecraft" if available and preferred
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, color));
            nvgText(vg, x + w / 2, y + h / 2, text);
        }
    }

    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}