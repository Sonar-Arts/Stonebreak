package com.stonebreak.ui.chat.chatSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for wrapping text to fit within character limits.
 * Follows Single Responsibility Principle and provides reusable text wrapping logic.
 */
public class TextWrapper {
    private static final int DEFAULT_MAX_LENGTH = 60;

    private final int maxLength;

    public TextWrapper() {
        this(DEFAULT_MAX_LENGTH);
    }

    public TextWrapper(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * Wrap text to fit within maxLength characters per line
     */
    public String[] wrapText(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }

        if (text.length() <= maxLength) {
            return new String[]{text};
        }

        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (shouldStartNewLine(currentLine, word)) {
                finalizeLine(lines, currentLine);
                handleLongWord(lines, currentLine, word);
            } else {
                appendWord(currentLine, word);
            }
        }

        finalizeLine(lines, currentLine);

        return lines.toArray(String[]::new);
    }

    /**
     * Check if word should start a new line
     */
    private boolean shouldStartNewLine(StringBuilder currentLine, String word) {
        return currentLine.length() + word.length() + 1 > maxLength;
    }

    /**
     * Finalize current line and add to list
     */
    private void finalizeLine(List<String> lines, StringBuilder currentLine) {
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
            currentLine.setLength(0);
        }
    }

    /**
     * Handle words longer than maxLength by splitting them
     */
    private void handleLongWord(List<String> lines, StringBuilder currentLine, String word) {
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
    }

    /**
     * Append word to current line with space if needed
     */
    private void appendWord(StringBuilder currentLine, String word) {
        if (currentLine.length() > 0) {
            currentLine.append(" ");
        }
        currentLine.append(word);
    }

    /**
     * Get maximum line length
     */
    public int getMaxLength() {
        return maxLength;
    }
}
