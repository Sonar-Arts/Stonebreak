package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import org.joml.Vector3f;

/**
 * Command to spawn a sound emitter
 */
public class SpawnSoundEmitCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        Player player = Game.getPlayer();
        Vector3f playerPos = player.getPosition();
        Vector3f spawnPos = new Vector3f(playerPos.x, playerPos.y + 1.0f, playerPos.z);

        var emitterManager = Game.getSoundEmitterManager();
        if (emitterManager != null) {
            emitterManager.spawnBlockPickupEmitter(spawnPos);
            messageManager.addMessage(
                String.format("Sound emitter spawned at (%.1f, %.1f, %.1f)",
                    spawnPos.x, spawnPos.y, spawnPos.z),
                ChatColors.GREEN
            );
            messageManager.addMessage(
                "Enable debug mode to see the yellow wireframe triangle!",
                ChatColors.YELLOW
            );
        } else {
            messageManager.addMessage("Sound emitter system not available!", ChatColors.RED);
        }
    }

    @Override
    public String getName() {
        return "spawn_soundemit";
    }

    @Override
    public String getDescription() {
        return "Spawn a sound emitter at player location";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
