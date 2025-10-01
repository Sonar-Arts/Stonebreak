package com.stonebreak.rendering.UI.components;

import com.stonebreak.ui.chat.ChatMessage;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import org.lwjgl.nanovg.NVGColor;
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

            // Render opaque gray background panel when chat is open
            if (isChatOpen) {
                // Use MAX_VISIBLE_LINES for consistent chat area height
                float chatAreaHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);
                float backgroundY = windowHeight - chatAreaHeight;
                float backgroundX = chatX - backgroundPadding;
                float backgroundWidth = maxChatWidth + (backgroundPadding * 2);

                nvgBeginPath(vg);
                nvgRoundedRect(vg, backgroundX, backgroundY, backgroundWidth, chatAreaHeight, 5.0f);
                nvgFillColor(vg, nvgRGBA(50, 50, 50, 230, NVGColor.malloc(stack)));
                nvgFill(vg);
            }

            float currentY = chatStartY;
            for (int i = visibleMessages.size() - 1; i >= 0; i--) {
                ChatMessage message = visibleMessages.get(i);
                float alpha = message.getAlpha(isChatOpen);

                if (alpha <= 0.0f) {
                    continue;
                }

                if (isChatOpen) {
                    nvgBeginPath(vg);
                    nvgRect(vg, chatX - 5, currentY - lineHeight + 2, maxChatWidth + 10, lineHeight);
                    nvgFillColor(vg, nvgRGBA(0, 0, 0, (int)(80 * alpha), NVGColor.malloc(stack)));
                    nvgFill(vg);
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

        nvgBeginPath(vg);
        nvgRect(vg, x - 5, y, width + 10, inputHeight);
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
        nvgFill(vg);

        nvgBeginPath(vg);
        nvgRect(vg, x - 5, y, width + 10, inputHeight);
        nvgStrokeWidth(vg, 1.0f);
        nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 200, NVGColor.malloc(stack)));
        nvgStroke(vg);

        nvgFontSize(vg, 14);
        nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));

        String displayText = chatSystem.getDisplayInput();
        if (displayText.isEmpty()) {
            nvgFillColor(vg, nvgRGBA(128, 128, 128, 255, NVGColor.malloc(stack)));
            nvgText(vg, x, y + inputHeight/2, "Type a message...");
        } else {
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

        // Draw scrollbar track (darker background)
        nvgBeginPath(vg);
        nvgRoundedRect(vg, scrollbarX, scrollbarTrackY, scrollbarWidth, scrollbarTrackHeight, 3.0f);
        nvgFillColor(vg, nvgRGBA(30, 30, 30, 200, NVGColor.malloc(stack)));
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

            // Draw scrollbar thumb (lighter, with hover effect)
            nvgBeginPath(vg);
            nvgRoundedRect(vg, scrollbarX, thumbY, scrollbarWidth, thumbHeight, 3.0f);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 220, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Add subtle highlight to thumb
            nvgBeginPath(vg);
            nvgRoundedRect(vg, scrollbarX, thumbY, scrollbarWidth, thumbHeight, 3.0f);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(220, 220, 220, 150, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
}