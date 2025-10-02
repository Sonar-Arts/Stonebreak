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
 * Modern tooltip renderer with sleek design and enhanced typography.
 * Features gradient backgrounds, subtle animations, and professional styling.
 */
public class InventoryTooltipRenderer {

    private InventoryTooltipRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws a modern item tooltip with enhanced visual design and improved readability.
     */
    public static void drawItemTooltip(UIRenderer uiRenderer, String itemName, float x, float y, int screenWidth, int screenHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Calculate tooltip dimensions with modern padding
            TooltipDimensions dimensions = calculateTooltipDimensions(vg, itemName);

            // Adjust position to stay within screen bounds
            TooltipPosition position = calculateTooltipPosition(x, y, dimensions, screenWidth, screenHeight);

            // Draw tooltip with modern styling
            drawModernTooltip(vg, position, dimensions, itemName, stack);
        }
    }

    /**
     * Calculates tooltip dimensions based on text content and modern spacing.
     */
    private static TooltipDimensions calculateTooltipDimensions(long vg, String itemName) {
        float padding = InventoryTheme.Measurements.PADDING_MEDIUM;
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_MEDIUM;

        // Measure text with enhanced font
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        float[] bounds = new float[4];
        nvgTextBounds(vg, 0, 0, itemName, bounds);
        float textWidth = bounds[2] - bounds[0];
        float textHeight = bounds[3] - bounds[1];

        // Add extra space for better visual breathing room
        float tooltipWidth = textWidth + (padding * 2.5f);
        float tooltipHeight = textHeight + (padding * 2.0f);

        return new TooltipDimensions(tooltipWidth, tooltipHeight, textWidth, textHeight, padding, cornerRadius);
    }

    /**
     * Calculates optimal tooltip position with improved screen boundary handling.
     */
    private static TooltipPosition calculateTooltipPosition(float x, float y, TooltipDimensions dimensions, int screenWidth, int screenHeight) {
        float margin = InventoryTheme.Measurements.PADDING_LARGE;
        float offsetX = 15.0f; // Offset from cursor
        float offsetY = 15.0f;

        float finalX = x + offsetX;
        float finalY = y + offsetY;

        // Smart positioning to avoid screen edges
        if (finalX + dimensions.width > screenWidth - margin) {
            finalX = x - dimensions.width - offsetX; // Show to the left of cursor
        }
        if (finalY + dimensions.height > screenHeight - margin) {
            finalY = y - dimensions.height - offsetY; // Show above cursor
        }

        // Ensure minimum margins are maintained
        finalX = Math.max(margin, Math.min(finalX, screenWidth - dimensions.width - margin));
        finalY = Math.max(margin, Math.min(finalY, screenHeight - dimensions.height - margin));

        return new TooltipPosition(finalX, finalY);
    }

    /**
     * Draws the complete modern tooltip with all visual effects.
     */
    private static void drawModernTooltip(long vg, TooltipPosition position, TooltipDimensions dimensions, String itemName, MemoryStack stack) {
        // Draw drop shadow for depth
        drawTooltipShadow(vg, position, dimensions, stack);

        // Draw main tooltip background with gradient
        drawTooltipBackground(vg, position, dimensions, stack);

        // Draw border with accent color
        drawTooltipBorder(vg, position, dimensions, stack);

        // Draw inner highlight for depth
        drawTooltipInnerHighlight(vg, position, dimensions, stack);

        // Draw tooltip text with enhanced typography
        drawTooltipText(vg, position, dimensions, itemName, stack);
    }

    /**
     * Draws an enhanced drop shadow with blur effect.
     */
    private static void drawTooltipShadow(long vg, TooltipPosition position, TooltipDimensions dimensions, MemoryStack stack) {
        float shadowOffset = InventoryTheme.Measurements.SHADOW_OFFSET;
        float shadowBlur = InventoryTheme.Measurements.SHADOW_BLUR;

        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x + shadowOffset, position.y + shadowOffset,
                      dimensions.width, dimensions.height, dimensions.cornerRadius);

        // Create shadow paint with gradient blur
        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, position.x + shadowOffset, position.y + shadowOffset,
                      dimensions.width, dimensions.height,
                      dimensions.cornerRadius, shadowBlur,
                      InventoryTheme.Tooltip.SHADOW.toNVG(stack),
                      nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)),
                      shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Draws the tooltip background with subtle gradient.
     */
    private static void drawTooltipBackground(long vg, TooltipPosition position, TooltipDimensions dimensions, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, dimensions.width, dimensions.height, dimensions.cornerRadius);

        // Create subtle vertical gradient for depth
        NVGPaint backgroundPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, position.x, position.y, position.x, position.y + dimensions.height,
                         InventoryTheme.Tooltip.BACKGROUND.brighten(0.1f).toNVG(stack),
                         InventoryTheme.Tooltip.BACKGROUND.darken(0.1f).toNVG(stack),
                         backgroundPaint);

        nvgFillPaint(vg, backgroundPaint);
        nvgFill(vg);
    }

    /**
     * Draws the tooltip border with accent color.
     */
    private static void drawTooltipBorder(long vg, TooltipPosition position, TooltipDimensions dimensions, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, dimensions.width, dimensions.height, dimensions.cornerRadius);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THICK);
        nvgStrokeColor(vg, InventoryTheme.Tooltip.BORDER.toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Draws an inner highlight for 3D depth effect.
     */
    private static void drawTooltipInnerHighlight(long vg, TooltipPosition position, TooltipDimensions dimensions, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x + 1, position.y + 1, dimensions.width - 2, dimensions.height - 2, dimensions.cornerRadius - 1);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStrokeColor(vg, InventoryTheme.Tooltip.HIGHLIGHT_INNER.toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Draws enhanced tooltip text with shadow and glow effects.
     */
    private static void drawTooltipText(long vg, TooltipPosition position, TooltipDimensions dimensions, String itemName, MemoryStack stack) {
        float textX = position.x + dimensions.width / 2;
        float textY = position.y + dimensions.height / 2;

        // Set font and alignment
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Draw enhanced text shadow with multiple layers for depth
        drawTextShadowLayers(vg, textX, textY, itemName, stack);

        // Draw main text with high contrast
        nvgFillColor(vg, InventoryTheme.Tooltip.TEXT_PRIMARY.toNVG(stack));
        nvgText(vg, textX, textY, itemName);

        // Add subtle glow effect for enhanced readability
        nvgGlobalAlpha(vg, 0.4f);
        nvgFillColor(vg, InventoryTheme.Text.ACCENT.toNVG(stack));
        nvgText(vg, textX, textY, itemName);
        nvgGlobalAlpha(vg, 1.0f);
    }

    /**
     * Draws multiple text shadow layers for enhanced depth effect.
     */
    private static void drawTextShadowLayers(long vg, float textX, float textY, String itemName, MemoryStack stack) {
        // Strong shadow for contrast
        nvgFillColor(vg, InventoryTheme.Tooltip.TEXT_SHADOW.toNVG(stack));
        nvgText(vg, textX + 2, textY + 2, itemName);

        // Medium shadow for depth
        nvgGlobalAlpha(vg, 0.7f);
        nvgFillColor(vg, InventoryTheme.Tooltip.TEXT_SHADOW.toNVG(stack));
        nvgText(vg, textX + 1, textY + 1, itemName);
        nvgGlobalAlpha(vg, 1.0f);
    }

    /**
     * Data class for tooltip dimensions and spacing.
     */
    private static class TooltipDimensions {
        final float width, height, textWidth, textHeight, padding, cornerRadius;

        TooltipDimensions(float width, float height, float textWidth, float textHeight, float padding, float cornerRadius) {
            this.width = width;
            this.height = height;
            this.textWidth = textWidth;
            this.textHeight = textHeight;
            this.padding = padding;
            this.cornerRadius = cornerRadius;
        }
    }

    /**
     * Data class for tooltip position.
     */
    private static class TooltipPosition {
        final float x, y;

        TooltipPosition(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Legacy helper method for backward compatibility
     */
    @Deprecated
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}