package com.stonebreak.rendering.UI.components;

import com.stonebreak.chat.ChatMessage;
import com.stonebreak.chat.ChatSystem;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class ChatRenderer extends BaseRenderer {
    
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
            
            float chatStartY;
            if (chatSystem.isOpen()) {
                chatStartY = windowHeight - inputBoxHeight - inputBoxMargin - lineHeight;
            } else {
                chatStartY = windowHeight - 20 - lineHeight;
            }
            
            float currentY = chatStartY;
            for (int i = visibleMessages.size() - 1; i >= 0; i--) {
                ChatMessage message = visibleMessages.get(i);
                float alpha = message.getAlpha();
                
                if (alpha <= 0.0f) {
                    continue;
                }
                
                if (chatSystem.isOpen()) {
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
}