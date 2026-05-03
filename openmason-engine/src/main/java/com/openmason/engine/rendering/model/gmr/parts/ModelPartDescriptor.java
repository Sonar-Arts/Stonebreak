package com.openmason.engine.rendering.model.gmr.parts;

/**
 * Immutable descriptor for a model part, tracking its identity, transform,
 * and mesh range within the combined buffer.
 *
 * <p>ModelPartDescriptor is the primary data object for the ModelPartManager.
 * Each part has a unique ID, a user-facing display name, a local transform, and
 * a mesh range that maps into the combined GPU buffer. Parts may optionally
 * declare a {@code parentId} — when set, this part's effective world transform
 * is the parent's effective world transform composed with this part's local
 * transform (skeletal hierarchy). A null {@code parentId} marks the part as a
 * root.
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
 * @param parentId  ID of the parent part for hierarchical transforms; null = root
 */
public record ModelPartDescriptor(
        String id,
        String name,
        PartTransform transform,
        MeshRange meshRange,
        boolean visible,
        boolean locked,
        String parentId
) {

    /**
     * Backward-compatible constructor for callers that don't specify a parent.
     * The new descriptor is treated as a root part (parentId = null).
     */
    public ModelPartDescriptor(String id, String name, PartTransform transform,
                               MeshRange meshRange, boolean visible, boolean locked) {
        this(id, name, transform, meshRange, visible, locked, null);
    }

    /**
     * Create a new descriptor with an updated name.
     *
     * @param newName New display name
     * @return Updated descriptor
     */
    public ModelPartDescriptor withName(String newName) {
        return new ModelPartDescriptor(id, newName, transform, meshRange, visible, locked, parentId);
    }

    /**
     * Create a new descriptor with an updated transform.
     *
     * @param newTransform New local transform
     * @return Updated descriptor
     */
    public ModelPartDescriptor withTransform(PartTransform newTransform) {
        return new ModelPartDescriptor(id, name, newTransform, meshRange, visible, locked, parentId);
    }

    /**
     * Create a new descriptor with an updated mesh range.
     *
     * @param newRange New mesh range after buffer rebuild
     * @return Updated descriptor
     */
    public ModelPartDescriptor withMeshRange(MeshRange newRange) {
        return new ModelPartDescriptor(id, name, transform, newRange, visible, locked, parentId);
    }

    /**
     * Create a new descriptor with updated visibility.
     *
     * @param newVisible New visibility state
     * @return Updated descriptor
     */
    public ModelPartDescriptor withVisible(boolean newVisible) {
        return new ModelPartDescriptor(id, name, transform, meshRange, newVisible, locked, parentId);
    }

    /**
     * Create a new descriptor with updated lock state.
     *
     * @param newLocked New lock state
     * @return Updated descriptor
     */
    public ModelPartDescriptor withLocked(boolean newLocked) {
        return new ModelPartDescriptor(id, name, transform, meshRange, visible, newLocked, parentId);
    }

    /**
     * Create a new descriptor with an updated parent ID.
     * Pass {@code null} to detach this part to root.
     *
     * @param newParentId New parent ID, or null for root
     * @return Updated descriptor
     */
    public ModelPartDescriptor withParent(String newParentId) {
        return new ModelPartDescriptor(id, name, transform, meshRange, visible, locked, newParentId);
    }

    /**
     * @return true if this part has no parent (root part)
     */
    public boolean isRoot() {
        return parentId == null;
    }
}
