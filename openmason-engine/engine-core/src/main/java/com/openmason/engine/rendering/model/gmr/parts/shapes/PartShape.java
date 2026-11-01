package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * A primitive shape that can produce a {@link ModelPart}.
 *
 * <p>Implementations encapsulate the geometry, UV layout, and triangle-to-face
 * mapping for a single shape type. New shapes can be added by implementing this
 * interface and registering the implementation in
 * {@link com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory.Shape}.
 */
public interface PartShape {

    /** Human-readable display name shown in editor UIs. */
    String displayName();

    /** Short description shown in editor UIs. */
    String description();

    /**
     * SVG icon filename (e.g. {@code "cube.svg"}) for this shape. The icon
     * resource is expected to live under {@code /icons/shapes/} on the
     * classpath. Editor UIs use this to render a small preview next to the
     * shape's label.
     */
    String iconFilename();

    /**
     * Build the geometry for this shape, centered at the origin.
     *
     * @param name The part name
     * @param size The full extents along each axis (X width, Y height, Z depth)
     * @return A ModelPart with vertices, UVs, and indices
     */
    ModelPart create(String name, Vector3f size);

    /**
     * Mapping from triangle index → logical face id, used by PartGeometry to group
     * triangles by face for selection, painting, and topology operations.
     *
     * <p>The returned array length must equal the number of triangles produced by
     * {@link #create}. The mapping is structural and does not depend on size.
     */
    int[] triangleToFaceId();
}
