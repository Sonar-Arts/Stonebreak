package com.stonebreak.ui.hotbar.renderers;

import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer for hotbar tooltips.
 * Handles tooltip backgrounds, text rendering, and positioning with consistent styling.
 */
public class HotbarTooltipRenderer {

    private final long vg;

    public HotbarTooltipRenderer(long vg) {
        this.vg = vg;
    }

    /**
     * Renders a tooltip with fade animation support.
     */
    public void renderTooltip(String text, HotbarLayoutCalculator.TooltipPosition position, float alpha) {
        if (text == null || text.isEmpty() || alpha <= 0.0f) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Render tooltip shadow
            renderTooltipShadow(position, alpha, stack);

            // Render tooltip background
            renderTooltipBackground(position, alpha, stack);

            // Render tooltip border
            renderTooltipBorder(position, alpha, stack);

            // Render tooltip text
            renderTooltipText(text, position, alpha, stack);
        }
    }

    /**
     * Calculates tooltip dimensions for the given text.
     */
    public HotbarLayoutCalculator.TooltipPosition calculateTooltipDimensions(String text, int slotIndex,
                                                                            HotbarLayoutCalculator.HotbarLayout layout,
                                                                            int screenWidth) {
        if (text == null || text.isEmpty()) {
            return new HotbarLayoutCalculator.TooltipPosition(0, 0, 0, 0);
        }

        // Set up font for measurement using consistent recipe font system
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Measure text bounds
        float[] bounds = new float[4];
        nvgTextBounds(vg, 0, 0, text, bounds);

        float textWidth = bounds[2] - bounds[0];
        float textHeight = bounds[3] - bounds[1];

        // Add padding for tooltip background
        float padding = HotbarTheme.Measurements.PADDING_MEDIUM;
        float tooltipWidth = textWidth + (padding * 2);
        float tooltipHeight = textHeight + (padding * 2);

        return HotbarLayoutCalculator.calculateTooltipPosition(slotIndex, layout,
                                                              tooltipWidth, tooltipHeight, screenWidth);
    }

    /**
     * Renders the tooltip drop shadow.
     */
    private void renderTooltipShadow(HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        float shadowOffset = HotbarTheme.Measurements.SHADOW_OFFSET;
        float shadowBlur = HotbarTheme.Measurements.SHADOW_BLUR;

        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            position.x + shadowOffset,
            position.y + shadowOffset,
            position.width,
            position.height,
            HotbarTheme.Measurements.CORNER_RADIUS_SMALL);

        // Create shadow with alpha
        HotbarTheme.ColorRGBA shadowColor = HotbarTheme.Tooltip.SHADOW.withAlpha((int)(alpha * HotbarTheme.Tooltip.SHADOW.a));
        HotbarTheme.ColorRGBA shadowTransparent = shadowColor.withAlpha(0);

        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg,
            (float)position.x, (float)position.y,
            (float)position.width, (float)position.height,
            HotbarTheme.Measurements.CORNER_RADIUS_SMALL,
            shadowBlur,
            shadowColor.toNVG(stack), shadowTransparent.toNVG(stack), shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Renders the tooltip background.
     */
    private void renderTooltipBackground(HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_SMALL);

        HotbarTheme.ColorRGBA bgColor = HotbarTheme.Tooltip.BACKGROUND.withAlpha((int)(alpha * HotbarTheme.Tooltip.BACKGROUND.a));
        nvgFillColor(vg, bgColor.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders the tooltip border.
     */
    private void renderTooltipBorder(HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_SMALL);

        HotbarTheme.ColorRGBA borderColor = HotbarTheme.Tooltip.BORDER.withAlpha((int)(alpha * HotbarTheme.Tooltip.BORDER.a));
        nvgStrokeColor(vg, borderColor.toNVG(stack));
        nvgStrokeWidth(vg, HotbarTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStroke(vg);
    }

    /**
     * Renders the tooltip text.
     */
    private void renderTooltipText(String text, HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        // Set up font using consistent recipe font system
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_LARGE);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        float textX = position.x + position.width / 2.0f;
        float textY = position.y + position.height / 2.0f;

        // Render text shadow first
        HotbarTheme.ColorRGBA shadowColor = HotbarTheme.Tooltip.TEXT_SHADOW.withAlpha((int)(alpha * HotbarTheme.Tooltip.TEXT_SHADOW.a));
        nvgFillColor(vg, shadowColor.toNVG(stack));
        nvgText(vg, textX + 1, textY + 1, text);

        // Render main text
        HotbarTheme.ColorRGBA textColor = HotbarTheme.Tooltip.TEXT_PRIMARY.withAlpha((int)(alpha * HotbarTheme.Tooltip.TEXT_PRIMARY.a));
        nvgFillColor(vg, textColor.toNVG(stack));
        nvgText(vg, textX, textY, text);
    }

    /**
     * Renders a tooltip with rich text support (for future extension).
     */
    public void renderRichTooltip(String title, String[] descriptions, HotbarLayoutCalculator.TooltipPosition position, float alpha) {
        if (title == null || alpha <= 0.0f) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Render background and border (same as simple tooltip)
            renderTooltipShadow(position, alpha, stack);
            renderTooltipBackground(position, alpha, stack);
            renderTooltipBorder(position, alpha, stack);

            // Render title
            renderTooltipTitle(title, position, alpha, stack);

            // Render descriptions if provided
            if (descriptions != null && descriptions.length > 0) {
                renderTooltipDescriptions(descriptions, position, alpha, stack);
            }
        }
    }

    /**
     * Renders the tooltip title with larger font.
     */
    private void renderTooltipTitle(String title, HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        // Use title font for rich tooltip titles
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, RecipeUIStyleRenderer.RecipeFonts.TITLE_SMALL);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);

        float textX = position.x + position.width / 2.0f;
        float textY = position.y + HotbarTheme.Measurements.PADDING_SMALL;

        // Title shadow
        HotbarTheme.ColorRGBA shadowColor = HotbarTheme.Tooltip.TEXT_SHADOW.withAlpha((int)(alpha * HotbarTheme.Tooltip.TEXT_SHADOW.a));
        nvgFillColor(vg, shadowColor.toNVG(stack));
        nvgText(vg, textX + 1, textY + 1, title);

        // Title text
        HotbarTheme.ColorRGBA textColor = HotbarTheme.Tooltip.TEXT_PRIMARY.withAlpha((int)(alpha * HotbarTheme.Tooltip.TEXT_PRIMARY.a));
        nvgFillColor(vg, textColor.toNVG(stack));
        nvgText(vg, textX, textY, title);
    }

    /**
     * Renders tooltip description lines.
     */
    private void renderTooltipDescriptions(String[] descriptions, HotbarLayoutCalculator.TooltipPosition position, float alpha, MemoryStack stack) {
        // Use body font for description lines
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_TOP);

        float textX = position.x + position.width / 2.0f;
        float startY = position.y + HotbarTheme.Measurements.PADDING_SMALL + HotbarTheme.Typography.FONT_SIZE_NORMAL + 4;
        float lineHeight = HotbarTheme.Typography.FONT_SIZE_SMALL + 2;

        HotbarTheme.ColorRGBA descColor = HotbarTheme.Text.SECONDARY.withAlpha((int)(alpha * HotbarTheme.Text.SECONDARY.a));

        for (int i = 0; i < descriptions.length; i++) {
            float textY = startY + (i * lineHeight);
            nvgFillColor(vg, descColor.toNVG(stack));
            nvgText(vg, textX, textY, descriptions[i]);
        }
    }
}