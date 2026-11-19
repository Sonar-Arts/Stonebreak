package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGeneratorType;

/**
 * Reports the current world's EXP-2 climate layout (read-only). The layout is part of the world's identity, like
 * the seed: it is chosen when the world is created and can never be changed afterwards, so this command takes no
 * arguments. The client's world carries the server world's layout (sent in {@code WelcomeS2C}).
 *
 * Usage: /climate
 */
public class ClimateCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        World world = Game.getWorld();
        if (world == null) {
            messageManager.addMessage("No world loaded.", ChatColors.RED);
            return;
        }
        var layout = world.getClimateLayout();
        if (world.getGeneratorType() == TerrainGeneratorType.DIFFUSION) {
            messageManager.addMessage("Climate layout: " + layout.label() + " (" + layout.value() + ")",
                ChatColors.YELLOW);
        } else {
            messageManager.addMessage("Climate layout: " + layout.label() + " (unused by "
                + world.getGeneratorType().displayName() + ")", ChatColors.YELLOW);
        }
        if (args.length > 0) {
            messageManager.addMessage("The climate layout is fixed when a world is created.", ChatColors.LIGHT_GRAY);
        }
    }

    @Override
    public String getName() {
        return "climate";
    }

    @Override
    public String getDescription() {
        return "Show this world's climate layout (/climate)";
    }
}
