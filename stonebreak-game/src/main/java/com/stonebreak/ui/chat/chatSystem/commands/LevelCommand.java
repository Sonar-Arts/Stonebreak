package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to set the player's character level.
 * Usage: /level          - Show the current level
 *        /level <level>  - Set the level (1 or higher)
 */
public class LevelCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        CharacterStats stats = Game.getPlayer().getCharacterStats();

        if (args.length == 0) {
            messageManager.addMessage("Current level: " + stats.getLevel(), ChatColors.YELLOW);
            showUsage(messageManager);
            return;
        }

        try {
            int level = Integer.parseInt(args[0]);
            if (level < 1) {
                messageManager.addMessage("Level must be 1 or higher", ChatColors.RED);
                return;
            }
            stats.setLevel(level);
            messageManager.addMessage("Level set to " + level, ChatColors.GREEN);
        } catch (NumberFormatException e) {
            messageManager.addMessage("Invalid level: " + args[0], ChatColors.RED);
            showUsage(messageManager);
        }
    }

    @Override
    public String getName() {
        return "level";
    }

    @Override
    public String getDescription() {
        return "Set your character level (/level <level>)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /level <level>", ChatColors.YELLOW);
    }
}
