package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.battle.stage.FocusBattle;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import java.util.List;

/** Entry point to the Focus battle proof of concept (Monk vs Ice Archon, staged in the ice arena). */
public final class BattleCommand implements ChatCommand {
    @Override
    public String getName() {
        return "battle";
    }
    @Override
    public String getDescription() {
        return "Start the Focus battle: /battle [stop|leave] (singleplayer)";
    }
    @Override
    public void execute(String[] args, ChatMessageManager messages) {
        try {
            String result;
            if (args.length == 0)
                result = FocusBattle.start();
            else if (args.length == 1 && args[0].equalsIgnoreCase("stop"))
                result = FocusBattle.stop() ? "Focus battle stopped. /battle leave returns to the world."
                                            : "No Focus battle is running.";
            else if (args.length == 1 && args[0].equalsIgnoreCase("leave"))
                result = FocusBattle.leave() ? "Returned from Frostbound Crucible."
                                             : "No Focus battle or battle arena is active.";
            else
                result = "Usage: /battle [stop|leave]";
            messages.addMessage(result, ChatColors.WHITE);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(BattleCommand.class).error("Focus battle command failed", e);
            messages.addMessage("Focus battle command failed: " + e.getMessage(), ChatColors.RED);
        }
    }
    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        return List.of("stop", "leave")
            .stream()
            .filter(s -> s.startsWith(currentArg.toLowerCase(java.util.Locale.ROOT)))
            .toList();
    }
}
