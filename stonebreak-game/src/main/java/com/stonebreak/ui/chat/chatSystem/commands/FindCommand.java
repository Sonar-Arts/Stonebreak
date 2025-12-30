package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.World;
import com.stonebreak.world.structure.StructureFinder;
import com.stonebreak.world.structure.StructureSearchConfig;
import com.stonebreak.world.structure.StructureSearchResult;
import com.stonebreak.world.structure.StructureType;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Command to find nearby structures in the world.
 * Usage: /find <structure> [radius]
 *
 * <p>Currently supported structures:</p>
 * <ul>
 *     <li><strong>lake</strong> - Elevated lakes and ponds (y >= 65)</li>
 *     <li><strong>village</strong> - Villages (not yet implemented)</li>
 *     <li><strong>temple</strong> - Temples (not yet implemented)</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <ul>
 *     <li>/find lake - Find nearest lake within 2048 blocks</li>
 *     <li>/find lake 4096 - Find nearest lake within 4096 blocks</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This command requires cheats to be enabled.</p>
 */
public class FindCommand implements ChatCommand {

    private static final int DEFAULT_RADIUS = 2048; // Reduced from 8192 for performance
    private static final int MIN_RADIUS = StructureSearchConfig.MIN_RADIUS;
    private static final int MAX_RADIUS = StructureSearchConfig.MAX_RADIUS;

    @Override
    public String getName() {
        return "find";
    }

    @Override
    public String getDescription() {
        return "Find nearby structures (/find <lake|village|temple> [radius])";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Validate cheat requirements
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        // Validate argument count
        if (args.length == 0) {
            showUsage(messageManager);
            return;
        }

        // Parse structure type
        String structureTypeName = args[0].toUpperCase();
        StructureType structureType;
        try {
            structureType = StructureType.valueOf(structureTypeName);
        } catch (IllegalArgumentException e) {
            messageManager.addMessage("Unknown structure type: " + args[0], ChatColors.RED);
            messageManager.addMessage("Available types: lake, village, temple", ChatColors.ORANGE);
            return;
        }

        // Parse optional radius
        int searchRadius = DEFAULT_RADIUS;
        if (args.length >= 2) {
            try {
                searchRadius = Integer.parseInt(args[1]);
                if (searchRadius < MIN_RADIUS || searchRadius > MAX_RADIUS) {
                    messageManager.addMessage(
                        String.format("Radius must be between %d and %d blocks", MIN_RADIUS, MAX_RADIUS),
                        ChatColors.RED
                    );
                    return;
                }
            } catch (NumberFormatException e) {
                messageManager.addMessage("Invalid radius: " + args[1], ChatColors.RED);
                return;
            }
        }

        // Get player position
        Player player = Game.getPlayer();
        Vector3f playerPos = player.getPosition();

        // Get world and structure finder
        World world = Game.getWorld();
        StructureFinder finder = world.getStructureFinder();

        // Check if structure type is supported
        if (!finder.isSupported(structureType)) {
            messageManager.addMessage(
                String.format("%s finding is not yet implemented", capitalize(structureType.name())),
                ChatColors.RED
            );
            return;
        }

        // Create search config
        StructureSearchConfig config = new StructureSearchConfig(searchRadius);

        // Notify user that search is starting
        messageManager.addMessage(
            String.format("Searching for nearest %s within %d blocks...",
                structureType.name().toLowerCase(), searchRadius),
            ChatColors.YELLOW
        );

        // Execute async search (avoids blocking game thread)
        final int finalSearchRadius = searchRadius;
        final String structureName = structureType.name().toLowerCase();

        // Debug: Log search start
        long searchStartTime = System.currentTimeMillis();
        System.out.println("[FindCommand] Starting async search for " + structureName +
                           " at (" + playerPos.x + ", " + playerPos.z + ") radius=" + searchRadius);

        finder.findNearestAsync(structureType, playerPos, config)
            .thenAccept(result -> {
                long duration = System.currentTimeMillis() - searchStartTime;
                System.out.println("[FindCommand] Search completed in " + duration + "ms");

                if (result.isPresent()) {
                    StructureSearchResult searchResult = result.get();
                    Vector3f pos = searchResult.position();

                    // Success message with coordinates
                    messageManager.addMessage(
                        String.format("Nearest %s found at: %.0f, %.0f, %.0f",
                            structureName, pos.x, pos.y, pos.z),
                        ChatColors.GREEN
                    );

                    // Distance information (optional, helpful for user)
                    if (searchResult.distance() > 0) {
                        messageManager.addMessage(
                            String.format("Distance: %.0f blocks away", searchResult.distance()),
                            ChatColors.CYAN
                        );
                    }
                } else {
                    // No structure found
                    messageManager.addMessage(
                        String.format("No %s found within %d blocks",
                            structureName, finalSearchRadius),
                        ChatColors.RED
                    );
                }
            })
            .exceptionally(ex -> {
                long duration = System.currentTimeMillis() - searchStartTime;
                System.out.println("[FindCommand] Search failed after " + duration + "ms: " + ex.getMessage());

                // Error during search
                messageManager.addMessage("Error during search: " + ex.getMessage(), ChatColors.RED);
                ex.printStackTrace();
                return null;
            });
    }

    /**
     * Shows command usage information.
     *
     * @param messageManager Message manager for output
     */
    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /find <structure> [radius]", ChatColors.ORANGE);
        messageManager.addMessage("Available structures: lake, village, temple", ChatColors.ORANGE);
        messageManager.addMessage("Example: /find lake 4096", ChatColors.ORANGE);
    }

    /**
     * Capitalizes the first letter of a string.
     *
     * @param str String to capitalize
     * @return Capitalized string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        // Autocomplete for structure types
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            for (StructureType type : StructureType.values()) {
                String typeName = type.name().toLowerCase();
                if (typeName.startsWith(currentArg.toLowerCase())) {
                    suggestions.add(typeName);
                }
            }
            return suggestions;
        }

        return Collections.emptyList();
    }
}
