package com.openmason.engine.rendering.model.gmr.parts;

import com.openmason.engine.rendering.model.ModelPart;
import com.openmason.engine.rendering.model.gmr.parts.shapes.ConeShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.CrossShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.CubeShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.CylinderShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.HemisphereShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.PaneShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.PartShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.PyramidShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.SphereShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.SpriteShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.TorusShape;
import com.openmason.engine.rendering.model.gmr.parts.shapes.WedgeShape;
import org.joml.Vector3f;

/**
 * Facade for creating standard part shapes. Each shape's geometry, UV layout,
 * and triangle-to-face mapping is encapsulated in its own {@link PartShape}
 * implementation under the {@code shapes} subpackage. This class merely routes
 * requests to the correct implementation via the {@link Shape} enum.
 *
 * <p>To add a new shape:
 * <ol>
 *   <li>Implement {@link PartShape} in a new class under {@code shapes/}</li>
 *   <li>Add a corresponding entry to the {@link Shape} enum below</li>
 * </ol>
 * No other code changes are required — the MCP tools, ImGui dialog, and
 * renderer all auto-discover new enum values.
 */
public final class PartShapeFactory {

    private PartShapeFactory() {
        throw new UnsupportedOperationException("Factory class");
    }

    /**
     * Available primitive shapes for new model parts. Each constant binds to a
     * {@link PartShape} implementation that owns the geometry definition.
     */
    public enum Shape {
        CUBE(new CubeShape()),
        PYRAMID(new PyramidShape()),
        PANE(new PaneShape()),
        SPRITE(new SpriteShape()),
        CYLINDER(new CylinderShape()),
        CONE(new ConeShape()),
        SPHERE(new SphereShape()),
        HEMISPHERE(new HemisphereShape()),
        WEDGE(new WedgeShape()),
        TORUS(new TorusShape()),
        CROSS(new CrossShape());

        private final PartShape impl;

        Shape(PartShape impl) {
            this.impl = impl;
        }

        public PartShape impl() { return impl; }
        public String getDisplayName() { return impl.displayName(); }
        public String getDescription() { return impl.description(); }
    }

    /**
     * Create a model part for the given shape.
     *
     * @param shape The primitive shape to create
     * @param name  Part name
     * @param size  Size in each axis (width, height, depth)
     * @return A ModelPart with the shape's geometry
     */
    public static ModelPart create(Shape shape, String name, Vector3f size) {
        return shape.impl().create(name, size);
    }

    /**
     * Create a unit-sized (1x1x1) model part for the given shape.
     */
    public static ModelPart createUnit(Shape shape, String name) {
        return create(shape, name, new Vector3f(1, 1, 1));
    }

    /**
     * Create a PartGeometry for the given shape with a proper triangle-to-face
     * mapping. Use this instead of {@link #create} when adding parts to the
     * ModelPartManager to preserve the exact face mapping.
     *
     * @param shape The primitive shape
     * @param name  Part name (unused in geometry, kept for API symmetry)
     * @param size  Size in each axis
     * @return PartGeometry with correct face mapping
     */
    public static PartMeshRebuilder.PartGeometry createGeometry(Shape shape, String name, Vector3f size) {
        PartShape impl = shape.impl();
        ModelPart part = impl.create(name, size);
        int[] triangleToFaceId = impl.triangleToFaceId();
        return PartMeshRebuilder.PartGeometry.of(
                part.vertices(), part.texCoords(), part.indices(), triangleToFaceId
        );
    }
}
