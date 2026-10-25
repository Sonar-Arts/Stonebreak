package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.weather.WeatherMode;
import com.stonebreak.world.weather.WeatherZone;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sets the world's weather.
 * Usage: /weather <clear|cloudy|rain|storm|auto>
 *
 * <p>Weather is server-authoritative like the clock: the mode routes to the
 * server, which persists it and broadcasts it, and every client eases its sky
 * toward it. {@code auto} hands the weather back to the world's own cycle.</p>
 */
public class WeatherCommand implements ChatCommand {

    /** Second argument that sets the weather of the whole world instead of the region around you. */
    private static final String WORLD = "world";

    private static final List<String> MODES = Arrays.stream(WeatherMode.values())
            .map(m -> m.name().toLowerCase(Locale.ROOT))
            .toList();

    private static final String USAGE = "Usage: /weather <" + String.join("|", MODES) + "> [" + WORLD + "]";

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }
        WeatherMode mode = args.length >= 1 && args.length <= 2 ? WeatherMode.parse(args[0]) : null;
        boolean world = args.length == 2 && args[1].equalsIgnoreCase(WORLD);
        if (mode == null || (args.length == 2 && !world)) {
            messageManager.addMessage(USAGE, ChatColors.YELLOW);
            return;
        }
        if (!com.stonebreak.network.MultiplayerSession.requestServerWeatherSet(mode, world)) {
            messageManager.addMessage("Weather is server-controlled — only the host can set it.",
                    ChatColors.RED);
            return;
        }
        String name = mode.name().toLowerCase(Locale.ROOT);
        if (world) {
            messageManager.addMessage("Weather set to " + name + " everywhere", ChatColors.GREEN);
        } else if (mode == WeatherMode.AUTO) {
            messageManager.addMessage("Weather here returns to normal", ChatColors.GREEN);
        } else {
            messageManager.addMessage("Weather set to " + name + " within "
                    + Math.round(WeatherZone.DEFAULT_RADIUS) + " blocks", ChatColors.GREEN);
        }
    }

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "Set the weather around you, or everywhere with \"" + WORLD + "\" (/weather <"
                + String.join("|", MODES) + "> [" + WORLD + "])";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        if (args.length == 0) {
            return MODES.stream().filter(s -> s.startsWith(currentArg.toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 1 && WORLD.startsWith(currentArg.toLowerCase(Locale.ROOT))) {
            return List.of(WORLD);
        }
        return List.of();
    }
}
