package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class InventoryPanelRenderer {

    private InventoryPanelRenderer() {
        // Utility class - prevent instantiation
    }

    public static void drawInventoryPanel(UIRenderer uiRenderer, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Main panel background
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(50, 50, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Panel border
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }

    public static void drawInventoryTitle(UIRenderer uiRenderer, float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 24);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, title);
        }
    }

    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}