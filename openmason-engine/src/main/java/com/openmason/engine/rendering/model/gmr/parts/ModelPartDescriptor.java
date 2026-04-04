package com.openmason.engine.rendering.model.gmr.parts;

/**
 * Immutable descriptor for a model part, tracking its identity, transform,
 * and mesh range within the combined buffer.
 *
 * <p>ModelPartDescriptor is the primary data object for the ModelPartManager.
 * Each part has a unique ID, a user-facing name, a local transform, and
 * a mesh range that maps into the combined GPU buffer.
 *
 * <p>Descriptors are immutable. To update a part, create a new descriptor
 * via the {@code with*} methods and register it with the manager.
 *
 * @param id        UUID-based unique identifier (stable across renames)
 * @param name      User-facing display name
 * @param transform Local transform (position, rotation, scale relative to origin)
 * @param meshRange Vertex/index/face range in the combined buffer (null before first rebuild)
 * @param visible   Whether this part is rendered
 * @param locked    Whether this part is protected from editing
 */
public record ModelPartDescriptor(
        String id,
        String name,
        PartTransform transform,
        MeshRange meshRange,
        boolean visible,
        boolean locked
) {

    /**
     * Create a new descriptor with an updated name.
     *
     * @param newName New display name
     * @return Updated descriptor
     */
    public ModelPartDescriptor withName(String newName) {
        return new ModelPartDescriptor(id, newName, transform, meshRange, visible, locked);
    }

    /**
     * Create a new descriptor with an updated transform.
     *
     * @param newTransform New local transform
     * @return Updated descriptor
     */
    public ModelPartDescriptor withTransform(PartTransform newTransform) {
        return new ModelPartDescriptor(id, name, newTransform, meshRange, visible, locked);
    }

    /**
     * Create a new descriptor with an updated mesh range.
     *
     * @param newRange New mesh range after buffer rebuild
     * @return Updated descriptor
     */
    public ModelPartDescriptor withMeshRange(MeshRange newRange) {
        return new ModelPartDescriptor(id, name, transform, newRange, visible, locked);
    }

    /**
     * Create a new descriptor with updated visibility.
     *
     * @param newVisible New visibility state
     * @return Updated descriptor
     */
    public ModelPartDescriptor withVisible(boolean newVisible) {
        return new ModelPartDescriptor(id, name, transform, meshRange, newVisible, locked);
    }

    /**
     * Create a new descriptor with updated lock state.
     *
     * @param newLocked New lock state
     * @return Updated descriptor
     */
    public ModelPartDescriptor withLocked(boolean newLocked) {
        return new ModelPartDescriptor(id, name, transform, meshRange, visible, newLocked);
    }
}
