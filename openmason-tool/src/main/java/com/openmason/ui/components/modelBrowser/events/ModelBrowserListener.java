package com.openmason.ui.components.modelBrowser.events;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;

/**
 * Listener interface for Model Browser events.
 * Implements the Observer pattern for loose coupling between the Model Browser and viewport.
 *
 * <p>This interface allows components to be notified when the user selects different
 * assets in the Model Browser without the Model Browser needing direct knowledge of
 * those components.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Interface Segregation: Focused interface with only necessary methods</li>
 *   <li>Dependency Inversion: Depend on abstraction, not concrete implementation</li>
 * </ul>
 */
public interface ModelBrowserListener {

    /**
     * Called when a block is selected in the Model Browser.
     *
     * @param event The block selection event containing the selected BlockType
     */
    void onBlockSelected(BlockSelectedEvent event);

    /**
     * Called when an item is selected in the Model Browser.
     *
     * @param event The item selection event containing the selected ItemType
     */
    void onItemSelected(ItemSelectedEvent event);

    /**
     * Called when an entity model is selected in the Model Browser.
     *
     * @param event The model selection event containing the model name
     */
    void onModelSelected(ModelSelectedEvent event);
}
