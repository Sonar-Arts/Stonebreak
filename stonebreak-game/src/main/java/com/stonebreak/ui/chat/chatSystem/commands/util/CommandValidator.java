package com.stonebreak.ui.chat.chatSystem.commands.util;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;

/**
 * Utility class for common command validations.
 * Follows DRY principle by centralizing validation logic.
 */
public class CommandValidator {

    private CommandValidator() {
        // Utility class - no instantiation
    }

    /**
     * Validate that cheats are enabled
     */
    public static boolean validateCheatsEnabled(ChatMessageManager messageManager) {
        if (!Game.getInstance().isCheatsEnabled()) {
            messageManager.addMessage("Cheats must be enabled first! Use /cheats", ChatColors.RED);
            return false;
        }
        return true;
    }

    /**
     * Validate that world is loaded
     */
    public static boolean validateWorldLoaded(ChatMessageManager messageManager) {
        if (Game.getWorld() == null) {
            messageManager.addMessage("Cannot execute - no world loaded!", ChatColors.RED);
            return false;
        }
        return true;
    }

    /**
     * Validate that player exists
     */
    public static boolean validatePlayerExists(ChatMessageManager messageManager) {
        if (Game.getPlayer() == null) {
            messageManager.addMessage("Player not found!", ChatColors.RED);
            return false;
        }
        return true;
    }

    /**
     * Combined validation for commands requiring cheats, world, and player
     */
    public static boolean validateCheatCommand(ChatMessageManager messageManager) {
        return validateCheatsEnabled(messageManager) &&
               validateWorldLoaded(messageManager) &&
               validatePlayerExists(messageManager);
    }
}
