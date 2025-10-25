package com.openmason.ui.components.modelBrowser;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.ui.components.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.components.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.components.modelBrowser.events.*;
import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.StatusService;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller for the Model Browser component.
 *
 * <p>This class follows the Single Responsibility Principle by focusing on coordinating
 * model browser business logic. It manages state, handles user actions, and notifies
 * listeners of events using the Observer pattern.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Coordinates model browser operations</li>
 *   <li>Open/Closed: New event types can be added without modifying existing code</li>
 *   <li>Dependency Inversion: Depends on listener interface, not concrete implementations</li>
 * </ul>
 *
 * <p>Following KISS: Simple coordination logic, delegates complex operations to services.</p>
 */
public class ModelBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserController.class);

    private final ModelBrowserState state;
    private final List<ModelBrowserListener> listeners;
    private final ModelOperationService modelOperationService;
    private final StatusService statusService;

    /**
     * Creates a new Model Browser controller.
     *
     * @param modelOperationService Service for model operations
     * @param statusService Service for status updates
     */
    public ModelBrowserController(ModelOperationService modelOperationService, StatusService statusService) {
        this.state = new ModelBrowserState();
        this.listeners = new ArrayList<>();
        this.modelOperationService = modelOperationService;
        this.statusService = statusService;
    }

    /**
     * Gets the state object.
     *
     * @return The model browser state
     */
    public ModelBrowserState getState() {
        return state;
    }

    /**
     * Adds a listener to be notified of model browser events.
     *
     * @param listener The listener to add
     */
    public void addListener(ModelBrowserListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a listener from event notifications.
     *
     * @param listener The listener to remove
     */
    public void removeListener(ModelBrowserListener listener) {
        listeners.remove(listener);
    }

    /**
     * Handles block selection by the user.
     *
     * @param blockType The selected block type
     */
    public void selectBlock(BlockType blockType) {
        try {
            // Update state
            String displayName = BlockManager.getDisplayName(blockType);
            state.setSelectedModelInfo("Selected: " + displayName + " (Block)");

            // Update status
            statusService.updateStatus("Loaded block: " + displayName);

            // Notify listeners
            BlockSelectedEvent event = new BlockSelectedEvent(blockType);
            for (ModelBrowserListener listener : listeners) {
                listener.onBlockSelected(event);
            }

            logger.debug("Block selected: {}", blockType);
        } catch (Exception e) {
            logger.error("Failed to select block: " + blockType, e);
            statusService.updateStatus("Error loading block: " + e.getMessage());
        }
    }

    /**
     * Handles item selection by the user.
     *
     * @param itemType The selected item type
     */
    public void selectItem(ItemType itemType) {
        try {
            // Update state
            String displayName = ItemManager.getDisplayName(itemType);
            state.setSelectedModelInfo("Selected: " + displayName + " (Item)");

            // Update status
            statusService.updateStatus("Loaded item: " + displayName);

            // Notify listeners
            ItemSelectedEvent event = new ItemSelectedEvent(itemType);
            for (ModelBrowserListener listener : listeners) {
                listener.onItemSelected(event);
            }

            logger.debug("Item selected: {}", itemType);
        } catch (Exception e) {
            logger.error("Failed to select item: " + itemType, e);
            statusService.updateStatus("Error loading item: " + e.getMessage());
        }
    }

    /**
     * Handles model selection by the user.
     *
     * @param modelName The selected model name
     */
    public void selectModel(String modelName) {
        try {
            // Update state
            state.setSelectedModelInfo("Selected: " + modelName + " (Model)");
            state.addRecentFile(modelName);

            // Update status
            statusService.updateStatus("Loading model: " + modelName);

            // Use model operation service for actual loading (with default variant)
            modelOperationService.selectModel(modelName, "default");

            // Notify listeners
            ModelSelectedEvent event = new ModelSelectedEvent(modelName);
            for (ModelBrowserListener listener : listeners) {
                listener.onModelSelected(event);
            }

            logger.debug("Model selected: {}", modelName);
        } catch (Exception e) {
            logger.error("Failed to select model: " + modelName, e);
            statusService.updateStatus("Error loading model: " + e.getMessage());
        }
    }

    /**
     * Gets all available blocks categorized for display.
     *
     * @return Map of categories to lists of blocks
     */
    public Map<BlockCategorizer.Category, List<BlockType>> getCategorizedBlocks() {
        List<BlockType> allBlocks = BlockManager.getAvailableBlocks();
        return BlockCategorizer.categorizeAll(allBlocks);
    }

    /**
     * Gets all available items categorized for display.
     *
     * @return Map of categories to lists of items
     */
    public Map<ItemCategorizer.Category, List<ItemType>> getCategorizedItems() {
        List<ItemType> allItems = ItemManager.getVoxelizableItems();
        return ItemCategorizer.categorizeAll(allItems);
    }

    /**
     * Filters blocks based on current search text.
     *
     * @param blocks The list of blocks to filter
     * @return Filtered list of blocks matching search criteria
     */
    public List<BlockType> filterBlocks(List<BlockType> blocks) {
        if (!state.isSearchActive()) {
            return blocks;
        }

        List<BlockType> filtered = new ArrayList<>();
        for (BlockType block : blocks) {
            String displayName = BlockManager.getDisplayName(block);
            if (state.matchesSearch(displayName)) {
                filtered.add(block);
            }
        }
        return filtered;
    }

    /**
     * Filters items based on current search text.
     *
     * @param items The list of items to filter
     * @return Filtered list of items matching search criteria
     */
    public List<ItemType> filterItems(List<ItemType> items) {
        if (!state.isSearchActive()) {
            return items;
        }

        List<ItemType> filtered = new ArrayList<>();
        for (ItemType item : items) {
            String displayName = ItemManager.getDisplayName(item);
            if (state.matchesSearch(displayName)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /**
     * Handles loading a recent file.
     *
     * @param fileName The recent file name to load
     */
    public void loadRecentFile(String fileName) {
        try {
            modelOperationService.loadRecentFile(fileName);
            selectModel(fileName);
        } catch (Exception e) {
            logger.error("Failed to load recent file: " + fileName, e);
            statusService.updateStatus("Error loading recent file: " + e.getMessage());
        }
    }

    /**
     * Resets the controller state to defaults.
     */
    public void reset() {
        state.reset();
        logger.debug("Model browser state reset");
    }
}
