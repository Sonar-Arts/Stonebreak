package com.stonebreak.ui.hotbar.renderers;

import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer for hotbar background elements.
 * Handles background panel, borders, and shadow effects with consistent styling.
 */
public class HotbarBackgroundRenderer {

    private final long vg;

    public HotbarBackgroundRenderer(long vg) {
        this.vg = vg;
    }

    /**
     * Renders the main hotbar background with consistent styling.
     */
    public void renderBackground(HotbarLayoutCalculator.HotbarLayout layout) {
        try (MemoryStack stack = stackPush()) {
            // Render shadow first (behind background)
            renderShadow(layout, stack);

            // Render main background
            renderMainBackground(layout, stack);

            // Render border
            renderBorder(layout, stack);
        }
    }

    /**
     * Renders the drop shadow beneath the hotbar.
     */
    private void renderShadow(HotbarLayoutCalculator.HotbarLayout layout, MemoryStack stack) {
        float shadowOffset = HotbarTheme.Measurements.SHADOW_OFFSET;
        float shadowBlur = HotbarTheme.Measurements.SHADOW_BLUR;

        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            layout.backgroundX + shadowOffset,
            layout.backgroundY + shadowOffset,
            layout.backgroundWidth,
            layout.backgroundHeight,
            HotbarTheme.Measurements.CORNER_RADIUS_LARGE);

        // Create shadow gradient
        NVGColor shadowStart = HotbarTheme.Background.SHADOW.toNVG(stack);
        NVGColor shadowEnd = HotbarTheme.Background.SHADOW.withAlpha(0).toNVG(stack);

        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg,
            (float)layout.backgroundX, (float)layout.backgroundY,
            (float)layout.backgroundWidth, (float)layout.backgroundHeight,
            HotbarTheme.Measurements.CORNER_RADIUS_LARGE,
            shadowBlur,
            shadowStart, shadowEnd, shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Renders the main background panel.
     */
    private void renderMainBackground(HotbarLayoutCalculator.HotbarLayout layout, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            layout.backgroundX,
            layout.backgroundY,
            layout.backgroundWidth,
            layout.backgroundHeight,
            HotbarTheme.Measurements.CORNER_RADIUS_LARGE);

        nvgFillColor(vg, HotbarTheme.Background.PRIMARY.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders the border around the hotbar background.
     */
    private void renderBorder(HotbarLayoutCalculator.HotbarLayout layout, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            layout.backgroundX,
            layout.backgroundY,
            layout.backgroundWidth,
            layout.backgroundHeight,
            HotbarTheme.Measurements.CORNER_RADIUS_LARGE);

        nvgStrokeColor(vg, HotbarTheme.Background.BORDER.toNVG(stack));
        nvgStrokeWidth(vg, HotbarTheme.Measurements.BORDER_WIDTH_NORMAL);
        nvgStroke(vg);
    }

    /**
     * Renders a subtle gradient overlay for enhanced visual depth.
     */
    public void renderGradientOverlay(HotbarLayoutCalculator.HotbarLayout layout) {
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRoundedRect(vg,
                layout.backgroundX,
                layout.backgroundY,
                layout.backgroundWidth,
                layout.backgroundHeight,
                HotbarTheme.Measurements.CORNER_RADIUS_LARGE);

            // Create subtle vertical gradient for depth
            HotbarTheme.ColorRGBA gradientTop = HotbarTheme.Background.PRIMARY.brighten(0.1f);
            HotbarTheme.ColorRGBA gradientBottom = HotbarTheme.Background.PRIMARY.darken(0.1f);

            NVGPaint gradientPaint = NVGPaint.malloc(stack);
            nvgLinearGradient(vg,
                (float)layout.backgroundX, (float)layout.backgroundY,
                (float)layout.backgroundX, (float)(layout.backgroundY + layout.backgroundHeight),
                gradientTop.toNVG(stack), gradientBottom.toNVG(stack), gradientPaint);

            nvgFillPaint(vg, gradientPaint);
            nvgFill(vg);
        }
    }
}