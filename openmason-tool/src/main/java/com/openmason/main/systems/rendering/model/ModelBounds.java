package com.openmason.main.systems.rendering.model;

import org.joml.Vector3f;

/**
 * Immutable axis-aligned bounding box (AABB) for a model.
 *
 * @param min    Minimum corner of the bounding box
 * @param max    Maximum corner of the bounding box
 * @param center Center point of the bounding box
 * @param size   Dimensions of the bounding box (max - min)
 */
public record ModelBounds(Vector3f min, Vector3f max, Vector3f center, Vector3f size) {

    /** Empty bounds for models with no vertex data. */
    public static final ModelBounds EMPTY = new ModelBounds(
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f());

    /**
     * @return The largest dimension (width, height, or depth)
     */
    public float maxExtent() {
        return Math.max(size.x, Math.max(size.y, size.z));
    }
}
