package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.water.BasinWaterFiller;
import com.stonebreak.world.generation.water.basin.BasinWaterLevelGrid;

/**
 * Command to display lake generation statistics.
 * Helps diagnose why lakes might not be generating by showing filter rejection rates.
 */
public class LakeStatsCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatsEnabled(messageManager)) {
            return;
        }

        World world = Game.getWorld();
        if (world == null) {
            messageManager.addMessage("No world loaded!", ChatColors.RED);
            return;
        }

        TerrainGenerationSystem terrainSystem = world.getTerrainGenerationSystem();
        if (terrainSystem == null) {
            messageManager.addMessage("Terrain generation system not available!", ChatColors.RED);
            return;
        }

        BasinWaterFiller basinFiller = terrainSystem.getBasinWaterFiller();
        if (basinFiller == null) {
            messageManager.addMessage("Basin water filler not available!", ChatColors.RED);
            return;
        }

        BasinWaterLevelGrid basinGrid = basinFiller.getBasinWaterLevelGrid();
        if (basinGrid == null) {
            messageManager.addMessage("Basin water level grid not available!", ChatColors.RED);
            return;
        }

        messageManager.addMessage("=== Lake Generation Diagnostics ===", ChatColors.CYAN);
        messageManager.addMessage("Analyzing basin detection filters...", ChatColors.YELLOW);

        // Log stats to console
        basinGrid.logStats();

        messageManager.addMessage("Detailed statistics logged to console!", ChatColors.GREEN);
        messageManager.addMessage("Look for 'Success Rate' and 'Rim Detection' percentages.", ChatColors.YELLOW);
    }

    @Override
    public String getName() {
        return "lakestats";
    }

    @Override
    public String getDescription() {
        return "Display lake generation diagnostics (shows filter rejection rates)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
