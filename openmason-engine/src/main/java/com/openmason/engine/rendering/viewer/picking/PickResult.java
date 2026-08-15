package com.openmason.engine.rendering.viewer.picking;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import org.joml.Vector3f;

/**
 * A successful pick.
 *
 * @param instance   the instance under the cursor
 * @param distance   distance along the ray to the hit
 * @param worldPoint the hit position in world space
 */
public record PickResult(ModelInstance instance, float distance, Vector3f worldPoint) {
}
