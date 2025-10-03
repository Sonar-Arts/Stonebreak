package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to test 3D audio at near distance (2 blocks)
 */
public class Test3DNearCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        messageManager.addMessage("Testing 3D audio at 2 blocks distance...", ChatColors.CYAN);
        Game.getSoundSystem().testSingle3DAudio(2.0f);
    }

    @Override
    public String getName() {
        return "test_3d_near";
    }

    @Override
    public String getDescription() {
        return "Test 3D audio at 2 blocks distance";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
