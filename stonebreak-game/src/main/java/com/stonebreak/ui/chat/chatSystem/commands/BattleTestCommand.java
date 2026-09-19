package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.battletest.BattleTestSession;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import java.util.List;

/** The sole entry point to the temporary combat-test arena; no menu/world-list registration. */
public final class BattleTestCommand implements ChatCommand {
    @Override
    public String getName() {
        return "battletest";
    }
    @Override
    public String getDescription() {
        return "Visit the ice arena: /battletest [reset|leave] (singleplayer)";
    }
    @Override
    public void execute(String[] args, ChatMessageManager messages) {
        try {
            String result;
            if (args.length == 0)
                result = BattleTestSession.enter();
            else if (args.length == 1 && args[0].equalsIgnoreCase("leave"))
                result = BattleTestSession.leave() ? "Returned from Frostbound Crucible."
                                                   : "No battle test is active.";
            else if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
                var session = BattleTestSession.current();
                if (session == null)
                    result = "Enter with /battletest first.";
                else {
                    session.reset();
                    result = "Returned to the cyan player spawn. Violet marks the future Ice Archon spawn.";
                }
            } else
                result = "Usage: /battletest [reset|leave]";
            messages.addMessage(result, ChatColors.WHITE);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BattleTestCommand.class)
                .error("Unable to enter battle arena", e);
            messages.addMessage("Could not load the battle arena: " + e.getMessage(), ChatColors.RED);
        }
    }
    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        return List.of("reset", "leave")
            .stream()
            .filter(s -> s.startsWith(currentArg.toLowerCase(java.util.Locale.ROOT)))
            .toList();
    }
}
