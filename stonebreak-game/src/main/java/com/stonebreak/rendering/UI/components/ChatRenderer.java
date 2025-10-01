package com.stonebreak.rendering.UI.components;

import com.stonebreak.ui.chat.ChatMessage;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.chat.chatSystem.ChatCommandExecutor;
import com.stonebreak.ui.chat.chatSystem.commands.ChatCommand;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class ChatRenderer extends BaseRenderer {
    private static final int MAX_VISIBLE_LINES = 10; // Maximum chat height in lines

    // Command button tracking
    private String hoveredCommand = null;
    private float lastMouseX = 0;
    private float lastMouseY = 0;

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

                // Border with highlight effect (with tab cutout for active tab)
                drawPanelBorderWithTabCutout(chatSystem, backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);

                // Inner highlight for 3D effect
                drawInnerHighlight(backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);
            }

            float currentY = chatStartY;
            Long previousMessageId = null;

            for (int i = visibleMessages.size() - 1; i >= 0; i--) {
                ChatMessage message = visibleMessages.get(i);
                float alpha = message.getAlpha(isChatOpen);

                if (alpha <= 0.0f) {
                    continue;
                }

                boolean isNewMessage = previousMessageId != null && previousMessageId != message.getMessageId();

                if (isChatOpen) {
                    // Draw divider line between different messages
                    if (isNewMessage) {
                        float dividerY = currentY - lineHeight + 2;
                        nvgBeginPath(vg);
                        nvgMoveTo(vg, chatX - 5, dividerY);
                        nvgLineTo(vg, chatX + maxChatWidth + 5, dividerY);
                        nvgStrokeWidth(vg, 1.0f);
                        nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_NORMAL.withAlpha((int)(alpha * 0.3f * 255)).toNVG(stack));
                        nvgStroke(vg);
                    }

                    // Modern message background with depth
                    nvgBeginPath(vg);
                    nvgRoundedRect(vg, chatX - 5, currentY - lineHeight + 2, maxChatWidth + 10, lineHeight,
                                   InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
                    nvgFillColor(vg, InventoryTheme.Slot.BACKGROUND.withAlpha((int)(alpha * 255)).toNVG(stack));
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
                previousMessageId = message.getMessageId();
                currentY -= lineHeight;
            }

            if (chatSystem.isOpen()) {
                // Calculate chat area dimensions
                float tabHeight = 22; // Height of folder tabs
                float tabSpacing = 2; // Space above tabs
                float chatAreaHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);
                float backgroundY = windowHeight - chatAreaHeight;
                float backgroundX = chatX - backgroundPadding;
                float backgroundWidth = maxChatWidth + (backgroundPadding * 2);

                // Render tab buttons ABOVE the chat panel (folder-style)
                float tabY = backgroundY - tabHeight - tabSpacing;
                renderTabButtons(chatSystem, backgroundX, tabY, tabHeight, stack);

                // Render content based on current tab
                ChatSystem.ChatTab currentTab = chatSystem.getCurrentTab();
                if (currentTab == ChatSystem.ChatTab.COMMANDS) {
                    // Render background pane to cover chat history
                    float commandsY = backgroundY + backgroundPadding + 10; // Below tabs, inside panel
                    float commandsHeight = chatAreaHeight - backgroundPadding * 2 - 10 - inputBoxHeight - inputBoxMargin;

                    nvgBeginPath(vg);
                    nvgRoundedRectVarying(vg, backgroundX + 5, commandsY - 5, backgroundWidth - 10, commandsHeight + 10,
                                         0, 0, InventoryTheme.Measurements.CORNER_RADIUS_SMALL, InventoryTheme.Measurements.CORNER_RADIUS_SMALL);
                    nvgFillColor(vg, InventoryTheme.Panel.BACKGROUND_PRIMARY.toNVG(stack));
                    nvgFill(vg);

                    // Render commands tab with buttons
                    renderCommandButtons(chatSystem, backgroundX, commandsY, backgroundWidth, commandsHeight, stack);
                }

                // Render input box
                float inputBoxY = windowHeight - inputBoxHeight - inputBoxMargin;
                renderChatInputBox(chatSystem, chatX, inputBoxY, maxChatWidth, stack);

                // Render scrollbar if there are more messages than can be displayed (only for chat tab)
                if (currentTab == ChatSystem.ChatTab.CHAT) {
                    int maxScroll = chatSystem.getMaxScroll();
                    if (maxScroll > 0) {
                        renderScrollbar(chatSystem, backgroundX, backgroundY, backgroundWidth, chatAreaHeight, stack);
                    }
                }
            }
        }
    }
    
    private void renderChatInputBox(ChatSystem chatSystem, float x, float y, float width, MemoryStack stack) {
        float inputHeight = 25;
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_SMALL;
        float textPadding = 5;
        float availableTextWidth = width;

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

        // Enable scissoring to clip text to input box
        nvgScissor(vg, x - 5, y, width + 10, inputHeight);

        nvgFontSize(vg, 14);
        nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

        String displayText = chatSystem.getDisplayInput();
        String ghostText = chatSystem.getGhostText();

        if (displayText.isEmpty()) {
            nvgFillColor(vg, InventoryTheme.Text.SECONDARY.withAlpha(128).toNVG(stack));
            nvgText(vg, x, y + inputHeight/2, "Type a message...");
        } else {
            // Measure the full text width (including cursor)
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, displayText, bounds);
            float textWidth = bounds[2] - bounds[0];

            // Calculate scroll offset to keep the end of text visible
            float scrollOffset = 0;
            if (textWidth > availableTextWidth) {
                scrollOffset = textWidth - availableTextWidth;
            }

            // Render main text with horizontal offset to scroll left
            nvgFillColor(vg, InventoryTheme.Text.PRIMARY.toNVG(stack));
            nvgText(vg, x - scrollOffset, y + inputHeight/2, displayText);

            // Render ghost text if available (autocomplete suggestion)
            if (!ghostText.isEmpty()) {
                // Measure text up to cursor position (without cursor character)
                String textWithoutCursor = chatSystem.getCurrentInput();
                float[] cursorBounds = new float[4];
                nvgTextBounds(vg, 0, 0, textWithoutCursor, cursorBounds);
                float cursorX = cursorBounds[2] - cursorBounds[0];

                // Only show ghost text if it's within visible area
                if (cursorX - scrollOffset >= 0 && cursorX - scrollOffset < availableTextWidth) {
                    nvgFillColor(vg, InventoryTheme.Text.SECONDARY.withAlpha(100).toNVG(stack));
                    nvgText(vg, x + cursorX - scrollOffset, y + inputHeight/2, ghostText);
                }
            }
        }

        // Reset scissoring
        nvgResetScissor(vg);
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
     * Draws the main chat panel border with a cutout for the active tab connection.
     */
    private void drawPanelBorderWithTabCutout(ChatSystem chatSystem, float x, float y, float width, float height, MemoryStack stack) {
        ChatSystem.ChatTab currentTab = chatSystem.getCurrentTab();
        float tabWidth = 70;
        float tabSpacing = 3;
        float startX = x + 5;

        // Calculate active tab position
        float activeTabX = currentTab == ChatSystem.ChatTab.CHAT ? startX : (startX + tabWidth + tabSpacing);
        float activeTabEndX = activeTabX + tabWidth;

        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_LARGE;

        // Draw border in segments to skip the active tab area
        nvgBeginPath(vg);

        // Start from bottom left corner
        nvgMoveTo(vg, x, y + height - cornerRadius);
        nvgLineTo(vg, x, y + cornerRadius);
        nvgArcTo(vg, x, y, x + cornerRadius, y, cornerRadius); // Top left corner

        // Top edge - skip the active tab connection area
        if (activeTabX > x + cornerRadius) {
            nvgLineTo(vg, activeTabX, y); // Line to start of tab
        }
        // Skip the tab area (no line drawn here)
        nvgMoveTo(vg, activeTabEndX, y); // Move to end of tab

        // Continue top edge to top right corner
        nvgLineTo(vg, x + width - cornerRadius, y);
        nvgArcTo(vg, x + width, y, x + width, y + cornerRadius, cornerRadius); // Top right corner

        // Right edge
        nvgLineTo(vg, x + width, y + height - cornerRadius);
        nvgArcTo(vg, x + width, y + height, x + width - cornerRadius, y + height, cornerRadius); // Bottom right corner

        // Bottom edge
        nvgLineTo(vg, x + cornerRadius, y + height);
        nvgArcTo(vg, x, y + height, x, y + height - cornerRadius, cornerRadius); // Bottom left corner

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

    /**
     * Render tab buttons at the top of the chat panel (folder-style tabs)
     */
    private void renderTabButtons(ChatSystem chatSystem, float x, float y, float tabHeight, MemoryStack stack) {
        ChatSystem.ChatTab currentTab = chatSystem.getCurrentTab();
        float tabWidth = 70; // Compact tab width
        float tabSpacing = 3; // Small gap between tabs
        float startX = x + 5; // Start in upper left corner with small offset

        // Render Chat tab
        renderTabButton("Chat", startX, y, tabWidth, tabHeight,
                       currentTab == ChatSystem.ChatTab.CHAT, stack);

        // Render Commands tab
        renderTabButton("Commands", startX + tabWidth + tabSpacing, y, tabWidth, tabHeight,
                       currentTab == ChatSystem.ChatTab.COMMANDS, stack);
    }

    /**
     * Render a single folder-style tab button
     */
    private void renderTabButton(String label, float x, float y, float width, float height,
                                 boolean isActive, MemoryStack stack) {
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_MEDIUM;

        // Offset inactive tabs down slightly to appear behind
        float yOffset = isActive ? 0 : 3;
        float adjustedY = y + yOffset;
        float adjustedHeight = isActive ? height + 2 : height; // Active tab slightly taller to overlap panel

        // Shadow for inactive tabs (to appear behind)
        if (!isActive) {
            nvgBeginPath(vg);
            nvgRoundedRectVarying(vg, x + 1, adjustedY + 1, width, adjustedHeight,
                                 cornerRadius, cornerRadius, 0, 0);
            nvgFillColor(vg, InventoryTheme.Panel.SHADOW.toNVG(stack));
            nvgFill(vg);
        }

        // Tab background with folder shape (rounded top corners, flat bottom)
        nvgBeginPath(vg);
        nvgRoundedRectVarying(vg, x, adjustedY, width, adjustedHeight,
                             cornerRadius, cornerRadius, 0, 0);

        if (isActive) {
            // Active tab uses panel background color to blend seamlessly
            nvgFillColor(vg, InventoryTheme.Panel.BACKGROUND_PRIMARY.toNVG(stack));
        } else {
            // Inactive tab is darker
            nvgFillColor(vg, InventoryTheme.Panel.BACKGROUND_PRIMARY.darken(0.15f).toNVG(stack));
        }
        nvgFill(vg);

        // Tab border (only sides and top, not bottom for active tab)
        nvgBeginPath(vg);
        nvgMoveTo(vg, x, adjustedY + adjustedHeight); // Bottom left
        nvgLineTo(vg, x, adjustedY + cornerRadius); // Left side
        nvgArcTo(vg, x, adjustedY, x + cornerRadius, adjustedY, cornerRadius); // Top left corner
        nvgLineTo(vg, x + width - cornerRadius, adjustedY); // Top edge
        nvgArcTo(vg, x + width, adjustedY, x + width, adjustedY + cornerRadius, cornerRadius); // Top right corner
        nvgLineTo(vg, x + width, adjustedY + adjustedHeight); // Right side

        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_NORMAL);
        if (isActive) {
            nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_HIGHLIGHT.toNVG(stack));
        } else {
            nvgStrokeColor(vg, InventoryTheme.Panel.BORDER_PRIMARY.toNVG(stack));
        }
        nvgStroke(vg);

        // Subtle gradient on active tab
        if (isActive) {
            nvgBeginPath(vg);
            nvgRoundedRectVarying(vg, x + 1, adjustedY + 1, width - 2, adjustedHeight - 1,
                                 cornerRadius - 1, cornerRadius - 1, 0, 0);
            NVGPaint gradient = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, x, adjustedY, x, adjustedY + adjustedHeight / 2,
                             InventoryTheme.Panel.BACKGROUND_PRIMARY.brighten(0.08f).toNVG(stack),
                             InventoryTheme.Panel.BACKGROUND_PRIMARY.toNVG(stack),
                             gradient);
            nvgFillPaint(vg, gradient);
            nvgFill(vg);
        }

        // Text
        nvgFontSize(vg, 12);
        nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, isActive ? InventoryTheme.Text.PRIMARY.toNVG(stack)
                                  : InventoryTheme.Text.SECONDARY.toNVG(stack));
        nvgText(vg, x + width / 2, adjustedY + adjustedHeight / 2 - 1, label);
    }

    /**
     * Render command buttons in the Commands tab
     */
    private void renderCommandButtons(ChatSystem chatSystem, float x, float y, float width, float height, MemoryStack stack) {
        ChatCommandExecutor executor = chatSystem.getCommandExecutor();
        Map<String, ChatCommand> commands = executor.getCommands();

        // Convert to sorted list
        List<Map.Entry<String, ChatCommand>> commandList = new ArrayList<>(commands.entrySet());
        commandList.sort(Map.Entry.comparingByKey());

        float buttonWidth = width - 20;
        float buttonHeight = 25;
        float buttonPadding = 5;
        float currentY = y + 10;
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_SMALL;

        // Enable scissoring to clip buttons to commands area
        nvgScissor(vg, x, y, width, height);

        for (Map.Entry<String, ChatCommand> entry : commandList) {
            String commandName = entry.getKey();
            ChatCommand command = entry.getValue();

            // Check if button is visible in the area
            if (currentY + buttonHeight > y + height) {
                break; // Stop rendering if we're past the visible area
            }

            boolean isHovered = commandName.equals(hoveredCommand);

            // Button background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 10, currentY, buttonWidth, buttonHeight, cornerRadius);
            if (isHovered) {
                nvgFillColor(vg, InventoryTheme.Button.BACKGROUND_HOVER.toNVG(stack));
            } else {
                nvgFillColor(vg, InventoryTheme.Slot.BACKGROUND.toNVG(stack));
            }
            nvgFill(vg);

            // Button border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 10, currentY, buttonWidth, buttonHeight, cornerRadius);
            nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_NORMAL);
            if (isHovered) {
                nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_HOVER.toNVG(stack));
            } else {
                nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_NORMAL.toNVG(stack));
            }
            nvgStroke(vg);

            // Command name text
            nvgFontSize(vg, 13);
            nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, InventoryTheme.Text.PRIMARY.toNVG(stack));
            nvgText(vg, x + 15, currentY + buttonHeight / 2, "/" + commandName);

            // Command description text (smaller, secondary color)
            String description = command.getDescription();
            nvgFontSize(vg, 11);
            nvgFillColor(vg, InventoryTheme.Text.SECONDARY.toNVG(stack));
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE);
            nvgText(vg, x + buttonWidth + 5, currentY + buttonHeight / 2, description);

            currentY += buttonHeight + buttonPadding;
        }

        // Reset scissoring
        nvgResetScissor(vg);
    }

    /**
     * Update mouse position for hover detection
     */
    public void updateMousePosition(float mouseX, float mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    /**
     * Check if a command button is clicked at the given position
     */
    public String getClickedCommand(ChatSystem chatSystem, float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!chatSystem.isOpen() || chatSystem.getCurrentTab() != ChatSystem.ChatTab.COMMANDS) {
            return null;
        }

        // Calculate command button area
        float backgroundPadding = 10;
        float maxChatWidth = windowWidth * 0.4f;
        float inputBoxHeight = 25;
        float inputBoxMargin = 10;
        float lineHeight = 20;
        float chatAreaHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);

        float backgroundY = windowHeight - chatAreaHeight;
        float backgroundX = 20 - backgroundPadding;
        float backgroundWidth = maxChatWidth + (backgroundPadding * 2);

        float tabY = backgroundY + backgroundPadding;
        float commandsY = tabY + 35;
        float commandsHeight = chatAreaHeight - 70 - inputBoxHeight - inputBoxMargin;

        // Check if click is within commands area
        if (mouseX < backgroundX + 10 || mouseX > backgroundX + backgroundWidth - 10) {
            return null;
        }
        if (mouseY < commandsY + 10 || mouseY > commandsY + commandsHeight) {
            return null;
        }

        // Calculate which button was clicked
        float buttonHeight = 25;
        float buttonPadding = 5;
        float relativeY = mouseY - (commandsY + 10);
        int buttonIndex = (int) (relativeY / (buttonHeight + buttonPadding));

        // Get the command at that index
        ChatCommandExecutor executor = chatSystem.getCommandExecutor();
        Map<String, ChatCommand> commands = executor.getCommands();
        List<String> commandNames = new ArrayList<>(commands.keySet());
        commandNames.sort(String::compareTo);

        if (buttonIndex >= 0 && buttonIndex < commandNames.size()) {
            return commandNames.get(buttonIndex);
        }

        return null;
    }
}