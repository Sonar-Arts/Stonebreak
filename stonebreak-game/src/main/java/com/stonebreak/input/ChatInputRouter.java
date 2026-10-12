package com.stonebreak.input;

import com.stonebreak.ui.chat.ChatSystem;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Key input routed to an open chat: the chat key switch
 * (backspace/enter/escape/copy/paste/tab-complete). The chat document takes
 * the chat's pointer input.
 */
final class ChatInputRouter {

    private final KeyEdgeTracker keys;

    ChatInputRouter(KeyEdgeTracker keys) {
        this.keys = keys;
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
}
