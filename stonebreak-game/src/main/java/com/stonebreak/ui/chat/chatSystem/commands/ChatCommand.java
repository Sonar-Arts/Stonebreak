package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;

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
}
