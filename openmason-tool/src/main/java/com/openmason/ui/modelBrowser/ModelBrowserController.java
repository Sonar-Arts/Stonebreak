package com.openmason.ui.modelBrowser;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.model.io.omo.OMOFileManager;
import com.openmason.ui.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.modelBrowser.events.BlockSelectedEvent;
import com.openmason.ui.modelBrowser.events.ItemSelectedEvent;
import com.openmason.ui.modelBrowser.events.ModelBrowserListener;
import com.openmason.ui.modelBrowser.events.listeners.BlockSelectionListener;
import com.openmason.ui.modelBrowser.events.listeners.ItemSelectionListener;
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
     */
    public ModelBrowserState getState() {
        return state;
    }

    /**
     * Adds a listener to be notified of model browser events.
     */
    public void addListener(ModelBrowserListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Template method for handling selection events.
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
            logger.error("Failed to select {}: {}", itemType.toLowerCase(), item, e);
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
     * Handles model selection by the user (legacy cow functionality removed).
     *
     * @param modelName The selected model name
     * @deprecated Legacy cow model support has been removed
     */
    @Deprecated
    public void selectModel(String modelName) {
        handleSelection(
                modelName,
                "Model",
                modelName,
                "Model selection (legacy)",
                name -> {
                    // Legacy model selection - no longer supported
                    logger.debug("selectModel called but legacy model loading is no longer supported: {}", name);
                },
                listenerList -> {
                    // No listener notification - ModelSelectionListener removed
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
     * Gets all available .OMO model files.
     */
    public List<OMOFileManager.OMOFileEntry> getOMOFiles() {
        return omoFileManager.scanForOMOFiles();
    }

    /**
     * Handles .OMO file selection by the user.
     */
    public void selectOMOFile(OMOFileManager.OMOFileEntry entry) {
        if (entry == null) {
            logger.warn("Attempted to select null OMO file");
            return;
        }

        try {
            // Update state
            state.setSelectedModelInfo("Selected: " + entry.name() + " (.OMO Model)");
            state.addRecentFile(entry.name());

            // Load the .OMO file using ModelOperationService
            statusService.updateStatus("Loading .OMO model: " + entry.name());
            modelOperationService.loadOMOModel(entry.getFilePathString());

            logger.debug(".OMO file selected: {}", entry.name());
        } catch (Exception e) {
            logger.error("Failed to load .OMO file: {}", entry.name(), e);
            statusService.updateStatus("Error loading .OMO model: " + e.getMessage());
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
