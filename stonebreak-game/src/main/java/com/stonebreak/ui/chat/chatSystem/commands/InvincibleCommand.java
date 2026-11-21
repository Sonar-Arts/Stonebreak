package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

import java.util.List;

/**
 * Makes the sender take no damage — executed ON THE SERVER, which owns player health (#343):
 * the client forwards the raw line, the server checks its own cheats flag and flags the sender
 * so every hit (falls, cactus, fire, mobs, arrows, PvP) lands for nothing. Lasts until turned
 * off or the player leaves.
 *
 * Usage: /invincible [on|off]   (no argument toggles)
 */
public class InvincibleCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Not reached in-world: executedOnServer() forwards the line to the server.
        messageManager.addMessage("No server session — /invincible runs on the server.", ChatColors.RED);
    }

    @Override
    public String getName() {
        return "invincible";
    }

    @Override
    public String getDescription() {
        return "Take no damage (/invincible [on|off])";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public boolean executedOnServer() {
        return true;
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        if (args.length > 1) {
            return List.of();
        }
        String prefix = currentArg == null ? "" : currentArg.toLowerCase();
        return List.of("on", "off").stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
