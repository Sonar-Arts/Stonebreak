package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Command to spawn items in the player's inventory — executed ON THE SERVER (issue #334):
 * the client forwards the raw line, the server checks its own cheats flag, resolves the
 * item name ({@link com.stonebreak.items.ItemLookup}) and adds to the server-owned
 * inventory replica.
 *
 * Usage: /obtain <stonebreak:item_name|item_name> [count]
 *
 * Examples:
 * - /obtain stonebreak:grass
 * - /obtain grass 64
 * - /obtain stonebreak:wooden_pickaxe 1
 * - /obtain STONE 32
 */
public class ObtainCommand implements ChatCommand {

    private static final String PREFIX = "stonebreak:";
    /** Largest count the server accepts (mirrors ServerCommandHandler.MAX_OBTAIN_COUNT). */
    private static final int MAX_COUNT = 999;

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // Not reached in-world: executedOnServer() forwards the raw line and the SERVER
        // executes it against its own cheats flag and inventory (issue #334). Kept as a
        // fallback for no-session contexts only.
        messageManager.addMessage("No server session — /obtain runs on the server.",
                ChatColors.RED);
    }

    @Override
    public String getName() {
        return "obtain";
    }

    @Override
    public String getDescription() {
        return "Spawn items in inventory (/obtain <stonebreak:item> [count])";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public boolean executedOnServer() {
        return true;
    }


    /**
     * Common count presets offered when the count argument is being typed.
     * 64 (a full stack) leads so it is the default suggestion for empty input;
     * the rest cover single items, smaller/larger stacks, and the maximum.
     */
    private static final int[] COUNT_SUGGESTIONS = {64, 1, 8, 16, 32, 128, 256, 512, MAX_COUNT};

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        // Second argument (count) - suggest common stack sizes.
        if (args.length == 1) {
            return getCountSuggestions(currentArg);
        }

        // Only the first argument (item name) and the count are completable.
        if (args.length > 1) {
            return Collections.emptyList();
        }

        // First argument (item name).
        // Remove stonebreak: prefix if present for matching
        String searchTerm = currentArg;
        boolean hasPrefix = searchTerm.toLowerCase().startsWith(PREFIX);
        if (hasPrefix) {
            searchTerm = searchTerm.substring(PREFIX.length());
        }

        List<String> suggestions = new ArrayList<>();
        String lowerSearch = searchTerm.toLowerCase();

        // Add matching block types
        for (BlockType blockType : BlockType.values()) {
            if (blockType == BlockType.AIR) {
                continue; // Skip AIR
            }

            String enumName = blockType.name();
            String displayName = blockType.getName();

            // Match against enum name (lowercase)
            if (enumName.toLowerCase().startsWith(lowerSearch)) {
                suggestions.add(PREFIX + enumName.toLowerCase());
            }
            // Match against display name (case-insensitive)
            else if (displayName.toLowerCase().startsWith(lowerSearch)) {
                // Convert display name to lowercase with underscores for consistency
                String underscoreName = displayName.toLowerCase().replace(" ", "_");
                suggestions.add(PREFIX + underscoreName);
            }
        }

        // Add matching item types
        for (ItemType itemType : ItemType.values()) {
            String enumName = itemType.name();
            String displayName = itemType.getName();

            // Match against enum name (lowercase)
            if (enumName.toLowerCase().startsWith(lowerSearch)) {
                suggestions.add(PREFIX + enumName.toLowerCase());
            }
            // Match against display name (case-insensitive)
            else if (displayName.toLowerCase().startsWith(lowerSearch)) {
                // Convert display name to lowercase with underscores for consistency
                String underscoreName = displayName.toLowerCase().replace(" ", "_");
                suggestions.add(PREFIX + underscoreName);
            }
        }

        // Sort suggestions alphabetically
        suggestions.sort(String::compareTo);

        // Limit to reasonable number of suggestions
        if (suggestions.size() > 20) {
            suggestions = suggestions.subList(0, 20);
        }

        return suggestions;
    }

    /**
     * Suggest common count presets, filtered by what has been typed so far.
     * Matching is prefix-based so typing "1" offers 1, 16, 128, etc.
     */
    private List<String> getCountSuggestions(String currentArg) {
        // Only suggest counts for numeric (or empty) input.
        if (!currentArg.isEmpty() && !currentArg.chars().allMatch(Character::isDigit)) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();
        for (int count : COUNT_SUGGESTIONS) {
            String value = Integer.toString(count);
            if (value.startsWith(currentArg)) {
                suggestions.add(value);
            }
        }
        return suggestions;
    }
}
