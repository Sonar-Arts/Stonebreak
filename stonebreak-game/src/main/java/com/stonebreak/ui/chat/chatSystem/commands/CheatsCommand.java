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
                case 1 -> apply(true, messageManager);
                case 0 -> apply(false, messageManager);
                default -> showUsage(messageManager);
            }
        } catch (NumberFormatException e) {
            showUsage(messageManager);
        }
    }

    private void handleToggleMode(ChatMessageManager messageManager) {
        apply(!Game.getInstance().isCheatsEnabled(), messageManager);
    }

    /** Cheats are a per-world setting owned by the server; only the host may change them. */
    private void apply(boolean enabled, ChatMessageManager messageManager) {
        if (!Game.getInstance().applyCheatsToCurrentWorld(enabled)) {
            messageManager.addMessage("Cheats are server-controlled — only the host can change them.",
                ChatColors.RED);
            return;
        }
        if (enabled) {
            messageManager.addMessage("Cheats enabled!", ChatColors.GREEN);
        } else {
            messageManager.addMessage("Cheats disabled!", ChatColors.ORANGE);
        }
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /cheats <1|0>", ChatColors.RED);
    }
}
