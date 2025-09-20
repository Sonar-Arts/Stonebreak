package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class InventoryCraftingRenderer {

    private InventoryCraftingRenderer() {
        // Utility class - prevent instantiation
    }

    public static void drawCraftingArrow(UIRenderer uiRenderer, float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgBeginPath(vg);
            nvgFillColor(vg, nvgRGBA(200, 200, 200, 220, NVGColor.malloc(stack)));
            // Simple arrow: ->
            nvgMoveTo(vg, x, y + height / 2);
            nvgLineTo(vg, x + width - (width / 3), y + height / 2);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);

            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width - (width / 3) - (height / 4), y + height / 4);
            nvgLineTo(vg, x + width, y + height / 2);
            nvgLineTo(vg, x + width - (width / 3) - (height / 4), y + height * 3 / 4);
            nvgStroke(vg);
        }
    }

    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}