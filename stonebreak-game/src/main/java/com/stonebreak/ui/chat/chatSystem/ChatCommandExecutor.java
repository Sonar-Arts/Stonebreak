package com.stonebreak.ui.chat.chatSystem;

import com.stonebreak.ui.chat.chatSystem.commands.*;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes chat commands using a registry pattern.
 * Follows Open/Closed Principle - easy to add new commands without modifying existing code.
 */
public class ChatCommandExecutor {
    private final Map<String, ChatCommand> commands;
    private final ChatMessageManager messageManager;

    public ChatCommandExecutor(ChatMessageManager messageManager) {
        this.messageManager = messageManager;
        this.commands = new HashMap<>();
        registerCommands();
    }

    /**
     * Register all available commands
     */
    private void registerCommands() {
        registerCommand(new CheatsCommand());
        registerCommand(new FindCommand());
        registerCommand(new FlyCommand());
        registerCommand(new HelpCommand());
        registerCommand(new ObtainCommand());
        registerCommand(new SpawnSoundEmitCommand());
        registerCommand(new Test3DAudioCommand());
        registerCommand(new Test3DNearCommand());
        registerCommand(new Test3DFarCommand());
        registerCommand(new DiagnoseOpenALCommand());
        registerCommand(new VoxelAdjCommand());
        registerCommand(new TimeSetCommand());
    }

    /**
     * Register a command
     */
    private void registerCommand(ChatCommand command) {
        commands.put(command.getName().toLowerCase(), command);
    }

    /**
     * Execute a command string
     */
    public void executeCommand(String commandString) {
        if (commandString == null || !commandString.startsWith("/")) {
            return;
        }

        String[] parts = commandString.substring(1).split(" ");
        if (parts.length == 0) {
            return;
        }

        String commandName = parts[0].toLowerCase();
        String[] args = extractArgs(parts);

        ChatCommand command = commands.get(commandName);
        if (command != null) {
            command.execute(args, messageManager);
        } else {
            messageManager.addMessage("Unknown command: /" + commandName, ChatColors.RED);
        }
    }

    /**
     * Extract arguments from command parts
     */
    private String[] extractArgs(String[] parts) {
        if (parts.length <= 1) {
            return new String[0];
        }

        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return args;
    }

    /**
     * Get all registered commands (for help/autocomplete)
     */
    public Map<String, ChatCommand> getCommands() {
        return new HashMap<>(commands);
    }

    /**
     * Get matching command names for autocomplete
     * @param prefix The command prefix (without leading slash)
     * @return List of matching command names
     */
    public java.util.List<String> getMatchingCommands(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return commands.keySet().stream()
            .filter(cmd -> cmd.startsWith(lowerPrefix))
            .sorted()
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get autocomplete suggestions for command arguments
     * @param commandName The command name
     * @param args Current arguments (excluding command name)
     * @param currentArg The argument currently being typed
     * @return List of suggestions for the current argument
     */
    public java.util.List<String> getArgumentSuggestions(String commandName, String[] args, String currentArg) {
        ChatCommand command = commands.get(commandName.toLowerCase());
        if (command == null) {
            return java.util.Collections.emptyList();
        }
        return command.getAutocompleteSuggestions(args, currentArg);
    }
}
