package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Command to diagnose OpenAL 3D audio
 */
public class DiagnoseOpenALCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatsEnabled(messageManager)) {
            return;
        }

        messageManager.addMessage("Running OpenAL 3D audio diagnosis...", ChatColors.CYAN);
        messageManager.addMessage("Check console for detailed information!", ChatColors.YELLOW);
        Game.getSoundSystem().diagnoseOpenAL3D();
    }

    @Override
    public String getName() {
        return "diagnose_openal";
    }

    @Override
    public String getDescription() {
        return "Run OpenAL 3D audio diagnostics";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
