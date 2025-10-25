package com.openmason.ui.components.modelBrowser.categorizers;

import com.stonebreak.blocks.BlockType;

import java.util.*;

/**
 * Categorizes blocks into logical groups for the Model Browser.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * block categorization logic. It provides a pure function that maps BlockTypes to
 * categories, making it easily testable and reusable.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Only handles block categorization</li>
 *   <li>Open/Closed: New categories can be added without modifying existing code</li>
 * </ul>
 *
 * <p>Following KISS: Simple string matching logic, easy to understand and maintain.</p>
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
     *
     * <p>This is a pure function - same input always produces same output,
     * no side effects, making it easy to test and reason about.</p>
     *
     * @param blockType The block type to categorize
     * @return The category this block belongs to
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
     *
     * <p>This method organizes blocks into a map where keys are categories and
     * values are lists of blocks in that category. Following DRY principle by
     * reusing the categorize() method.</p>
     *
     * @param blocks The list of blocks to categorize
     * @return A map of categories to lists of blocks in each category
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

    /**
     * Gets all blocks in a specific category from a list of blocks.
     *
     * @param blocks The list of blocks to filter
     * @param category The category to filter by
     * @return A list of blocks in the specified category
     */
    public static List<BlockType> getBlocksInCategory(List<BlockType> blocks, Category category) {
        List<BlockType> filtered = new ArrayList<>();
        for (BlockType block : blocks) {
            if (categorize(block) == category) {
                filtered.add(block);
            }
        }
        return filtered;
    }
}
