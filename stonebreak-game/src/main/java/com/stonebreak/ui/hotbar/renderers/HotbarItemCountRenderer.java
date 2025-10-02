package com.stonebreak.ui.hotbar.renderers;

import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer for item count numbers in hotbar slots.
 * Handles count text rendering with consistent styling and positioning.
 */
public class HotbarItemCountRenderer {

    private final long vg;

    public HotbarItemCountRenderer(long vg) {
        this.vg = vg;
    }

    /**
     * Renders the item count in the bottom-right corner of a slot.
     */
    public void renderItemCount(int count, HotbarLayoutCalculator.SlotPosition position) {
        if (count <= 1) {
            return; // Don't render count for single items
        }

        try (MemoryStack stack = stackPush()) {
            String countText = String.valueOf(count);

            // Set up font using consistent recipe font system
            RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

            // Calculate position (bottom-right corner with padding)
            float padding = HotbarTheme.Measurements.PADDING_SMALL / 2.0f;
            float textX = position.x + position.width - padding;
            float textY = position.y + position.height - padding;

            // Render text shadow for better readability
            renderCountShadow(countText, textX, textY, stack);

            // Render main count text
            renderCountText(countText, textX, textY, stack);
        }
    }

    /**
     * Renders item count with background for better visibility.
     */
    public void renderItemCountWithBackground(int count, HotbarLayoutCalculator.SlotPosition position) {
        if (count <= 1) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            String countText = String.valueOf(count);

            // Measure text to create background using consistent font
            RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);

            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, countText, bounds);

            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];

            // Calculate background dimensions and position
            float bgPadding = 2.0f;
            float bgWidth = textWidth + (bgPadding * 2);
            float bgHeight = textHeight + (bgPadding * 2);

            float padding = HotbarTheme.Measurements.PADDING_SMALL / 2.0f;
            float bgX = position.x + position.width - bgWidth - padding;
            float bgY = position.y + position.height - bgHeight - padding;

            // Render semi-transparent background
            renderCountBackground(bgX, bgY, bgWidth, bgHeight, stack);

            // Position text centered in background
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            float textX = bgX + bgWidth / 2.0f;
            float textY = bgY + bgHeight / 2.0f;

            // Render count text
            renderCountText(countText, textX, textY, stack);
        }
    }

    /**
     * Renders item count with animated scaling for pickup effects.
     */
    public void renderAnimatedItemCount(int count, HotbarLayoutCalculator.SlotPosition position,
                                       float animationProgress, float scale) {
        if (count <= 1) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            String countText = String.valueOf(count);

            // Save current transform
            nvgSave(vg);

            // Calculate position
            float padding = HotbarTheme.Measurements.PADDING_SMALL / 2.0f;
            float baseX = position.x + position.width - padding;
            float baseY = position.y + position.height - padding;

            // Apply scaling animation
            nvgTranslate(vg, baseX, baseY);
            nvgScale(vg, scale, scale);
            nvgTranslate(vg, -baseX, -baseY);

            // Set up font using consistent recipe font system with scaling
            RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL * scale);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

            // Calculate alpha based on animation progress
            float alpha = Math.max(0.0f, Math.min(1.0f, 1.0f - animationProgress));

            // Render with animation effects
            renderAnimatedCountShadow(countText, baseX, baseY, alpha, stack);
            renderAnimatedCountText(countText, baseX, baseY, alpha, stack);

            // Restore transform
            nvgRestore(vg);
        }
    }

    /**
     * Renders the count text shadow for readability.
     */
    private void renderCountShadow(String text, float x, float y, MemoryStack stack) {
        nvgFillColor(vg, HotbarTheme.Text.COUNT_SHADOW.toNVG(stack));
        nvgText(vg, x + 1, y + 1, text);
    }

    /**
     * Renders the main count text.
     */
    private void renderCountText(String text, float x, float y, MemoryStack stack) {
        nvgFillColor(vg, HotbarTheme.Text.COUNT.toNVG(stack));
        nvgText(vg, x, y, text);
    }

    /**
     * Renders a semi-transparent background for count text.
     */
    private void renderCountBackground(float x, float y, float width, float height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, HotbarTheme.Measurements.CORNER_RADIUS_SMALL / 2.0f);

        // Semi-transparent dark background
        HotbarTheme.ColorRGBA bgColor = new HotbarTheme.ColorRGBA(0, 0, 0, 120);
        nvgFillColor(vg, bgColor.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders animated count shadow with alpha.
     */
    private void renderAnimatedCountShadow(String text, float x, float y, float alpha, MemoryStack stack) {
        HotbarTheme.ColorRGBA shadowColor = HotbarTheme.Text.COUNT_SHADOW.withAlpha((int)(alpha * HotbarTheme.Text.COUNT_SHADOW.a));
        nvgFillColor(vg, shadowColor.toNVG(stack));
        nvgText(vg, x + 1, y + 1, text);
    }

    /**
     * Renders animated count text with alpha.
     */
    private void renderAnimatedCountText(String text, float x, float y, float alpha, MemoryStack stack) {
        HotbarTheme.ColorRGBA textColor = HotbarTheme.Text.COUNT.withAlpha((int)(alpha * HotbarTheme.Text.COUNT.a));
        nvgFillColor(vg, textColor.toNVG(stack));
        nvgText(vg, x, y, text);
    }

    /**
     * Renders count with different styling for stack limits.
     */
    public void renderStackLimitCount(int count, int maxStack, HotbarLayoutCalculator.SlotPosition position) {
        if (count <= 1) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            String countText = String.valueOf(count);

            // Set up font using consistent recipe font system
            RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

            // Calculate position
            float padding = HotbarTheme.Measurements.PADDING_SMALL / 2.0f;
            float textX = position.x + position.width - padding;
            float textY = position.y + position.height - padding;

            // Choose color based on stack status
            HotbarTheme.ColorRGBA textColor;
            if (count >= maxStack) {
                // Full stack - use accent color
                textColor = HotbarTheme.Text.ACCENT;
            } else if (count >= maxStack * 0.8f) {
                // Near full - use warning color (slightly different)
                textColor = HotbarTheme.Text.COUNT.brighten(0.2f);
            } else {
                // Normal count
                textColor = HotbarTheme.Text.COUNT;
            }

            // Render shadow
            nvgFillColor(vg, HotbarTheme.Text.COUNT_SHADOW.toNVG(stack));
            nvgText(vg, textX + 1, textY + 1, countText);

            // Render main text with status color
            nvgFillColor(vg, textColor.toNVG(stack));
            nvgText(vg, textX, textY, countText);
        }
    }
}