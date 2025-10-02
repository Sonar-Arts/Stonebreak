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
    private static final int MAX_VISIBLE_MESSAGES = 10; // Must match ChatRenderer.MAX_VISIBLE_LINES

    private final ChatMessageManager messageManager;
    private final ChatInputHandler inputHandler;
    private final ChatCursorState cursorState;
    private final ChatCommandExecutor commandExecutor;
    private boolean isOpen;
    private int scrollOffset = 0; // Number of messages scrolled up from bottom
    private int commandScrollOffset = 0; // Number of commands scrolled down from top

    // Tab system
    public enum ChatTab {
        CHAT,
        COMMANDS
    }
    private ChatTab currentTab = ChatTab.CHAT;

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
            scrollOffset = 0; // Reset scroll when opening chat
            updateMouseCapture();
        }
    }

    public void closeChat() {
        isOpen = false;
        inputHandler.clear();
        scrollOffset = 0; // Reset scroll when closing chat
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
        messageManager.update(isOpen);

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

    public void handleTab() {
        if (isOpen) {
            inputHandler.handleTab(commandExecutor);
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

    /**
     * Set the current chat input to a specific value (for populating from command buttons)
     */
    public void setInput(String text) {
        inputHandler.setInput(text);
    }

    public String getDisplayInput() {
        String input = getCurrentInput();
        if (isOpen) {
            return input + cursorState.getDisplayCursor();
        }
        return input;
    }

    /**
     * Get ghost text suggestion for autocomplete display
     * @return Ghost text to show in lighter color, or empty string
     */
    public String getGhostText() {
        if (!isOpen) {
            return "";
        }
        return inputHandler.getGhostText(commandExecutor);
    }

    public List<ChatMessage> getVisibleMessages() {
        return messageManager.getVisibleMessages(isOpen, scrollOffset);
    }

    /**
     * Handle scroll input when chat is open
     */
    public void handleScroll(double yOffset) {
        if (!isOpen) {
            return;
        }

        // Handle scrolling based on active tab
        if (currentTab == ChatTab.CHAT) {
            // Chat tab - scroll through messages
            int totalMessages = messageManager.getHistoryCount();

            // Scroll up (positive offset increases scroll)
            if (yOffset > 0) {
                scrollOffset = Math.min(scrollOffset + 1, Math.max(0, totalMessages - MAX_VISIBLE_MESSAGES));
            }
            // Scroll down (negative offset decreases scroll)
            else if (yOffset < 0) {
                scrollOffset = Math.max(scrollOffset - 1, 0);
            }
        } else if (currentTab == ChatTab.COMMANDS) {
            // Commands tab - scroll through command buttons
            int maxScroll = getMaxCommandScroll();

            // Scroll down (positive offset increases scroll)
            if (yOffset > 0) {
                commandScrollOffset = Math.min(commandScrollOffset + 1, maxScroll);
            }
            // Scroll up (negative offset decreases scroll)
            else if (yOffset < 0) {
                commandScrollOffset = Math.max(commandScrollOffset - 1, 0);
            }
        }
    }

    /**
     * Get current scroll offset (for scrollbar rendering)
     */
    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * Get maximum scroll value (for scrollbar rendering)
     */
    public int getMaxScroll() {
        // Use history count when chat is open for scrollbar calculations
        int totalMessages = messageManager.getHistoryCount();
        return Math.max(0, totalMessages - MAX_VISIBLE_MESSAGES);
    }

    /**
     * Get the command executor (for help command)
     */
    public ChatCommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    /**
     * Get current command scroll offset (for scrollbar rendering)
     */
    public int getCommandScrollOffset() {
        return commandScrollOffset;
    }

    /**
     * Get maximum command scroll value (for scrollbar rendering)
     */
    public int getMaxCommandScroll() {
        int totalCommands = commandExecutor.getCommands().size();
        // Assuming button height of 25px + 5px padding = 30px per button
        // And visible area of approximately 6-7 buttons
        int maxVisibleCommands = 6;
        return Math.max(0, totalCommands - maxVisibleCommands);
    }

    /**
     * Get the current active tab
     */
    public ChatTab getCurrentTab() {
        return currentTab;
    }

    /**
     * Set the current active tab
     */
    public void setCurrentTab(ChatTab tab) {
        this.currentTab = tab;
        // Reset scroll when switching tabs
        this.scrollOffset = 0;
        this.commandScrollOffset = 0;
    }

    /**
     * Switch to the next tab
     */
    public void switchToNextTab() {
        ChatTab[] tabs = ChatTab.values();
        int currentIndex = currentTab.ordinal();
        int nextIndex = (currentIndex + 1) % tabs.length;
        setCurrentTab(tabs[nextIndex]);
    }

    /**
     * Switch to the previous tab
     */
    public void switchToPreviousTab() {
        ChatTab[] tabs = ChatTab.values();
        int currentIndex = currentTab.ordinal();
        int previousIndex = (currentIndex - 1 + tabs.length) % tabs.length;
        setCurrentTab(tabs[previousIndex]);
    }
}