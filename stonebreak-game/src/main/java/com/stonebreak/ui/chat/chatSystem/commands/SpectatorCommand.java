package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to toggle spectator mode (flight + noclip).
 */
public class SpectatorCommand implements ChatCommand {

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
        return "spectator";
    }

    @Override
    public String getDescription() {
        return "Toggle spectator mode (flight + noclip) (/spectator or /spectator <1|0>)";
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
                    player.setSpectator(true);
                    messageManager.addMessage("Spectator mode enabled! You can fly through blocks", ChatColors.CYAN);
                }
                case 0 -> {
                    boolean wasInsideBlock = player.isPlayerInsideSolidBlock();
                    player.setSpectator(false);

                    if (wasInsideBlock) {
                        messageManager.addMessage("Spectator mode disabled! WARNING: You're inside a block!", ChatColors.ORANGE);
                    } else {
                        messageManager.addMessage("Spectator mode disabled!", ChatColors.GREEN);
                    }
                }
                default -> showUsage(messageManager);
            }
        } catch (NumberFormatException e) {
            showUsage(messageManager);
        }
    }

    private void handleToggleMode(ChatMessageManager messageManager, Player player) {
        boolean currentState = player.isSpectator();

        if (!currentState) {
            player.setSpectator(true);
            messageManager.addMessage("Spectator mode enabled! You can fly through blocks", ChatColors.CYAN);
        } else {
            boolean wasInsideBlock = player.isPlayerInsideSolidBlock();
            player.setSpectator(false);

            if (wasInsideBlock) {
                messageManager.addMessage("Spectator mode disabled! WARNING: You're inside a block!", ChatColors.ORANGE);
            } else {
                messageManager.addMessage("Spectator mode disabled!", ChatColors.GREEN);
            }
        }
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /spectator <1|0>", ChatColors.RED);
    }
}
