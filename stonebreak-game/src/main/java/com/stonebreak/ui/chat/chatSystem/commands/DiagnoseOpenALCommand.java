package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

/**
 * Prints the native audio engine's state (device, voices, loaded sounds). Keeps its old
 * {@code diagnose_openal} name and description: the chat fidelity gate renders both (frozen captures).
 */
public class DiagnoseOpenALCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatsEnabled(messageManager)) {
            return;
        }

        messageManager.addMessage("Running audio engine diagnosis...", ChatColors.CYAN);
        messageManager.addMessage("Check console for detailed information!", ChatColors.YELLOW);
        Game.getSoundSystem().testBasicFunctionality();
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
