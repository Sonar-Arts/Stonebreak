package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

import java.util.List;

/**
 * Whether players may hurt each other on this world (issue #343) — executed ON THE SERVER: the
 * client forwards the raw line, the server checks who may change the rule (the host, or anyone
 * while cheats are on), persists it with the world and tells every client.
 *
 * Usage: /pvp [on|off]   (no argument reports the rule)
 */
public class PvpCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Not reached in-world: executedOnServer() forwards the line to the server.
        messageManager.addMessage("No server session — /pvp runs on the server.", ChatColors.RED);
    }

    @Override
    public String getName() {
        return "pvp";
    }

    @Override
    public String getDescription() {
        return "Let players hurt each other (/pvp [on|off])";
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
