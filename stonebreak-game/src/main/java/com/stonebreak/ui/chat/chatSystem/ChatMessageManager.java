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

    private final List<ChatMessage> messages;
    private final TextWrapper textWrapper;

    public ChatMessageManager(TextWrapper textWrapper) {
        this.messages = new ArrayList<>();
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

        String[] wrappedLines = textWrapper.wrapText(text);
        for (String line : wrappedLines) {
            messages.add(new ChatMessage(line, color));
        }

        pruneOldMessages();
    }

    /**
     * Update message lifecycle (remove expired messages)
     */
    public void update() {
        Iterator<ChatMessage> iterator = messages.iterator();
        while (iterator.hasNext()) {
            ChatMessage message = iterator.next();
            if (message.shouldRemove()) {
                iterator.remove();
            }
        }
    }

    /**
     * Get visible messages based on chat state
     */
    public List<ChatMessage> getVisibleMessages(boolean isChatOpen) {
        if (isChatOpen) {
            return getRecentMessages();
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
     * Get recent messages (up to MAX_VISIBLE_MESSAGES)
     */
    private List<ChatMessage> getRecentMessages() {
        List<ChatMessage> visible = new ArrayList<>();
        int startIndex = Math.max(0, messages.size() - MAX_VISIBLE_MESSAGES);
        for (int i = startIndex; i < messages.size(); i++) {
            visible.add(messages.get(i));
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
}
