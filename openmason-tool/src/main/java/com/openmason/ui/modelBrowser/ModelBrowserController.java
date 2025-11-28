package com.openmason.ui.modelBrowser;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.model.io.omo.OMOFileManager;
import com.openmason.ui.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.modelBrowser.events.BlockSelectedEvent;
import com.openmason.ui.modelBrowser.events.ItemSelectedEvent;
import com.openmason.ui.modelBrowser.events.ModelBrowserListener;
import com.openmason.ui.modelBrowser.events.ModelSelectedEvent;
import com.openmason.ui.modelBrowser.events.listeners.BlockSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ItemSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ModelSelectionListener;
import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.StatusService;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private final OMOFileManager omoFileManager;

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
        this.omoFileManager = new OMOFileManager();
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
     * Template method for handling selection events.
     * Reduces code duplication by encapsulating the common pattern of all selection handlers.
     *
     * <p>This method follows the Template Method design pattern and DRY principle by
     * extracting the common logic: update state, update status, notify listeners, log, and handle errors.</p>
     *
     * @param item The selected item (block, item, or model name)
     * @param itemType The type of item (e.g., "Block", "Item", "Model")
     * @param displayName The display name for the item
     * @param statusMessage The message to show in status (e.g., "Loaded block", "Loading model")
     * @param additionalAction Optional additional action to perform (e.g., add to recent files)
     * @param notifyListeners Consumer that notifies the appropriate listeners
     * @param <T> The type of the selected item
     */
    private <T> void handleSelection(
            T item,
            String itemType,
            String displayName,
            String statusMessage,
            Consumer<T> additionalAction,
            Consumer<List<ModelBrowserListener>> notifyListeners) {
        try {
            // Update state
            state.setSelectedModelInfo("Selected: " + displayName + " (" + itemType + ")");

            // Execute any additional actions (e.g., add to recent files)
            if (additionalAction != null) {
                additionalAction.accept(item);
            }

            // Update status
            statusService.updateStatus(statusMessage + ": " + displayName);

            // Notify listeners
            notifyListeners.accept(listeners);

            logger.debug("{} selected: {}", itemType, item);
        } catch (Exception e) {
            logger.error("Failed to select " + itemType.toLowerCase() + ": " + item, e);
            statusService.updateStatus("Error loading " + itemType.toLowerCase() + ": " + e.getMessage());
        }
    }

    /**
     * Handles block selection by the user.
     *
     * @param blockType The selected block type
     */
    public void selectBlock(BlockType blockType) {
        handleSelection(
                blockType,
                "Block",
                BlockManager.getDisplayName(blockType),
                "Loaded block",
                null, // No additional action needed
                listenerList -> {
                    BlockSelectedEvent event = new BlockSelectedEvent(blockType);
                    for (BlockSelectionListener listener : listenerList) {
                        listener.onBlockSelected(event);
                    }
                }
        );
    }

    /**
     * Handles item selection by the user.
     *
     * @param itemType The selected item type
     */
    public void selectItem(ItemType itemType) {
        handleSelection(
                itemType,
                "Item",
                ItemManager.getDisplayName(itemType),
                "Loaded item",
                null, // No additional action needed
                listenerList -> {
                    ItemSelectedEvent event = new ItemSelectedEvent(itemType);
                    for (ItemSelectionListener listener : listenerList) {
                        listener.onItemSelected(event);
                    }
                }
        );
    }

    /**
     * Handles model selection by the user.
     *
     * @param modelName The selected model name
     */
    public void selectModel(String modelName) {
        handleSelection(
                modelName,
                "Model",
                modelName,
                "Loading model",
                name -> {
                    // Additional actions for model selection
                    state.addRecentFile(name);
                    modelOperationService.selectModel(name, "default");
                },
                listenerList -> {
                    ModelSelectedEvent event = new ModelSelectedEvent(modelName);
                    for (ModelSelectionListener listener : listenerList) {
                        listener.onModelSelected(event);
                    }
                }
        );
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
     * Gets all available .OMO model files.
     *
     * @return List of OMO file entries
     */
    public List<OMOFileManager.OMOFileEntry> getOMOFiles() {
        return omoFileManager.scanForOMOFiles();
    }

    /**
     * Filters .OMO files based on current search text.
     *
     * @param omoFiles The list of OMO files to filter
     * @return Filtered list of OMO files matching search criteria
     */
    public List<OMOFileManager.OMOFileEntry> filterOMOFiles(List<OMOFileManager.OMOFileEntry> omoFiles) {
        if (!state.isSearchActive()) {
            return omoFiles;
        }

        List<OMOFileManager.OMOFileEntry> filtered = new ArrayList<>();
        for (OMOFileManager.OMOFileEntry entry : omoFiles) {
            if (state.matchesSearch(entry.getName())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Handles .OMO file selection by the user.
     * Loads the .OMO file into the viewport.
     *
     * @param entry The selected OMO file entry
     */
    public void selectOMOFile(OMOFileManager.OMOFileEntry entry) {
        if (entry == null) {
            logger.warn("Attempted to select null OMO file");
            return;
        }

        try {
            // Update state
            state.setSelectedModelInfo("Selected: " + entry.getName() + " (.OMO Model)");
            state.addRecentFile(entry.getName());

            // Load the .OMO file using ModelOperationService
            statusService.updateStatus("Loading .OMO model: " + entry.getName());
            modelOperationService.loadOMOModel(entry.getFilePathString());

            logger.debug(".OMO file selected: {}", entry.getName());
        } catch (Exception e) {
            logger.error("Failed to load .OMO file: " + entry.getName(), e);
            statusService.updateStatus("Error loading .OMO model: " + e.getMessage());
        }
    }

    /**
     * Gets the OMO file manager for direct access.
     *
     * @return The OMO file manager instance
     */
    public OMOFileManager getOMOFileManager() {
        return omoFileManager;
    }

    /**
     * Resets the controller state to defaults.
     */
    public void reset() {
        state.reset();
        logger.debug("Model browser state reset");
    }
}
