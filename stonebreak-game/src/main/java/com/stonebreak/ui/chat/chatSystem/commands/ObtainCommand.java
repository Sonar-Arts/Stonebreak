package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Command to spawn items and blocks in the player's inventory.
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
    private static final int DEFAULT_COUNT = 1;
    private static final int MAX_COUNT = 999;

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        if (args.length == 0) {
            showUsage(messageManager);
            return;
        }

        Player player = Game.getPlayer();
        Inventory inventory = player.getInventory();

        // Parse item name
        String itemName = parseItemName(args[0]);

        // Parse count (default to 1)
        int count = DEFAULT_COUNT;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
                if (count <= 0) {
                    messageManager.addMessage("Count must be greater than 0!", ChatColors.RED);
                    return;
                }
                if (count > MAX_COUNT) {
                    messageManager.addMessage("Count too large! Maximum is " + MAX_COUNT, ChatColors.RED);
                    return;
                }
            } catch (NumberFormatException e) {
                messageManager.addMessage("Invalid count: " + args[1], ChatColors.RED);
                return;
            }
        }

        // Try to find the item
        Item item = findItem(itemName);

        if (item == null) {
            messageManager.addMessage("Unknown item: " + itemName, ChatColors.RED);
            messageManager.addMessage("Try using the enum name (e.g., GRASS) or display name (e.g., \"Grass\")", ChatColors.ORANGE);
            return;
        }

        // Add item to inventory
        boolean success = inventory.addItem(item, count);

        if (success) {
            messageManager.addMessage("Gave " + count + "x " + item.getName() + " to player", ChatColors.GREEN);
        } else {
            messageManager.addMessage("Could not add all items - inventory may be full", ChatColors.ORANGE);
            messageManager.addMessage("Some items may have been added", ChatColors.ORANGE);
        }
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

    /**
     * Parse item name by removing stonebreak: prefix if present
     */
    private String parseItemName(String input) {
        if (input.toLowerCase().startsWith(PREFIX)) {
            input = input.substring(PREFIX.length());
        }
        return input;
    }

    /**
     * Find an item by name, checking both BlockType and ItemType.
     * Supports enum names (GRASS, WOODEN_PICKAXE) and display names (Grass, Wooden Pickaxe).
     */
    private Item findItem(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        // Try as enum name first (e.g., GRASS, WOODEN_PICKAXE)
        try {
            BlockType blockType = BlockType.valueOf(name.toUpperCase());
            if (blockType != null) {
                return blockType;
            }
        } catch (IllegalArgumentException e) {
            // Not a valid enum name, continue
        }

        try {
            ItemType itemType = ItemType.valueOf(name.toUpperCase());
            if (itemType != null) {
                return itemType;
            }
        } catch (IllegalArgumentException e) {
            // Not a valid enum name, continue
        }

        // Try as display name (e.g., "Grass", "Wooden Pickaxe")
        BlockType blockByName = BlockType.getByName(name);
        if (blockByName != null) {
            return blockByName;
        }

        ItemType itemByName = ItemType.getByName(name);
        if (itemByName != null) {
            return itemByName;
        }

        // Try converting underscores to spaces (e.g., "wooden_pickaxe" -> "Wooden Pickaxe")
        String convertedName = convertUnderscoresToSpaces(name);
        blockByName = BlockType.getByName(convertedName);
        if (blockByName != null) {
            return blockByName;
        }

        itemByName = ItemType.getByName(convertedName);
        if (itemByName != null) {
            return itemByName;
        }

        return null;
    }

    /**
     * Convert underscores to spaces and capitalize words
     * Example: "wooden_pickaxe" -> "Wooden Pickaxe"
     */
    private String convertUnderscoresToSpaces(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String[] words = input.split("_");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                // Capitalize first letter, lowercase rest
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }

                if (i < words.length - 1) {
                    result.append(" ");
                }
            }
        }

        return result.toString();
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /obtain <stonebreak:item> [count]", ChatColors.RED);
        messageManager.addMessage("Examples:", ChatColors.ORANGE);
        messageManager.addMessage("  /obtain stonebreak:grass", ChatColors.ORANGE);
        messageManager.addMessage("  /obtain grass 64", ChatColors.ORANGE);
        messageManager.addMessage("  /obtain WOODEN_PICKAXE 1", ChatColors.ORANGE);
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        // Only provide autocomplete for the first argument (item name)
        if (args.length > 1) {
            return Collections.emptyList();
        }

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
}
