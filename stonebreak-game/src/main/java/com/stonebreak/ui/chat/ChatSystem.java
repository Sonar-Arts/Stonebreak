package com.stonebreak.ui.chat;

import java.util.List;

import com.stonebreak.core.Game;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.ui.chat.chatSystem.*;

/**
 * Main chat system coordinator.
 * Refactored to follow SOLID, KISS, and DRY principles by delegating to specialized components.
 */
public class ChatSystem {
    private final ChatMessageManager messageManager;
    private final ChatInputHandler inputHandler;
    private final ChatCursorState cursorState;
    private final ChatCommandExecutor commandExecutor;
    private boolean isOpen;

    public ChatSystem() {
        TextWrapper textWrapper = new TextWrapper();
        this.messageManager = new ChatMessageManager(textWrapper);
        this.inputHandler = new ChatInputHandler();
        this.cursorState = new ChatCursorState();
        this.commandExecutor = new ChatCommandExecutor(messageManager);
        this.isOpen = false;
    }
    
    public void addMessage(String text) {
        messageManager.addMessage(text);
    }

    public void addMessage(String text, float[] color) {
        messageManager.addMessage(text, color);
    }
    
    public void openChat() {
        if (!isOpen) {
            isOpen = true;
            inputHandler.clear();
            cursorState.reset();
            updateMouseCapture();
        }
    }

    public void closeChat() {
        isOpen = false;
        inputHandler.clear();
        updateMouseCapture();
    }

    public boolean isOpen() {
        return isOpen;
    }

    private void updateMouseCapture() {
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.forceUpdate();
        }
    }
    
    public void update(float deltaTime) {
        messageManager.update();

        if (isOpen) {
            cursorState.update(deltaTime);
        }
    }
    
    public void handleCharInput(char character) {
        if (isOpen) {
            inputHandler.handleCharInput(character);
        }
    }

    public void handleBackspace() {
        if (isOpen) {
            inputHandler.handleBackspace();
        }
    }

    public void handlePaste() {
        if (isOpen) {
            inputHandler.handlePaste();
        }
    }

    public void handleCopy() {
        if (isOpen) {
            inputHandler.handleCopy();
        }
    }

    public void copyMessageToClipboard(String message) {
        inputHandler.copyToClipboard(message);
    }
    
    public void handleEnter() {
        if (!isOpen) {
            return;
        }

        String message = inputHandler.getCurrentInput().trim();
        if (!message.isEmpty()) {
            if (message.startsWith("/")) {
                commandExecutor.executeCommand(message);
            } else {
                messageManager.addMessage("<Player> " + message);
            }
        }

        closeChat();
    }
    
    
    public String getCurrentInput() {
        return inputHandler.getCurrentInput();
    }

    public String getDisplayInput() {
        String input = getCurrentInput();
        if (isOpen) {
            return input + cursorState.getDisplayCursor();
        }
        return input;
    }

    public List<ChatMessage> getVisibleMessages() {
        return messageManager.getVisibleMessages(isOpen);
    }
}