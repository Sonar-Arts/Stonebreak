package com.stonebreak.ui.chat.chatSystem;

import com.stonebreak.core.Game;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * Handles all chat input operations including character input, backspace, copy, and paste.
 * Follows Single Responsibility Principle by focusing solely on input management.
 */
public class ChatInputHandler {
    private static final int MAX_INPUT_LENGTH = 256;
    private static final char MIN_VALID_CHAR = 32;
    private static final char MAX_VALID_CHAR = 126;

    private final StringBuilder currentInput;

    public ChatInputHandler() {
        this.currentInput = new StringBuilder();
    }

    /**
     * Handle character input with validation
     */
    public void handleCharInput(char character) {
        if (isValidCharacter(character) && hasSpaceForInput()) {
            currentInput.append(character);
        }
    }

    /**
     * Handle backspace operation
     */
    public void handleBackspace() {
        if (currentInput.length() > 0) {
            currentInput.setLength(currentInput.length() - 1);
        }
    }

    /**
     * Handle paste from clipboard
     */
    public void handlePaste() {
        try {
            long window = Game.getInstance().getWindow();
            if (window == 0) {
                return;
            }

            String clipboardText = glfwGetClipboardString(window);
            if (clipboardText == null || clipboardText.isEmpty()) {
                return;
            }

            String filteredText = filterText(clipboardText);
            appendWithLimit(filteredText);

        } catch (Exception e) {
            System.err.println("Failed to paste from clipboard: " + e.getMessage());
        }
    }

    /**
     * Copy current input to clipboard
     */
    public void handleCopy() {
        String text = getCurrentInput();
        if (!text.isEmpty()) {
            copyToClipboard(text);
        }
    }

    /**
     * Copy arbitrary text to clipboard
     */
    public void copyToClipboard(String text) {
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0 && text != null && !text.isEmpty()) {
                glfwSetClipboardString(window, text);
            }
        } catch (Exception e) {
            System.err.println("Failed to copy to clipboard: " + e.getMessage());
        }
    }

    /**
     * Clear all input
     */
    public void clear() {
        currentInput.setLength(0);
    }

    /**
     * Get current input as string
     */
    public String getCurrentInput() {
        return currentInput.toString();
    }

    /**
     * Check if character is valid for chat input
     */
    private boolean isValidCharacter(char character) {
        return character >= MIN_VALID_CHAR && character <= MAX_VALID_CHAR;
    }

    /**
     * Check if there's space for more input
     */
    private boolean hasSpaceForInput() {
        return currentInput.length() < MAX_INPUT_LENGTH;
    }

    /**
     * Filter text to only allow valid characters
     */
    private String filterText(String text) {
        StringBuilder filtered = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isValidCharacter(c)) {
                filtered.append(c);
            }
        }
        return filtered.toString();
    }

    /**
     * Append text with length limit
     */
    private void appendWithLimit(String text) {
        int availableSpace = MAX_INPUT_LENGTH - currentInput.length();
        if (availableSpace <= 0) {
            return;
        }

        if (text.length() > availableSpace) {
            text = text.substring(0, availableSpace);
        }

        currentInput.append(text);
    }
}
