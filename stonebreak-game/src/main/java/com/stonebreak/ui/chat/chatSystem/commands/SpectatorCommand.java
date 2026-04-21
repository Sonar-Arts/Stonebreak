package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Toggles spectator mode: forces flight + disables block collision.
 */
public class SpectatorCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        Player player = Game.getPlayer();
        if (args.length == 0) {
            applyState(player, !player.isSpectator(), messageManager);
            return;
        }

        switch (args[0]) {
            case "1" -> applyState(player, true, messageManager);
            case "0" -> applyState(player, false, messageManager);
            default -> messageManager.addMessage("Usage: /spectator <1|0>", ChatColors.RED);
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

    private void applyState(Player player, boolean enable, ChatMessageManager messageManager) {
        if (enable) {
            player.setSpectator(true);
            messageManager.addMessage("Spectator mode enabled! You can fly through blocks", ChatColors.CYAN);
            return;
        }

        boolean stuck = player.isPlayerInsideSolidBlock();
        player.setSpectator(false);
        if (stuck) {
            messageManager.addMessage("Spectator mode disabled! WARNING: You're inside a block!", ChatColors.ORANGE);
        } else {
            messageManager.addMessage("Spectator mode disabled!", ChatColors.GREEN);
        }
    }
}
