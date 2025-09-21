package com.stonebreak.ui.hotbar.renderers;

import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer for individual hotbar slots.
 * Handles slot backgrounds, borders, selection highlights, and hover effects.
 */
public class HotbarSlotRenderer {

    private final long vg;

    public HotbarSlotRenderer(long vg) {
        this.vg = vg;
    }

    /**
     * Renders a complete hotbar slot with all visual states.
     */
    public void renderSlot(HotbarLayoutCalculator.SlotPosition position, boolean isSelected, boolean isHovered) {
        try (MemoryStack stack = stackPush()) {
            // Render slot background
            renderSlotBackground(position, stack);

            // Render inner shadow for depth
            renderInnerShadow(position, stack);

            // Render selection highlight if selected
            if (isSelected) {
                renderSelectionHighlight(position, stack);
            }

            // Render hover effect if hovered
            if (isHovered && !isSelected) {
                renderHoverEffect(position, stack);
            }

            // Render slot border
            renderSlotBorder(position, isSelected, stack);
        }
    }

    /**
     * Renders the basic slot background.
     */
    private void renderSlotBackground(HotbarLayoutCalculator.SlotPosition position, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM);
        nvgFillColor(vg, HotbarTheme.Slot.BACKGROUND.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders inner shadow for depth effect.
     */
    private void renderInnerShadow(HotbarLayoutCalculator.SlotPosition position, MemoryStack stack) {
        float insetSize = 2.0f;

        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            position.x + insetSize,
            position.y + insetSize,
            position.width - (insetSize * 2),
            position.height - (insetSize * 2),
            HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM - insetSize);

        // Create inset shadow gradient
        NVGColor shadowColor = HotbarTheme.Slot.SHADOW_INNER.toNVG(stack);
        NVGColor transparent = HotbarTheme.Slot.SHADOW_INNER.withAlpha(0).toNVG(stack);

        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg,
            (float)position.x, (float)position.y,
            (float)position.width, (float)position.height,
            HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM,
            insetSize * 2,
            shadowColor, transparent, shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Renders selection highlight for the active slot.
     */
    private void renderSelectionHighlight(HotbarLayoutCalculator.SlotPosition position, MemoryStack stack) {
        float highlightPadding = 2.0f;

        // Outer selection glow
        nvgBeginPath(vg);
        nvgRoundedRect(vg,
            position.x - highlightPadding,
            position.y - highlightPadding,
            position.width + (highlightPadding * 2),
            position.height + (highlightPadding * 2),
            HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM + highlightPadding);

        nvgStrokeColor(vg, HotbarTheme.Slot.BORDER_SELECTED.toNVG(stack));
        nvgStrokeWidth(vg, HotbarTheme.Measurements.BORDER_WIDTH_THICK);
        nvgStroke(vg);

        // Inner highlight glow
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM);
        nvgFillColor(vg, HotbarTheme.Slot.HIGHLIGHT_INNER.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders hover effect for non-selected slots.
     */
    private void renderHoverEffect(HotbarLayoutCalculator.SlotPosition position, MemoryStack stack) {
        // Subtle highlight for hover
        HotbarTheme.ColorRGBA hoverColor = HotbarTheme.Slot.BACKGROUND.brighten(0.2f);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM);
        nvgFillColor(vg, hoverColor.toNVG(stack));
        nvgFill(vg);

        // Hover border
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM);
        nvgStrokeColor(vg, HotbarTheme.Slot.HIGHLIGHT_INNER.toNVG(stack));
        nvgStrokeWidth(vg, HotbarTheme.Measurements.BORDER_WIDTH_NORMAL);
        nvgStroke(vg);
    }

    /**
     * Renders the slot border.
     */
    private void renderSlotBorder(HotbarLayoutCalculator.SlotPosition position, boolean isSelected, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, position.x, position.y, position.width, position.height,
                      HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM);

        HotbarTheme.ColorRGBA borderColor = isSelected ?
            HotbarTheme.Slot.BORDER_SELECTED :
            HotbarTheme.Slot.BORDER_NORMAL;

        nvgStrokeColor(vg, borderColor.toNVG(stack));
        nvgStrokeWidth(vg, HotbarTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStroke(vg);
    }

    /**
     * Renders a pulsing animation for the selected slot.
     */
    public void renderSelectionPulse(HotbarLayoutCalculator.SlotPosition position, float time) {
        try (MemoryStack stack = stackPush()) {
            // Calculate pulse intensity based on time
            float pulseIntensity = (float)(Math.sin(time * HotbarTheme.Animation.HIGHLIGHT_PULSE_SPEED) * 0.5 + 0.5);
            float alpha = 0.3f + (pulseIntensity * 0.4f); // Pulse between 0.3 and 0.7 alpha

            float glowRadius = 4.0f + (pulseIntensity * 2.0f); // Pulse glow radius

            nvgBeginPath(vg);
            nvgRoundedRect(vg,
                position.x - glowRadius,
                position.y - glowRadius,
                position.width + (glowRadius * 2),
                position.height + (glowRadius * 2),
                HotbarTheme.Measurements.CORNER_RADIUS_MEDIUM + glowRadius);

            HotbarTheme.ColorRGBA pulseColor = HotbarTheme.Slot.HIGHLIGHT_INNER.withAlpha((int)(alpha * 255));
            nvgFillColor(vg, pulseColor.toNVG(stack));
            nvgFill(vg);
        }
    }
}