package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

import java.util.Map;

/**
 * Command to list all available commands
 */
public class HelpCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Get all commands from the executor
        Map<String, ChatCommand> commands = Game.getInstance().getChatSystem().getCommandExecutor().getCommands();

        messageManager.addMessage("=== Available Commands ===", ChatColors.CYAN);

        // Sort commands alphabetically for better readability
        commands.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                ChatCommand command = entry.getValue();
                String commandInfo = "/" + command.getName() + " - " + command.getDescription();

                // Mark cheat-only commands in red
                if (command.requiresCheats()) {
                    messageManager.addMessage(commandInfo + " [Requires Cheats]", ChatColors.RED);
                } else {
                    messageManager.addMessage(commandInfo, ChatColors.WHITE);
                }
            });

        messageManager.addMessage("======================", ChatColors.CYAN);
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "Lists all available commands";
    }
}
