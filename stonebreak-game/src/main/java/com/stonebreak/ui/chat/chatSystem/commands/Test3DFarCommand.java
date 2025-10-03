package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to test 3D audio at far distance (60 blocks)
 */
public class Test3DFarCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        messageManager.addMessage("Testing 3D audio at 60 blocks distance...", ChatColors.CYAN);
        Game.getSoundSystem().testSingle3DAudio(60.0f);
    }

    @Override
    public String getName() {
        return "test_3d_far";
    }

    @Override
    public String getDescription() {
        return "Test 3D audio at 60 blocks distance";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
