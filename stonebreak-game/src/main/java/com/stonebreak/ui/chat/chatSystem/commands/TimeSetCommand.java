package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

import java.util.Arrays;
import java.util.List;

/**
 * Command to set the time of day.
 * Usage: /timeset <time|preset>
 * Examples:
 *   /timeset day      - Set to noon
 *   /timeset night    - Set to midnight
 *   /timeset dawn     - Set to dawn
 *   /timeset dusk     - Set to dusk
 *   /timeset 6000     - Set to specific tick value (0-23999)
 */
public class TimeSetCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        if (args.length == 0) {
            showUsage(messageManager);
            return;
        }

        TimeOfDay timeOfDay = Game.getTimeOfDay();
        if (timeOfDay == null) {
            messageManager.addMessage("Time system not initialized!", ChatColors.RED);
            return;
        }

        String timeArg = args[0].toLowerCase();

        // Handle preset time names
        switch (timeArg) {
            case "day", "noon" -> {
                applyTime(TimeOfDay.NOON, "Time set to noon (12:00 PM)", timeOfDay, messageManager);
                return;
            }
            case "night", "midnight" -> {
                applyTime(TimeOfDay.MIDNIGHT, "Time set to midnight (12:00 AM)", timeOfDay, messageManager);
                return;
            }
            case "dawn", "sunrise" -> {
                applyTime(TimeOfDay.DAWN, "Time set to dawn (6:00 AM)", timeOfDay, messageManager);
                return;
            }
            case "dusk", "sunset" -> {
                applyTime(TimeOfDay.DUSK, "Time set to dusk (6:00 PM)", timeOfDay, messageManager);
                return;
            }
        }

        // Handle numeric tick values
        try {
            long ticks = Long.parseLong(timeArg);

            if (ticks < 0 || ticks >= TimeOfDay.TICKS_PER_DAY) {
                messageManager.addMessage("Tick value must be between 0 and " +
                    (TimeOfDay.TICKS_PER_DAY - 1), ChatColors.RED);
                return;
            }

            applyTime(ticks, null, timeOfDay, messageManager);

        } catch (NumberFormatException e) {
            messageManager.addMessage("Invalid time value: " + timeArg, ChatColors.RED);
            showUsage(messageManager);
        }
    }

    /**
     * Applies a time set: the day/night clock is SERVER-authoritative, so the set must
     * route to the server (otherwise the next periodic TimeSyncS2C snaps the local clock
     * right back — the old "command succeeds, then reverts after a second" bug). The local
     * display clock is also set for instant feedback; the server's echo broadcast confirms.
     * On a remote JOIN client the set is refused (host-only).
     */
    private void applyTime(long ticks, String successMsg, TimeOfDay timeOfDay,
                           ChatMessageManager messageManager) {
        if (!com.stonebreak.network.MultiplayerSession.requestServerTimeSet(ticks)) {
            messageManager.addMessage("Time is server-controlled — only the host can set it.",
                ChatColors.RED);
            return;
        }
        timeOfDay.setTicks(ticks);
        messageManager.addMessage(successMsg != null ? successMsg
            : "Time set to " + ticks + " ticks (" + timeOfDay.getTimeString() + ")",
            ChatColors.GREEN);
    }

    @Override
    public String getName() {
        return "timeset";
    }

    @Override
    public String getDescription() {
        return "Set the time of day (/timeset <day|night|dawn|dusk|ticks>)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        if (args.length == 0) {
            // Suggest presets when first argument is being typed
            List<String> presets = Arrays.asList("day", "night", "dawn", "dusk", "noon", "midnight");
            return presets.stream()
                .filter(s -> s.startsWith(currentArg.toLowerCase()))
                .toList();
        }
        return List.of();
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /timeset <time>", ChatColors.YELLOW);
        messageManager.addMessage("  Presets: day, night, dawn, dusk", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  Or use tick value (0-23999)", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("Examples:", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset day", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset 6000", ChatColors.LIGHT_GRAY);
    }
}
