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
    private int autocompleteIndex = -1; // Track current autocomplete suggestion index
    private java.util.List<String> autocompleteSuggestions = null; // Cache of current suggestions

    public ChatInputHandler() {
        this.currentInput = new StringBuilder();
    }

    /**
     * Handle character input with validation
     */
    public void handleCharInput(char character) {
        if (isValidCharacter(character) && hasSpaceForInput()) {
            currentInput.append(character);
            resetAutocomplete(); // Reset autocomplete when typing
        }
    }

    /**
     * Handle backspace operation
     */
    public void handleBackspace() {
        if (currentInput.length() > 0) {
            currentInput.setLength(currentInput.length() - 1);
            resetAutocomplete(); // Reset autocomplete when deleting
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
        resetAutocomplete();
    }

    /**
     * Get current input as string
     */
    public String getCurrentInput() {
        return currentInput.toString();
    }

    /**
     * Get ghost text suggestion for autocomplete
     * @param commandExecutor The command executor to get suggestions from
     * @return The ghost text to display, or empty string if no suggestion
     */
    public String getGhostText(ChatCommandExecutor commandExecutor) {
        String input = getCurrentInput();

        // Only show ghost text if input starts with '/'
        if (!input.startsWith("/") || input.isEmpty()) {
            return "";
        }

        // Extract command name (everything after '/' until first space or end)
        String commandPart = input.substring(1); // Remove leading '/'
        int spaceIndex = commandPart.indexOf(' ');

        if (spaceIndex == -1) {
            // No arguments yet - show command name suggestions
            if (commandPart.isEmpty()) {
                return "";
            }

            // Get matching commands
            java.util.List<String> matches = commandExecutor.getMatchingCommands(commandPart);

            if (matches.isEmpty()) {
                return "";
            }

            // Return the first match (or show the current autocomplete suggestion if cycling)
            String suggestion;
            if (autocompleteSuggestions != null && !autocompleteSuggestions.isEmpty() && autocompleteIndex >= 0) {
                // Show current cycling suggestion
                suggestion = autocompleteSuggestions.get(autocompleteIndex);
            } else {
                // Show first match
                suggestion = matches.get(0);
            }

            // Only return the part that extends beyond current input
            if (suggestion.startsWith(commandPart)) {
                return suggestion.substring(commandPart.length());
            }

            return "";
        } else {
            // Has arguments - show argument suggestions
            String commandName = commandPart.substring(0, spaceIndex);
            String argsString = commandPart.substring(spaceIndex + 1);

            // Parse arguments
            String[] args = argsString.isEmpty() ? new String[0] : argsString.split(" ");
            String currentArg = args.length > 0 ? args[args.length - 1] : "";

            // Get argument suggestions
            java.util.List<String> matches = commandExecutor.getArgumentSuggestions(
                commandName,
                args.length > 1 ? java.util.Arrays.copyOf(args, args.length - 1) : new String[0],
                currentArg
            );

            if (matches.isEmpty()) {
                return "";
            }

            // Return the first match (or show the current autocomplete suggestion if cycling)
            String suggestion;
            if (autocompleteSuggestions != null && !autocompleteSuggestions.isEmpty() && autocompleteIndex >= 0) {
                // Show current cycling suggestion
                suggestion = autocompleteSuggestions.get(autocompleteIndex);
            } else {
                // Show first match
                suggestion = matches.get(0);
            }

            // Only return the part that extends beyond current argument
            if (suggestion.startsWith(currentArg)) {
                return suggestion.substring(currentArg.length());
            }

            return "";
        }
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

    /**
     * Handle Tab key for command autocomplete
     * @param commandExecutor The command executor to get suggestions from
     * @return true if autocomplete was performed, false otherwise
     */
    public boolean handleTab(ChatCommandExecutor commandExecutor) {
        String input = getCurrentInput();

        // Only autocomplete if input starts with '/'
        if (!input.startsWith("/")) {
            return false;
        }

        // Extract command name (everything after '/' until first space or end)
        String commandPart = input.substring(1); // Remove leading '/'
        int spaceIndex = commandPart.indexOf(' ');

        if (spaceIndex == -1) {
            // No arguments yet - autocomplete command name
            // Get or update suggestions
            if (autocompleteSuggestions == null) {
                autocompleteSuggestions = commandExecutor.getMatchingCommands(commandPart);
                autocompleteIndex = -1;
            }

            if (autocompleteSuggestions.isEmpty()) {
                return false;
            }

            // Cycle through suggestions
            autocompleteIndex = (autocompleteIndex + 1) % autocompleteSuggestions.size();
            String suggestion = autocompleteSuggestions.get(autocompleteIndex);

            // Replace current input with suggestion
            currentInput.setLength(0);
            currentInput.append('/').append(suggestion);

            return true;
        } else {
            // Has arguments - autocomplete argument
            String commandName = commandPart.substring(0, spaceIndex);
            String argsString = commandPart.substring(spaceIndex + 1);

            // Parse arguments
            String[] args = argsString.isEmpty() ? new String[0] : argsString.split(" ");
            String currentArg = args.length > 0 ? args[args.length - 1] : "";

            // Get or update suggestions
            if (autocompleteSuggestions == null) {
                autocompleteSuggestions = commandExecutor.getArgumentSuggestions(
                    commandName,
                    args.length > 1 ? java.util.Arrays.copyOf(args, args.length - 1) : new String[0],
                    currentArg
                );
                autocompleteIndex = -1;
            }

            if (autocompleteSuggestions.isEmpty()) {
                return false;
            }

            // Cycle through suggestions
            autocompleteIndex = (autocompleteIndex + 1) % autocompleteSuggestions.size();
            String suggestion = autocompleteSuggestions.get(autocompleteIndex);

            // Replace current argument with suggestion
            currentInput.setLength(0);
            currentInput.append('/').append(commandName).append(' ');

            // Add previous arguments if any
            if (args.length > 1) {
                for (int i = 0; i < args.length - 1; i++) {
                    currentInput.append(args[i]).append(' ');
                }
            }

            // Add the suggestion
            currentInput.append(suggestion);

            return true;
        }
    }

    /**
     * Reset autocomplete state
     */
    private void resetAutocomplete() {
        autocompleteIndex = -1;
        autocompleteSuggestions = null;
    }
}
