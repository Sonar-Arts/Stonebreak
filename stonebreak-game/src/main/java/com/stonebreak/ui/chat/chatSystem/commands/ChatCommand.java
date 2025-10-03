package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;

import java.util.Collections;
import java.util.List;

/**
 * Interface for all chat commands.
 * Follows Open/Closed Principle - open for extension, closed for modification.
 */
public interface ChatCommand {
    /**
     * Execute the command with given arguments
     * @param args Command arguments (excluding command name)
     * @param messageManager Message manager for output
     */
    void execute(String[] args, ChatMessageManager messageManager);

    /**
     * Get the command name (without leading slash)
     */
    String getName();

    /**
     * Get command description
     */
    String getDescription();

    /**
     * Check if command requires cheats to be enabled
     */
    default boolean requiresCheats() {
        return false;
    }

    /**
     * Get autocomplete suggestions for command arguments
     * @param args Current arguments (excluding command name)
     * @param currentArg The argument currently being typed
     * @return List of suggestions for the current argument
     */
    default List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        return Collections.emptyList();
    }
}
