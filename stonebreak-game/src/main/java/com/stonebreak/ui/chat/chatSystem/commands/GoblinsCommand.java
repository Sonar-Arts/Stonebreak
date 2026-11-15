package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

/**
 * Lists the goblin packs near the player — size, what they are doing, the food they carry, their
 * quarry and how long until they may hunt again. A debugging aid, executed ON THE SERVER where
 * the packs live; needs cheats.
 *
 * Usage: /goblins
 */
public class GoblinsCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Not reached in-world: executedOnServer() forwards the line to the server.
        messageManager.addMessage("No server session — /goblins runs on the server.", ChatColors.RED);
    }

    @Override
    public String getName() {
        return "goblins";
    }

    @Override
    public String getDescription() {
        return "List nearby goblin packs and their hunts";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public boolean executedOnServer() {
        return true;
    }
}
