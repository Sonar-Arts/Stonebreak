package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Modern inventory panel renderer with professional styling and visual depth.
 * Features gradient backgrounds, subtle shadows, and contemporary design elements.
 */
public class InventoryPanelRenderer {

    private InventoryPanelRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws the main inventory panel with modern styling including gradients, shadows, and depth effects.
     */
    public static void drawInventoryPanel(UIRenderer uiRenderer, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Drop shadow for depth
            drawPanelShadow(vg, x, y, width, height, stack);

            // Main panel background with gradient
            drawPanelBackground(vg, x, y, width, height, stack);

            // Header section with subtle gradient
            drawPanelHeader(vg, x, y, width, stack);

            // Main border with highlight effect
            drawPanelBorder(vg, x, y, width, height, stack);

            // Inner highlight for 3D effect
            drawInnerHighlight(vg, x, y, width, height, stack);
        }
    }

    /**
     * Draws modern section titles with enhanced typography and visual hierarchy.
     */
    public static void drawInventoryTitle(UIRenderer uiRenderer, float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Set font and alignment
            RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_MEDIUM);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

            // Draw text shadow for depth
            nvgFillColor(vg, InventoryTheme.Text.SHADOW.toNVG(stack));
            nvgText(vg, centerX + 1, centerY + 1, title);

            // Draw main title text with accent color
            nvgFillColor(vg, InventoryTheme.Text.ACCENT.toNVG(stack));
            nvgText(vg, centerX, centerY, title);

            // Add subtle glow effect
            nvgGlobalAlpha(vg, 0.3f);
            nvgFillColor(vg, InventoryTheme.Text.ACCENT.toNVG(stack));
            nvgText(vg, centerX, centerY, title);
            nvgGlobalAlpha(vg, 1.0f);
        }
    }

    /**
     * Draws a subtle drop shadow behind the panel for depth.
     */
    private static void drawPanelShadow(long vg, int x, int y, int width, int height, MemoryStack stack) {
        float offset = InventoryTheme.Measurements.SHADOW_OFFSET;
        float blur = InventoryTheme.Measurements.SHADOW_BLUR;

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + offset, y + offset, width, height, InventoryTheme.Measurements.CORNER_RADIUS_LARGE);

        // Create shadow paint with blur
        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, x + offset, y + offset, width, height,
                      InventoryTheme.Measurements.CORNER_RADIUS_LARGE, blur,
                      InventoryTheme.Panel.SHADOW.toNVG(stack),
                      nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)),
                      shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Draws the main panel background with a subtle gradient.
     */
    private static void drawPanelBackground(long vg, int x, int y, int width, int height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, InventoryTheme.Measurements.CORNER_RADIUS_LARGE);

        // Create vertical gradient from top to bottom
        NVGPaint gradientPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + height,
                         InventoryTheme.Panel.BACKGROUND_PRIMARY.toNVG(stack),
                         InventoryTheme.Panel.BACKGROUND_SECONDARY.toNVG(stack),
                         gradientPaint);

        nvgFillPaint(vg, gradientPaint);
        nvgFill(vg);
    }

    /**
     * Draws a subtle header gradient at the top of the panel.
     */
    private static void drawPanelHeader(long vg, int x, int y, int width, MemoryStack stack) {
        float headerHeight = 60.0f;

        nvgBeginPath(vg);
        nvgRoundedRectVarying(vg, x + 2, y + 2, width - 4, headerHeight,
                             InventoryTheme.Measurements.CORNER_RADIUS_LARGE - 2,
                             InventoryTheme.Measurements.CORNER_RADIUS_LARGE - 2,
                             0, 0);

        NVGPaint headerGradient = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + headerHeight,
                         InventoryTheme.Panel.HEADER_GRADIENT_START.toNVG(stack),
                         InventoryTheme.Panel.HEADER_GRADIENT_END.toNVG(stack),
                         headerGradient);

        nvgFillPaint(vg, headerGradient);
        nvgFill(vg);
    }

    /**
     * Draws the main panel border with highlight effect.
     */
    private static void drawPanelBorder(long vg, int x, int y, int width, int height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, InventoryTheme.Measurements.CORNER_RADIUS_LARGE);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THICK);
        nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_HIGHLIGHT.toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Draws an inner highlight for 3D depth effect.
     */
    private static void drawInnerHighlight(long vg, int x, int y, int width, int height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 1, y + 1, width - 2, height - 2, InventoryTheme.Measurements.CORNER_RADIUS_LARGE - 1);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_PRIMARY.withAlpha(80).toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Legacy helper method for backward compatibility
     */
    @Deprecated
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}