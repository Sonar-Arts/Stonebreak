package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.world.MoonCycle;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command to set the time of day.
 * Usage: /timeset <time|preset>
 * Numbers are clock times, the same ones the game shows (debug panel, this command's reply):
 *   /timeset 18         - 6:00 PM (a bare number is the hour, 24-hour clock)
 *   /timeset 14:30      - 2:30 PM
 *   /timeset 6pm        - 6:00 PM (also 6:30am, "6 pm")
 *   /timeset day        - noon (also night/midnight, dawn/sunrise, dusk/sunset)
 *   /timeset ticks 6000 - raw tick value (0-23999, where 0 is 6:00 AM)
 *   /timeset moon 4     - Jump to day 4 of the world (full moon), keeping the time of day
 */
public class TimeSetCommand implements ChatCommand {

    // H, HH, H:MM or HH:MM, optionally followed by am/pm.
    private static final Pattern CLOCK_TIME = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?");

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

        if (timeArg.equals("moon")) {
            setDay(args, timeOfDay, messageManager);
            return;
        }
        if (timeArg.equals("ticks")) {
            setTicks(args, timeOfDay, messageManager);
            return;
        }

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

        // Clock time; "6 pm" arrives as two args.
        String clockText = String.join(" ", args).toLowerCase();
        long ticks = parseClockTime(clockText);
        if (ticks < 0) {
            messageManager.addMessage("Invalid time: " + clockText, ChatColors.RED);
            showUsage(messageManager);
            return;
        }
        applyTime(ticks, null, timeOfDay, messageManager);
    }

    /**
     * Ticks for a clock time ({@code 18}, {@code 14:30}, {@code 6pm}, {@code 6:30 am}), or -1 if
     * {@code text} isn't one. Without am/pm the hour is 24-hour (0-23); with it, 1-12.
     */
    static long parseClockTime(String text) {
        Matcher m = CLOCK_TIME.matcher(text.trim().toLowerCase());
        if (!m.matches()) {
            return -1;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        String period = m.group(3);
        if (period != null) {
            if (hour < 1 || hour > 12) {
                return -1;
            }
            hour = hour % 12 + (period.equals("pm") ? 12 : 0);
        }
        if (hour > 23 || minute > 59) {
            return -1;
        }
        return TimeOfDay.ticksAtClock(hour, minute);
    }

    /** {@code /timeset ticks <0-23999>}: the raw tick scale, where 0 is 6:00 AM. */
    private void setTicks(String[] args, TimeOfDay timeOfDay, ChatMessageManager messageManager) {
        long ticks;
        try {
            ticks = args.length > 1 ? Long.parseLong(args[1]) : -1;
        } catch (NumberFormatException e) {
            ticks = -1;
        }
        if (ticks < 0 || ticks >= TimeOfDay.TICKS_PER_DAY) {
            messageManager.addMessage("Usage: /timeset ticks <0-" + (TimeOfDay.TICKS_PER_DAY - 1)
                + "> (0 = 6:00 AM, " + TimeOfDay.NOON + " = noon)", ChatColors.RED);
            return;
        }
        applyTime(ticks, null, timeOfDay, messageManager);
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
        if (!setAbsolute(timeOfDay.getDay() * TimeOfDay.TICKS_PER_DAY + ticks, timeOfDay, messageManager)) {
            return;
        }
        messageManager.addMessage(successMsg != null ? successMsg
            : "Time set to " + timeOfDay.getTimeString() + " (tick " + ticks + ")",
            ChatColors.GREEN);
    }

    /** {@code /timeset moon <day>}: the moon's phase follows the day count, so this picks the phase. */
    private void setDay(String[] args, TimeOfDay timeOfDay, ChatMessageManager messageManager) {
        long day;
        try {
            day = args.length > 1 ? Long.parseLong(args[1]) : -1;
        } catch (NumberFormatException e) {
            day = -1;
        }
        if (day < 0) {
            messageManager.addMessage("Usage: /timeset moon <day> (day 0 = new moon, "
                + MoonCycle.CYCLE_DAYS / 2 + " = full)", ChatColors.RED);
            return;
        }
        if (day > TimeOfDay.MAX_DAY) {
            messageManager.addMessage("Day must be at most " + TimeOfDay.MAX_DAY, ChatColors.RED);
            return;
        }
        if (setAbsolute(day * TimeOfDay.TICKS_PER_DAY + timeOfDay.getTicks(), timeOfDay, messageManager)) {
            messageManager.addMessage("Day " + day + ": "
                + MoonCycle.phaseName(MoonCycle.phase(timeOfDay)), ChatColors.GREEN);
        }
    }

    /** Sets the server's clock (it is authoritative) and the local one for instant feedback. */
    private boolean setAbsolute(long ticks, TimeOfDay timeOfDay, ChatMessageManager messageManager) {
        if (!com.stonebreak.network.MultiplayerSession.requestServerTimeSet(ticks)) {
            messageManager.addMessage("Time is server-controlled — only the host can set it.",
                ChatColors.RED);
            return false;
        }
        timeOfDay.setTicks(ticks);
        return true;
    }

    @Override
    public String getName() {
        return "timeset";
    }

    @Override
    public String getDescription() {
        return "Set the time of day (/timeset <14:30|6pm|day|night|dawn|dusk>)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        if (args.length == 0) {
            // Suggest presets when first argument is being typed
            List<String> presets = Arrays.asList("day", "night", "dawn", "dusk", "noon", "midnight", "moon", "ticks");
            return presets.stream()
                .filter(s -> s.startsWith(currentArg.toLowerCase()))
                .toList();
        }
        return List.of();
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /timeset <time>", ChatColors.YELLOW);
        messageManager.addMessage("  Clock time: 18, 14:30, 6pm, 6:30am", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  Presets: day, night, dawn, dusk", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset ticks <0-23999> sets raw ticks (0 = 6:00 AM)", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset moon <day> picks the moon's phase", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("Examples:", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset day", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /timeset 14:30", ChatColors.LIGHT_GRAY);
    }
}
