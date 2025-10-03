package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to enable/disable flight
 */
public class FlyCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        Player player = Game.getPlayer();

        if (args.length >= 1) {
            handleParameterMode(args, messageManager, player);
        } else {
            handleToggleMode(messageManager, player);
        }
    }

    @Override
    public String getName() {
        return "fly";
    }

    @Override
    public String getDescription() {
        return "Enable or disable flight (/fly or /fly <1|0>)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    private void handleParameterMode(String[] args, ChatMessageManager messageManager, Player player) {
        try {
            int value = Integer.parseInt(args[0]);
            switch (value) {
                case 1 -> {
                    player.setFlightEnabled(true);
                    messageManager.addMessage("Flight enabled! Double-tap space to fly", ChatColors.GREEN);
                }
                case 0 -> {
                    player.setFlightEnabled(false);
                    messageManager.addMessage("Flight disabled!", ChatColors.ORANGE);
                }
                default -> showUsage(messageManager);
            }
        } catch (NumberFormatException e) {
            showUsage(messageManager);
        }
    }

    private void handleToggleMode(ChatMessageManager messageManager, Player player) {
        boolean currentState = player.isFlightEnabled();
        player.setFlightEnabled(!currentState);

        if (!currentState) {
            messageManager.addMessage("Flight enabled! Double-tap space to fly", ChatColors.GREEN);
        } else {
            messageManager.addMessage("Flight disabled!", ChatColors.ORANGE);
        }
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /fly <1|0>", ChatColors.RED);
    }
}
