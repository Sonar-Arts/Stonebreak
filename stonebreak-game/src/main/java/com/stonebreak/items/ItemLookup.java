package com.stonebreak.items;

/**
 * Resolves a user-typed item name to an {@link Item} — shared by the client command's
 * autocomplete-adjacent parsing and the server-side /obtain executor, so both accept the
 * same spellings (enum name, display name, {@code stonebreak:}-prefixed, underscores).
 */
public final class ItemLookup {

    private static final String PREFIX = "stonebreak:";

    private ItemLookup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Strips a leading {@code stonebreak:} prefix, if present. */
    public static String parseName(String input) {
        if (input != null && input.toLowerCase().startsWith(PREFIX)) {
            return input.substring(PREFIX.length());
        }
        return input;
    }

    /**
     * Finds an item by name: enum names ({@code GRASS}, {@code WOODEN_PICKAXE}), display
     * names ({@code Grass}, {@code Wooden Pickaxe}) and underscore spellings
     * ({@code wooden_pickaxe}). Null when nothing matches.
     */
    public static Item find(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        try {
            return com.stonebreak.blocks.BlockType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            // not an enum name — continue
        }

        try {
            return ItemType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            // not an enum name — continue
        }

        com.stonebreak.blocks.BlockType blockByName = com.stonebreak.blocks.BlockType.getByName(name);
        if (blockByName != null) {
            return blockByName;
        }
        ItemType itemByName = ItemType.getByName(name);
        if (itemByName != null) {
            return itemByName;
        }

        String converted = convertUnderscoresToSpaces(name);
        blockByName = com.stonebreak.blocks.BlockType.getByName(converted);
        if (blockByName != null) {
            return blockByName;
        }
        return ItemType.getByName(converted);
    }

    /** "wooden_pickaxe" → "Wooden Pickaxe" */
    private static String convertUnderscoresToSpaces(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String[] words = input.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
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
}
