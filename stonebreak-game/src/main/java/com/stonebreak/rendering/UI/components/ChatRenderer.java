package com.stonebreak.rendering.UI.components;

import com.stonebreak.ui.chat.ChatMessage;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class ChatRenderer extends BaseRenderer {
    private static final int MAX_VISIBLE_LINES = 10; // Maximum chat height in lines

    public ChatRenderer(long vg) {
        super(vg);
        loadFonts();
    }

    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        if (chatSystem == null) {
            return;
        }

        List<ChatMessage> visibleMessages = chatSystem.getVisibleMessages();
        if (visibleMessages.isEmpty() && !chatSystem.isOpen()) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            float chatX = 20;
            float lineHeight = 20;
            float maxChatWidth = windowWidth * 0.4f;
            float inputBoxHeight = 25;
            float inputBoxMargin = 10;
            float backgroundPadding = 10;
            boolean isChatOpen = chatSystem.isOpen();

            float chatStartY;
            if (chatSystem.isOpen()) {
                chatStartY = windowHeight - inputBoxHeight - inputBoxMargin - lineHeight;
            } else {
                chatStartY = windowHeight - 20 - lineHeight;
            }

            // Render modern styled background panel when chat is open
            if (isChatOpen) {
                // Use MAX_VISIBLE_LINES for consistent chat area height
                float chatAreaHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);
                float backgroundY = windowHeight - chatAreaHeight;
                float backgroundX = chatX - backgroundPadding;
                float backgroundWidth = maxChatWidth + (backgroundPadding * 2);

                // Drop shadow for depth
                drawPanelShadow(backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);

                // Main panel background with gradient
                drawPanelBackground(backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);

                // Border with highlight effect
                drawPanelBorder(backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);

                // Inner highlight for 3D effect
                drawInnerHighlight(backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);
            }

            float currentY = chatStartY;
            for (int i = visibleMessages.size() - 1; i >= 0; i--) {
                ChatMessage message = visibleMessages.get(i);
                float alpha = message.getAlpha(isChatOpen);

                if (alpha <= 0.0f) {
                    continue;
                }

                if (isChatOpen) {
                    // Modern message background with depth
                    nvgBeginPath(vg);
                    nvgRoundedRect(vg, chatX - 5, currentY - lineHeight + 2, maxChatWidth + 10, lineHeight,
                                   InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
                    nvgFillColor(vg, InventoryTheme.Slot.BACKGROUND.withAlpha((int)(alpha * 255)).toNVG(stack));
                    nvgFill(vg);

                    // Subtle border for message separation
                    nvgBeginPath(vg);
                    nvgRoundedRect(vg, chatX - 5, currentY - lineHeight + 2, maxChatWidth + 10, lineHeight,
                                   InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
                    nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
                    nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_NORMAL.withAlpha((int)(alpha * 0.5f * 255)).toNVG(stack));
                    nvgStroke(vg);
                }

                nvgFontSize(vg, 14);
                nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
                nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

                float[] color = message.getColor();
                nvgFillColor(vg, nvgRGBA(
                    (int)(color[0] * 255),
                    (int)(color[1] * 255),
                    (int)(color[2] * 255),
                    (int)(alpha * 255),
                    NVGColor.malloc(stack)
                ));

                nvgText(vg, chatX, currentY - lineHeight/2, message.getText());
                currentY -= lineHeight;
            }

            if (chatSystem.isOpen()) {
                float inputBoxY = windowHeight - inputBoxHeight - inputBoxMargin;
                renderChatInputBox(chatSystem, chatX, inputBoxY, maxChatWidth, stack);

                // Render scrollbar if there are more messages than can be displayed
                int maxScroll = chatSystem.getMaxScroll();
                if (maxScroll > 0) {
                    // Use MAX_VISIBLE_LINES for consistent scrollbar positioning
                    float chatAreaHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);
                    float backgroundY = windowHeight - chatAreaHeight;
                    float backgroundX = chatX - backgroundPadding;
                    float backgroundWidth = maxChatWidth + (backgroundPadding * 2);

                    renderScrollbar(chatSystem, backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);
                }
            }
        }
    }
    
    private void renderChatInputBox(ChatSystem chatSystem, float x, float y, float width, MemoryStack stack) {
        float inputHeight = 25;
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_SMALL;

        // Input box background with gradient
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x - 5, y, width + 10, inputHeight, cornerRadius);
        NVGPaint inputGradient = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x - 5, y, x - 5, y + inputHeight,
                         InventoryTheme.Slot.BACKGROUND.toNVG(stack),
                         InventoryTheme.Slot.BACKGROUND.darken(0.1f).toNVG(stack),
                         inputGradient);
        nvgFillPaint(vg, inputGradient);
        nvgFill(vg);

        // Inner shadow for depth
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x - 4, y + 1, width + 8, 2, cornerRadius);
        nvgFillColor(vg, InventoryTheme.Slot.SHADOW_INNER.withAlpha(120).toNVG(stack));
        nvgFill(vg);

        // Enhanced border (interactive state)
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x - 5, y, width + 10, inputHeight, cornerRadius);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_NORMAL);
        nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_HOVER.toNVG(stack));
        nvgStroke(vg);

        nvgFontSize(vg, 14);
        nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

        String displayText = chatSystem.getDisplayInput();
        if (displayText.isEmpty()) {
            nvgFillColor(vg, InventoryTheme.Text.SECONDARY.withAlpha(128).toNVG(stack));
            nvgText(vg, x, y + inputHeight/2, "Type a message...");
        } else {
            nvgFillColor(vg, InventoryTheme.Text.PRIMARY.toNVG(stack));
            nvgText(vg, x, y + inputHeight/2, displayText);
        }
    }

    private void renderScrollbar(ChatSystem chatSystem, float chatX, float chatY, float chatWidth, float chatHeight, MemoryStack stack) {
        float scrollbarWidth = 6.0f;
        float scrollbarX = chatX + chatWidth - scrollbarWidth - 5;
        float scrollbarPadding = 5.0f;

        // Exclude input box from scrollbar area
        float inputBoxHeight = 25;
        float inputBoxMargin = 10;
        float messagesAreaHeight = chatHeight - inputBoxHeight - inputBoxMargin - scrollbarPadding;

        float scrollbarTrackY = chatY + scrollbarPadding;
        float scrollbarTrackHeight = messagesAreaHeight - scrollbarPadding;

        // Draw scrollbar track with modern styling
        nvgBeginPath(vg);
        nvgRoundedRect(vg, scrollbarX, scrollbarTrackY, scrollbarWidth, scrollbarTrackHeight,
                      InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
        nvgFillColor(vg, InventoryTheme.Slot.BACKGROUND.darken(0.3f).toNVG(stack));
        nvgFill(vg);

        // Calculate scrollbar thumb size and position
        int currentScroll = chatSystem.getScrollOffset();
        int maxScroll = chatSystem.getMaxScroll();

        if (maxScroll > 0) {
            // Thumb height proportional to visible content vs total content
            float visibleRatio = 10.0f / (10.0f + maxScroll); // 10 visible messages
            float thumbHeight = Math.max(20.0f, scrollbarTrackHeight * visibleRatio);

            // Thumb position based on scroll offset
            float scrollRatio = (float) currentScroll / maxScroll;
            float thumbY = scrollbarTrackY + (scrollbarTrackHeight - thumbHeight) * scrollRatio;

            // Draw scrollbar thumb with modern styling
            nvgBeginPath(vg);
            nvgRoundedRect(vg, scrollbarX, thumbY, scrollbarWidth, thumbHeight,
                          InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
            nvgFillColor(vg, InventoryTheme.Slot.BORDER_HOVER.toNVG(stack));
            nvgFill(vg);

            // Add subtle highlight to thumb
            nvgBeginPath(vg);
            nvgRoundedRect(vg, scrollbarX, thumbY, scrollbarWidth, thumbHeight,
                          InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
            nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
            nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_SELECTED.withAlpha(150).toNVG(stack));
            nvgStroke(vg);
        }
    }

    /**
     * Draws a subtle drop shadow behind the chat panel for depth.
     */
    private void drawPanelShadow(float x, float y, float width, float height, MemoryStack stack) {
        float offset = InventoryTheme.Measurements.SHADOW_OFFSET;
        float blur = InventoryTheme.Measurements.SHADOW_BLUR;

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + offset, y + offset, width, height,
                      InventoryTheme.Measurements.CORNER_RADIUS_LARGE);

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
     * Draws the main chat panel background with a subtle gradient.
     */
    private void drawPanelBackground(float x, float y, float width, float height, MemoryStack stack) {
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
     * Draws the main chat panel border with highlight effect.
     */
    private void drawPanelBorder(float x, float y, float width, float height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, InventoryTheme.Measurements.CORNER_RADIUS_LARGE);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THICK);
        nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_HIGHLIGHT.toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Draws an inner highlight for 3D depth effect.
     */
    private void drawInnerHighlight(float x, float y, float width, float height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 1, y + 1, width - 2, height - 2,
                      InventoryTheme.Measurements.CORNER_RADIUS_LARGE - 1);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_PRIMARY.withAlpha(80).toNVG(stack));
        nvgStroke(vg);
    }
}