package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.chat.SkijaChatRenderer;
import com.stonebreak.ui.chat.emoji.ChatEmoji;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * All input routed to an open chat: scrollbar presses/drags, tab switching,
 * the emoji picker, command buttons, and the chat key switch
 * (backspace/enter/escape/copy/paste/tab-complete).
 */
final class ChatInputRouter {

    private final KeyEdgeTracker keys;
    private final MouseInputState mouse;

    ChatInputRouter(KeyEdgeTracker keys, MouseInputState mouse) {
        this.keys = keys;
        this.mouse = mouse;
    }

    /** Handles a mouse button event while chat is open. */
    void handleMouseButton(ChatSystem chatSystem, int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        SkijaChatRenderer renderer = skijaChatRenderer();
        if (action == GLFW_PRESS) {
            if (renderer != null) {
                int windowWidth = Game.getWindowWidth();
                int windowHeight = Game.getWindowHeight();
                // Scrollbars claim the press before anything else.
                if (renderer.handleChatScrollbarPress(chatSystem, mouse.x(), mouse.y(), windowWidth, windowHeight)
                        || renderer.handleCommandScrollbarPress(chatSystem, mouse.x(), mouse.y(), windowWidth, windowHeight)) {
                    return;
                }
            }
            handleChatClick(chatSystem);
        } else if (action == GLFW_RELEASE && renderer != null) {
            renderer.handleScrollbarRelease();
        }
    }

    /** Updates chat hover state and continues a scrollbar drag as the cursor moves. */
    void onMouseMove(ChatSystem chatSystem) {
        SkijaChatRenderer renderer = skijaChatRenderer();
        if (renderer == null) {
            return;
        }
        renderer.updateMousePosition(mouse.x(), mouse.y());
        if (renderer.isDraggingScrollbar()) {
            renderer.handleScrollbarDrag(chatSystem, mouse.y(), Game.getWindowHeight());
        }
    }

    /** Handles key input while chat is open (the chat swallows everything else). */
    void handleKeyInput(ChatSystem chatSystem, int key, int action) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) {
            return;
        }
        switch (key) {
            case GLFW_KEY_BACKSPACE -> chatSystem.handleBackspace();
            case GLFW_KEY_ENTER -> chatSystem.handleEnter();
            case GLFW_KEY_ESCAPE -> chatSystem.closeChat();
            case GLFW_KEY_V -> {
                if (isCtrlDown()) {
                    chatSystem.handlePaste();
                }
            }
            case GLFW_KEY_C -> {
                if (isCtrlDown()) {
                    chatSystem.handleCopy();
                }
            }
            case GLFW_KEY_T -> {
                // T does nothing when chat is already open.
            }
            case GLFW_KEY_TAB -> chatSystem.handleTab();
        }
    }

    private boolean isCtrlDown() {
        return keys.isDown(GLFW_KEY_LEFT_CONTROL) || keys.isDown(GLFW_KEY_RIGHT_CONTROL);
    }

    /** Left-click inside the chat panel: emoji button/picker, folder tabs, command buttons. */
    private void handleChatClick(ChatSystem chatSystem) {
        int windowWidth = Game.getWindowWidth();
        int windowHeight = Game.getWindowHeight();

        // ── Emoji button / picker ─────────────────────────────────────────
        SkijaChatRenderer renderer = skijaChatRenderer();
        if (renderer != null) {
            // Toggle picker on emoji button click.
            if (renderer.isEmojiButtonClicked(mouse.x(), mouse.y(), windowWidth, windowHeight)) {
                chatSystem.toggleEmojiPicker();
                return;
            }

            if (chatSystem.isEmojiPickerOpen()) {
                // Star click → toggle favourite; emoji click → insert.
                ChatEmoji starTarget = renderer.getPickerFavoriteStarClick(
                        chatSystem, mouse.x(), mouse.y(), windowWidth, windowHeight);
                if (starTarget != null) {
                    chatSystem.getEmojiSystem().toggleFavorite(starTarget);
                    return;
                }

                ChatEmoji emojiTarget = renderer.getPickerEmojiClick(
                        chatSystem, mouse.x(), mouse.y(), windowWidth, windowHeight);
                if (emojiTarget != null) {
                    chatSystem.insertEmoji(emojiTarget);
                    return;
                }

                // Click outside picker closes it and consumes the click.
                chatSystem.closeEmojiPicker();
                return;
            }
        }

        // Tab hitboxes must match SkijaChatRenderer's folder-style tab layout.
        float backgroundPadding = 10;
        float inputBoxHeight = 25;
        float inputBoxMargin = 10;
        float lineHeight = 20;
        float chatAreaHeight = (10 * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);

        float backgroundY = windowHeight - chatAreaHeight;
        float backgroundX = 20 - backgroundPadding;

        // Tabs sit above the panel.
        float tabHeight = 22;
        float tabSpacing = 2;
        float tabY = backgroundY - tabHeight - tabSpacing;
        float tabWidth = 70;
        float tabGap = 3;
        float startX = backgroundX + 5;

        float chatTabX = startX;
        float commandsTabX = startX + tabWidth + tabGap;

        if (mouse.x() >= chatTabX && mouse.x() <= chatTabX + tabWidth
                && mouse.y() >= tabY && mouse.y() <= tabY + tabHeight) {
            chatSystem.setCurrentTab(ChatSystem.ChatTab.CHAT);
            return;
        }

        if (mouse.x() >= commandsTabX && mouse.x() <= commandsTabX + tabWidth
                && mouse.y() >= tabY && mouse.y() <= tabY + tabHeight) {
            chatSystem.setCurrentTab(ChatSystem.ChatTab.COMMANDS);
            return;
        }

        // Command buttons (only on the Commands tab).
        if (chatSystem.getCurrentTab() == ChatSystem.ChatTab.COMMANDS && renderer != null) {
            String clickedCommand = renderer.getClickedCommand(
                chatSystem, mouse.x(), mouse.y(), windowWidth, windowHeight);
            if (clickedCommand != null) {
                // Populate the input with the command instead of executing it.
                chatSystem.setInput("/" + clickedCommand + " ");
                chatSystem.setCurrentTab(ChatSystem.ChatTab.CHAT);
            }
        }
    }

    private static SkijaChatRenderer skijaChatRenderer() {
        UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
        return uiRenderer != null ? uiRenderer.getSkijaChatRenderer() : null;
    }
}
