package com.openmason.engine.rendering.model.gmr.parts;

import java.util.Set;

/**
 * Callback interface for model part lifecycle events.
 * Follows the Observer pattern to decouple part management from consumers
 * (viewport selection, UI panels, undo/redo, etc.).
 *
 * <p>Implementations should be lightweight — heavy work should be deferred
 * or batched. The manager may fire multiple events during a single operation
 * (e.g., merge fires multiple {@code onPartRemoved} then one {@code onPartAdded}).
 */
public interface IPartChangeListener {

    /**
     * A new part was added to the model.
     *
     * @param part The newly added part descriptor
     */
    void onPartAdded(ModelPartDescriptor part);

    /**
     * A part was removed from the model.
     *
     * @param partId The ID of the removed part
     */
    void onPartRemoved(String partId);

    /**
     * A part's local transform was updated.
     *
     * @param partId       The ID of the affected part
     * @param newTransform The new transform
     */
    void onPartTransformChanged(String partId, PartTransform newTransform);

    /**
     * The set of selected parts changed.
     *
     * @param selectedIds Current set of selected part IDs
     */
    void onPartSelectionChanged(Set<String> selectedIds);

    /**
     * Multiple parts were merged into a single part.
     *
     * @param sourceIds  IDs of the parts that were consumed by the merge
     * @param mergedPart The resulting merged part descriptor
     */
    void onPartsMerged(java.util.List<String> sourceIds, ModelPartDescriptor mergedPart);

    /**
     * The entire part collection was rebuilt (e.g., after undo/redo or file load).
     * Listeners should discard cached part state and re-query the manager.
     */
    void onPartsRebuilt();
}
