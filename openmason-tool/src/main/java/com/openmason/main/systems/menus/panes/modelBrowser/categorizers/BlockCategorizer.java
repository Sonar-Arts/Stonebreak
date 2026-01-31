package com.openmason.main.systems.menus.panes.modelBrowser.categorizers;

import com.stonebreak.blocks.BlockType;

import java.util.*;

/**
 * Categorizes blocks into logical groups for the Model Browser.
 */
public class BlockCategorizer {

    /**
     * Block categories for organization in the Model Browser.
     */
    public enum Category {
        TERRAIN("Terrain Blocks"),
        ORE("Ore Blocks"),
        WOOD("Wood Blocks"),
        PLANTS("Plants"),
        OTHER("Other Blocks");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Categorizes a block based on its name.
     */
    public static Category categorize(BlockType blockType) {
        String name = blockType.name().toUpperCase();

        // Check for ore blocks
        if (name.contains("ORE")) {
            return Category.ORE;
        }

        // Check for wood-related blocks
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK") ||
            name.contains("PINE") || name.contains("ELM")) {
            return Category.WOOD;
        }

        // Check for plant blocks
        if (name.contains("LEAVES") || name.contains("DANDELION") || name.contains("ROSE")) {
            return Category.PLANTS;
        }

        // Check for terrain blocks
        if (name.contains("DIRT") || name.contains("GRASS") || name.contains("STONE") ||
            name.contains("SAND") || name.contains("GRAVEL") || name.contains("CLAY")) {
            return Category.TERRAIN;
        }

        // Default to OTHER
        return Category.OTHER;
    }

    /**
     * Groups a list of blocks by their categories.
     */
    public static Map<Category, List<BlockType>> categorizeAll(List<BlockType> blocks) {
        Map<Category, List<BlockType>> categorized = new EnumMap<>(Category.class);

        // Initialize all categories with empty lists
        for (Category category : Category.values()) {
            categorized.put(category, new ArrayList<>());
        }

        // Categorize each block
        for (BlockType block : blocks) {
            Category category = categorize(block);
            categorized.get(category).add(block);
        }

        return categorized;
    }
}
