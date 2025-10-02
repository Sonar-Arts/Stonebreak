package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

/**
 * Command to enable/disable cheats
 */
public class CheatsCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (args.length >= 1) {
            handleParameterMode(args, messageManager);
        } else {
            handleToggleMode(messageManager);
        }
    }

    @Override
    public String getName() {
        return "cheats";
    }

    @Override
    public String getDescription() {
        return "Enable or disable cheats (/cheats or /cheats <1|0>)";
    }

    private void handleParameterMode(String[] args, ChatMessageManager messageManager) {
        try {
            int value = Integer.parseInt(args[0]);
            switch (value) {
                case 1 -> {
                    Game.getInstance().setCheatsEnabled(true);
                    messageManager.addMessage("Cheats enabled!", ChatColors.GREEN);
                }
                case 0 -> {
                    Game.getInstance().setCheatsEnabled(false);
                    messageManager.addMessage("Cheats disabled!", ChatColors.ORANGE);
                }
                default -> showUsage(messageManager);
            }
        } catch (NumberFormatException e) {
            showUsage(messageManager);
        }
    }

    private void handleToggleMode(ChatMessageManager messageManager) {
        boolean currentState = Game.getInstance().isCheatsEnabled();
        Game.getInstance().setCheatsEnabled(!currentState);

        if (!currentState) {
            messageManager.addMessage("Cheats enabled!", ChatColors.GREEN);
        } else {
            messageManager.addMessage("Cheats disabled!", ChatColors.ORANGE);
        }
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /cheats <1|0>", ChatColors.RED);
    }
}
