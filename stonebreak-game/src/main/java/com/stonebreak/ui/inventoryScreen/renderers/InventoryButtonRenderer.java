package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Modern button renderer with contemporary design and interactive states.
 * Features gradient backgrounds, hover effects, and professional styling.
 */
public class InventoryButtonRenderer {

    private InventoryButtonRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws a modern recipe button with enhanced visual feedback and professional styling.
     */
    public static void drawRecipeButton(UIRenderer uiRenderer, InputHandler inputHandler, float x, float y, float w, float h, String text) {
        drawRecipeButton(uiRenderer, inputHandler, x, y, w, h, text, RecipeUIStyleRenderer.RecipeFonts.TITLE_SMALL);
    }

    /**
     * Draws a modern recipe button with custom font size.
     */
    public static void drawRecipeButton(UIRenderer uiRenderer, InputHandler inputHandler, float x, float y, float w, float h, String text, float fontSize) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Determine button state
            boolean isHovering = inputHandler.getMousePosition().x >= x && inputHandler.getMousePosition().x <= x + w &&
                                inputHandler.getMousePosition().y >= y && inputHandler.getMousePosition().y <= y + h;
            boolean isPressed = isHovering && inputHandler.isMouseButtonPressed(0); // Left mouse button

            // Draw button with modern styling
            drawModernButton(vg, x, y, w, h, isHovering, isPressed, stack);

            // Draw button text with enhanced typography
            drawButtonText(vg, x, y, w, h, text, isPressed, fontSize, stack);
        }
    }

    /**
     * Draws a modern button with gradient background, depth effects, and state-based styling.
     */
    private static void drawModernButton(long vg, float x, float y, float w, float h, boolean isHovering, boolean isPressed, MemoryStack stack) {
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_MEDIUM;
        float pressedOffset = isPressed ? 1.0f : 0.0f;

        // Draw button shadow for depth (not when pressed)
        if (!isPressed) {
            drawButtonShadow(vg, x, y, w, h, cornerRadius, stack);
        }

        // Adjust position for pressed state
        float buttonX = x + pressedOffset;
        float buttonY = y + pressedOffset;

        // Draw button background with gradient
        drawButtonBackground(vg, buttonX, buttonY, w, h, cornerRadius, isHovering, isPressed, stack);

        // Draw button border
        drawButtonBorder(vg, buttonX, buttonY, w, h, cornerRadius, isHovering, stack);

        // Draw inner highlight for 3D effect
        if (!isPressed) {
            drawButtonInnerHighlight(vg, buttonX, buttonY, w, h, cornerRadius, stack);
        }
    }

    /**
     * Draws a subtle drop shadow behind the button.
     */
    private static void drawButtonShadow(long vg, float x, float y, float w, float h, float cornerRadius, MemoryStack stack) {
        float shadowOffset = 2.0f;
        float shadowBlur = 4.0f;

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + shadowOffset, y + shadowOffset, w, h, cornerRadius);

        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, x + shadowOffset, y + shadowOffset, w, h,
                      cornerRadius, shadowBlur,
                      InventoryTheme.Panel.SHADOW.toNVG(stack),
                      nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)),
                      shadowPaint);

        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Draws the button background with gradient and state-based coloring.
     */
    private static void drawButtonBackground(long vg, float x, float y, float w, float h, float cornerRadius, boolean isHovering, boolean isPressed, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, cornerRadius);

        // Choose colors based on button state
        InventoryTheme.ColorRGBA topColor, bottomColor;
        if (isPressed) {
            topColor = InventoryTheme.Button.BACKGROUND_PRESSED;
            bottomColor = InventoryTheme.Button.BACKGROUND_PRESSED.brighten(0.1f);
        } else if (isHovering) {
            topColor = InventoryTheme.Button.GRADIENT_TOP.brighten(0.2f);
            bottomColor = InventoryTheme.Button.GRADIENT_BOTTOM.brighten(0.2f);
        } else {
            topColor = InventoryTheme.Button.GRADIENT_TOP;
            bottomColor = InventoryTheme.Button.GRADIENT_BOTTOM;
        }

        // Create vertical gradient
        NVGPaint backgroundPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + h,
                         topColor.toNVG(stack),
                         bottomColor.toNVG(stack),
                         backgroundPaint);

        nvgFillPaint(vg, backgroundPaint);
        nvgFill(vg);
    }

    /**
     * Draws the button border with state-dependent styling.
     */
    private static void drawButtonBorder(long vg, float x, float y, float w, float h, float cornerRadius, boolean isHovering, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, cornerRadius);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_NORMAL);

        if (isHovering) {
            nvgStrokeColor(vg, InventoryTheme.Button.BORDER_HOVER.toNVG(stack));
        } else {
            nvgStrokeColor(vg, InventoryTheme.Button.BORDER_NORMAL.toNVG(stack));
        }
        nvgStroke(vg);
    }

    /**
     * Draws an inner highlight for 3D depth effect.
     */
    private static void drawButtonInnerHighlight(long vg, float x, float y, float w, float h, float cornerRadius, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 1, y + 1, w - 2, 2, cornerRadius - 1); // Only highlight the top edge
        nvgFillColor(vg, InventoryTheme.Button.BORDER_HOVER.withAlpha(60).toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Draws button text with enhanced typography and subtle effects.
     */
    private static void drawButtonText(long vg, float x, float y, float w, float h, String text, boolean isPressed, float fontSize, MemoryStack stack) {
        // Adjust text position for pressed state
        float textX = x + w / 2 + (isPressed ? 0.5f : 0);
        float textY = y + h / 2 + (isPressed ? 0.5f : 0);

        // Set font and alignment
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, fontSize);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Draw text shadow for depth
        nvgFillColor(vg, InventoryTheme.Text.SHADOW.toNVG(stack));
        nvgText(vg, textX + 1, textY + 1, text);

        // Draw main text
        nvgFillColor(vg, InventoryTheme.Text.PRIMARY.toNVG(stack));
        nvgText(vg, textX, textY, text);

        // Add subtle glow effect for primary text
        nvgGlobalAlpha(vg, 0.3f);
        nvgFillColor(vg, InventoryTheme.Text.ACCENT.toNVG(stack));
        nvgText(vg, textX, textY, text);
        nvgGlobalAlpha(vg, 1.0f);
    }

    /**
     * Legacy helper method for backward compatibility
     */
    @Deprecated
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}