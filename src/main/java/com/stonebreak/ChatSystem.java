package com.stonebreak;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

public class ChatSystem {
    private static final int MAX_MESSAGES = 100; // Like Minecraft
    private static final int MAX_VISIBLE_MESSAGES = 10; // Messages shown at once
    
    private final List<ChatMessage> messages;
    private boolean isOpen;
    private StringBuilder currentInput;
    private float blinkTimer;
    private boolean showCursor;
    
    public ChatSystem() {
        this.messages = new ArrayList<>();
        this.isOpen = false;
        this.currentInput = new StringBuilder();
        this.blinkTimer = 0.0f;
        this.showCursor = true;
    }
    
    public void addMessage(String text) {
        addMessage(text, new float[]{1.0f, 1.0f, 1.0f, 1.0f});
    }
    
    public void addMessage(String text, float[] color) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        
        // Split long messages into multiple lines if needed
        String[] lines = wrapText(text, 60); // Approximate character limit per line
        
        for (String line : lines) {
            messages.add(new ChatMessage(line, color));
        }
        
        // Remove old messages if we exceed the limit
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }
    
    public void openChat() {
        if (!isOpen) {
            isOpen = true;
            currentInput.setLength(0);
            blinkTimer = 0.0f;
            showCursor = true;
        }
    }
    
    public void closeChat() {
        isOpen = false;
        currentInput.setLength(0);
    }
    
    public boolean isOpen() {
        return isOpen;
    }
    
    public void update(float deltaTime) {
        // Remove expired messages
        Iterator<ChatMessage> iterator = messages.iterator();
        while (iterator.hasNext()) {
            ChatMessage message = iterator.next();
            if (message.shouldRemove()) {
                iterator.remove();
            }
        }
        
        // Update cursor blink
        if (isOpen) {
            blinkTimer += deltaTime;
            if (blinkTimer >= 1.0f) { // Blink every second
                showCursor = !showCursor;
                blinkTimer = 0.0f;
            }
        }
    }
    
    public void handleCharInput(char character) {
        if (!isOpen) {
            return;
        }
        
        // Filter out control characters
        if (character >= 32 && character <= 126) {
            currentInput.append(character);
        }
    }
    
    public void handleBackspace() {
        if (!isOpen || currentInput.length() == 0) {
            return;
        }
        
        currentInput.setLength(currentInput.length() - 1);
    }
    
    public void handleEnter() {
        if (!isOpen) {
            return;
        }
        
        String message = currentInput.toString().trim();
        if (!message.isEmpty()) {
            addMessage("<Player> " + message);
        }
        
        closeChat();
    }
    
    public String getCurrentInput() {
        return currentInput.toString();
    }
    
    public String getDisplayInput() {
        String input = getCurrentInput();
        if (isOpen && showCursor) {
            return input + "_";
        }
        return input;
    }
    
    public List<ChatMessage> getVisibleMessages() {
        List<ChatMessage> visible = new ArrayList<>();
        
        if (isOpen) {
            // When chat is open, show all recent messages (up to MAX_VISIBLE_MESSAGES)
            int startIndex = Math.max(0, messages.size() - MAX_VISIBLE_MESSAGES);
            for (int i = startIndex; i < messages.size(); i++) {
                visible.add(messages.get(i));
            }
        } else {
            // When chat is closed, only show messages that haven't faded yet
            for (ChatMessage message : messages) {
                if (message.getAlpha() > 0.0f) {
                    visible.add(message);
                }
            }
            
            // Limit to most recent visible messages
            if (visible.size() > MAX_VISIBLE_MESSAGES) {
                visible = visible.subList(visible.size() - MAX_VISIBLE_MESSAGES, visible.size());
            }
        }
        
        return visible;
    }
    
    private String[] wrapText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return new String[]{text};
        }
        
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        
        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > maxLength) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }
                
                // Handle very long words
                if (word.length() > maxLength) {
                    while (word.length() > maxLength) {
                        lines.add(word.substring(0, maxLength));
                        word = word.substring(maxLength);
                    }
                    if (!word.isEmpty()) {
                        currentLine.append(word);
                    }
                } else {
                    currentLine.append(word);
                }
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }
        
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        
        return lines.toArray(new String[0]);
    }
}