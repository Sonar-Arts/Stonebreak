package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to test 3D audio at various distances
 */
public class Test3DAudioCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        messageManager.addMessage(
            "Starting 3D audio test with sounds at 2, 10, 25, and 60 blocks...",
            ChatColors.CYAN
        );
        messageManager.addMessage(
            "Check console for detailed debug information!",
            ChatColors.YELLOW
        );

        // Run test in separate thread to avoid blocking
        new Thread(() -> Game.getSoundSystem().test3DAudio()).start();
    }

    @Override
    public String getName() {
        return "test_3d_audio";
    }

    @Override
    public String getDescription() {
        return "Test 3D audio at multiple distances";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
