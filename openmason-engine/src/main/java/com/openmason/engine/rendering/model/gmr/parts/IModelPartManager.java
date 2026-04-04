package com.openmason.engine.rendering.model.gmr.parts;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Contract for managing model parts with selection, transforms, and CRUD.
 *
 * <p>Follows Interface Segregation — consumers only see what they need.
 * The manager owns the lifecycle of {@link ModelPartDescriptor} instances and
 * coordinates mesh buffer rebuilds when parts change.
 *
 * <p>All mutating operations trigger a combined mesh rebuild and notify
 * registered {@link IPartChangeListener} instances.
 */
public interface IModelPartManager {

    // ========== Query ==========

    /**
     * Get all parts in insertion order.
     *
     * @return Unmodifiable list of part descriptors
     */
    List<ModelPartDescriptor> getAllParts();

    /**
     * Find a part by its unique ID.
     *
     * @param id Part ID
     * @return The descriptor, or empty if not found
     */
    Optional<ModelPartDescriptor> getPartById(String id);

    /**
     * Find a part by its display name.
     *
     * @param name Part name
     * @return The first matching descriptor, or empty if not found
     */
    Optional<ModelPartDescriptor> getPartByName(String name);

    /**
     * Find which part owns a given global vertex index.
     *
     * @param globalVertexIndex Vertex index in the combined buffer
     * @return The owning part, or empty if out of range
     */
    Optional<ModelPartDescriptor> getPartForVertex(int globalVertexIndex);

    /**
     * Find which part owns a given face ID.
     *
     * @param faceId Face ID in the combined buffer
     * @return The owning part, or empty if out of range
     */
    Optional<ModelPartDescriptor> getPartForFace(int faceId);

    /**
     * Get the number of parts.
     *
     * @return Part count
     */
    int getPartCount();

    // ========== CRUD ==========

    /**
     * Add a new part from raw geometry data.
     *
     * @param name     Display name for the part
     * @param geometry Raw geometry (vertices, texCoords, indices, topology)
     * @return The newly created part descriptor
     */
    ModelPartDescriptor addPart(String name, ModelPart geometry);

    /**
     * Duplicate an existing part (geometry + transform).
     *
     * @param sourceId ID of the part to duplicate
     * @return The new duplicate part, or empty if source not found
     */
    Optional<ModelPartDescriptor> duplicatePart(String sourceId);

    /**
     * Remove a part by ID.
     *
     * @param id Part ID to remove
     * @return true if the part was found and removed
     */
    boolean removePart(String id);

    /**
     * Merge multiple parts into a single new part.
     * Source parts are removed; their combined geometry becomes the merged part.
     *
     * @param partIds    IDs of parts to merge
     * @param mergedName Display name for the merged part
     * @return The merged part, or empty if any source ID was invalid
     */
    Optional<ModelPartDescriptor> mergeParts(List<String> partIds, String mergedName);

    /**
     * Rename a part.
     *
     * @param id      Part ID
     * @param newName New display name
     * @return The updated descriptor, or empty if not found
     */
    Optional<ModelPartDescriptor> renamePart(String id, String newName);

    // ========== Selection ==========

    /**
     * Get the set of currently selected part IDs.
     *
     * @return Unmodifiable set of selected IDs
     */
    Set<String> getSelectedPartIds();

    /**
     * Select a part (add to selection).
     *
     * @param id Part ID to select
     */
    void selectPart(String id);

    /**
     * Deselect a part (remove from selection).
     *
     * @param id Part ID to deselect
     */
    void deselectPart(String id);

    /**
     * Toggle selection state of a part.
     *
     * @param id Part ID to toggle
     */
    void togglePartSelection(String id);

    /**
     * Select all parts.
     */
    void selectAllParts();

    /**
     * Clear all selections.
     */
    void deselectAllParts();

    /**
     * Check if a specific part is selected.
     *
     * @param id Part ID
     * @return true if selected
     */
    boolean isPartSelected(String id);

    // ========== Transforms ==========

    /**
     * Set the full local transform for a part.
     *
     * @param id        Part ID
     * @param transform New transform
     */
    void setPartTransform(String id, PartTransform transform);

    /**
     * Translate a part by a delta offset.
     *
     * @param id    Part ID
     * @param delta Translation delta
     */
    void translatePart(String id, Vector3f delta);

    /**
     * Rotate a part by Euler delta (degrees).
     *
     * @param id         Part ID
     * @param eulerDelta Rotation delta in degrees
     */
    void rotatePart(String id, Vector3f eulerDelta);

    /**
     * Scale a part by multiplier factors per axis.
     *
     * @param id          Part ID
     * @param scaleFactors Scale multipliers
     */
    void scalePart(String id, Vector3f scaleFactors);

    // ========== Visibility / Locking ==========

    /**
     * Set part visibility.
     *
     * @param id      Part ID
     * @param visible true to show, false to hide
     */
    void setPartVisible(String id, boolean visible);

    /**
     * Set part lock state. Locked parts cannot be edited.
     *
     * @param id     Part ID
     * @param locked true to lock
     */
    void setPartLocked(String id, boolean locked);

    // ========== Listeners ==========

    /**
     * Register a listener for part change events.
     *
     * @param listener Listener to add
     */
    void addPartChangeListener(IPartChangeListener listener);

    /**
     * Unregister a listener.
     *
     * @param listener Listener to remove
     */
    void removePartChangeListener(IPartChangeListener listener);

    // ========== Bulk Operations ==========

    /**
     * Clear all parts and reset to empty state.
     * Fires {@link IPartChangeListener#onPartsRebuilt()}.
     */
    void clear();

    /**
     * Import a single part as the entire model (legacy compatibility).
     * Wraps the given geometry as a single "Root" part.
     *
     * @param geometry Raw geometry data
     * @return The root part descriptor
     */
    ModelPartDescriptor importAsSinglePart(ModelPart geometry);
}
