package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.weather.WeatherMode;

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

    private static final List<String> MODES = Arrays.stream(WeatherMode.values())
            .map(m -> m.name().toLowerCase(Locale.ROOT))
            .toList();

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }
        WeatherMode mode = args.length == 1 ? WeatherMode.parse(args[0]) : null;
        if (mode == null) {
            messageManager.addMessage("Usage: /weather <" + String.join("|", MODES) + ">", ChatColors.YELLOW);
            return;
        }
        if (!com.stonebreak.network.MultiplayerSession.requestServerWeatherSet(mode)) {
            messageManager.addMessage("Weather is server-controlled — only the host can set it.",
                    ChatColors.RED);
            return;
        }
        messageManager.addMessage("Weather set to " + mode.name().toLowerCase(Locale.ROOT), ChatColors.GREEN);
    }

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "Set the weather (/weather <" + String.join("|", MODES) + ">)";
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
        return List.of();
    }
}
