package com.stonebreak.ui.chat.chatSystem;

import com.stonebreak.ui.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages chat message storage, lifecycle, and visibility.
 * Follows Single Responsibility Principle by focusing solely on message management.
 */
public class ChatMessageManager {
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_VISIBLE_MESSAGES = 10;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final List<ChatMessage> messages;
    private final List<ChatMessage> chatHistory;
    private final TextWrapper textWrapper;

    public ChatMessageManager(TextWrapper textWrapper) {
        this.messages = new ArrayList<>();
        this.chatHistory = new ArrayList<>();
        this.textWrapper = textWrapper;
    }

    /**
     * Add a message with default white color
     */
    public void addMessage(String text) {
        addMessage(text, new float[]{1.0f, 1.0f, 1.0f, 1.0f});
    }

    /**
     * Add a message with custom color
     */
    public void addMessage(String text, float[] color) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // Generate a unique message ID for all wrapped lines from this message
        long messageId = System.nanoTime();

        String[] wrappedLines = textWrapper.wrapText(text);
        for (String line : wrappedLines) {
            ChatMessage message = new ChatMessage(line, color, messageId);
            messages.add(message);
            addToHistory(message);
        }

        pruneOldMessages();
    }

    /**
     * Update message lifecycle (remove expired messages only when chat is closed)
     */
    public void update() {
        update(false);
    }

    /**
     * Update message lifecycle with chat state
     */
    public void update(boolean isChatOpen) {
        // Only remove old messages when chat is closed
        if (!isChatOpen) {
            Iterator<ChatMessage> iterator = messages.iterator();
            while (iterator.hasNext()) {
                ChatMessage message = iterator.next();
                if (message.shouldRemove()) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Get visible messages based on chat state
     */
    public List<ChatMessage> getVisibleMessages(boolean isChatOpen) {
        return getVisibleMessages(isChatOpen, 0);
    }

    /**
     * Get visible messages based on chat state with scroll offset
     */
    public List<ChatMessage> getVisibleMessages(boolean isChatOpen, int scrollOffset) {
        if (isChatOpen) {
            return getHistoryMessages(scrollOffset);
        } else {
            return getNonFadedMessages();
        }
    }

    /**
     * Clear all messages
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Get total message count
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Get chat history count
     */
    public int getHistoryCount() {
        return chatHistory.size();
    }

    /**
     * Get recent messages (up to MAX_VISIBLE_MESSAGES)
     */
    private List<ChatMessage> getRecentMessages() {
        return getRecentMessages(0);
    }

    /**
     * Get recent messages with scroll offset (up to MAX_VISIBLE_MESSAGES)
     */
    private List<ChatMessage> getRecentMessages(int scrollOffset) {
        List<ChatMessage> visible = new ArrayList<>();

        // Calculate the range of messages to display
        // scrollOffset = 0 means showing the most recent messages
        // scrollOffset > 0 means scrolling up in history
        int endIndex = messages.size() - scrollOffset;
        int startIndex = Math.max(0, endIndex - MAX_VISIBLE_MESSAGES);

        // Clamp endIndex to valid range
        endIndex = Math.min(endIndex, messages.size());
        endIndex = Math.max(endIndex, 0);

        for (int i = startIndex; i < endIndex; i++) {
            visible.add(messages.get(i));
        }
        return visible;
    }

    /**
     * Get history messages with scroll offset (up to MAX_VISIBLE_MESSAGES)
     * Used when chat is open to display from the 20-message history
     */
    private List<ChatMessage> getHistoryMessages(int scrollOffset) {
        List<ChatMessage> visible = new ArrayList<>();

        // Calculate the range of messages to display from history
        // scrollOffset = 0 means showing the most recent messages
        // scrollOffset > 0 means scrolling up in history
        int endIndex = chatHistory.size() - scrollOffset;
        int startIndex = Math.max(0, endIndex - MAX_VISIBLE_MESSAGES);

        // Clamp endIndex to valid range
        endIndex = Math.min(endIndex, chatHistory.size());
        endIndex = Math.max(endIndex, 0);

        for (int i = startIndex; i < endIndex; i++) {
            visible.add(chatHistory.get(i));
        }
        return visible;
    }

    /**
     * Get messages that haven't faded yet
     */
    private List<ChatMessage> getNonFadedMessages() {
        List<ChatMessage> visible = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.getAlpha() > 0.0f) {
                visible.add(message);
            }
        }

        // Limit to most recent visible messages
        if (visible.size() > MAX_VISIBLE_MESSAGES) {
            visible = visible.subList(visible.size() - MAX_VISIBLE_MESSAGES, visible.size());
        }

        return visible;
    }

    /**
     * Remove old messages when exceeding limit
     */
    private void pruneOldMessages() {
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    /**
     * Add message to chat history, maintaining max history size
     */
    private void addToHistory(ChatMessage message) {
        chatHistory.add(message);

        // Prune history to maintain max size
        while (chatHistory.size() > MAX_HISTORY_MESSAGES) {
            chatHistory.remove(0);
        }
    }

    /**
     * Get chat history (past 20 messages)
     */
    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }
}
