package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.ModelPart;
import com.openmason.main.systems.rendering.model.UVMode;

import java.util.List;

/**
 * Interface for managing model state including parts, dimensions, and UV mode.
 * Encapsulates model configuration separate from rendering concerns.
 */
public interface IModelStateManager {

    /**
     * Set the model dimensions and origin.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     * @param depth Depth in pixels
     * @param originX Origin X in pixels
     * @param originY Origin Y in pixels
     * @param originZ Origin Z in pixels
     */
    void setDimensions(int width, int height, int depth, double originX, double originY, double originZ);

    /**
     * Get the current model dimensions.
     *
     * @return Current dimensions, or null if not set
     */
    ModelDimensions getDimensions();

    /**
     * Check if dimensions have been set.
     *
     * @return true if dimensions are set
     */
    boolean hasDimensions();

    /**
     * Set the UV mapping mode.
     *
     * @param mode UV mode (FLAT or CUBE_NET)
     */
    void setUVMode(UVMode mode);

    /**
     * Get the current UV mode.
     *
     * @return Current UV mode
     */
    UVMode getUVMode();

    /**
     * Get the list of model parts.
     *
     * @return Unmodifiable list of parts
     */
    List<ModelPart> getParts();

    /**
     * Clear all model parts.
     */
    void clearParts();

    /**
     * Add a model part.
     *
     * @param part The part to add
     */
    void addPart(ModelPart part);

    /**
     * Check if the model has any parts.
     *
     * @return true if parts exist
     */
    boolean hasParts();

    /**
     * Get the total vertex count across all parts.
     *
     * @return Total vertices
     */
    int getTotalVertexCount();

    /**
     * Get the total index count across all parts.
     *
     * @return Total indices
     */
    int getTotalIndexCount();

    /**
     * Aggregate all parts into combined vertex/texCoord/index arrays.
     *
     * @return Aggregated geometry data
     */
    AggregatedGeometry aggregateParts();

    /**
     * Immutable record holding model dimensions and origin.
     */
    record ModelDimensions(
            int width, int height, int depth,
            double originX, double originY, double originZ
    ) {
        /**
         * Pixels per unit conversion factor (16 pixels = 1 unit).
         */
        public static final float PIXELS_PER_UNIT = 16.0f;

        /**
         * Get the size in world units.
         *
         * @return Size as (width, height, depth) in units
         */
        public org.joml.Vector3f getSizeInUnits() {
            return new org.joml.Vector3f(
                    width / PIXELS_PER_UNIT,
                    height / PIXELS_PER_UNIT,
                    depth / PIXELS_PER_UNIT
            );
        }

        /**
         * Get the origin in world units.
         *
         * @return Origin as (x, y, z) in units
         */
        public org.joml.Vector3f getOriginInUnits() {
            return new org.joml.Vector3f(
                    (float) originX / PIXELS_PER_UNIT,
                    (float) originY / PIXELS_PER_UNIT,
                    (float) originZ / PIXELS_PER_UNIT
            );
        }
    }

    /**
     * Immutable record holding aggregated geometry from all parts.
     *
     * @param vertices Aggregated vertex positions
     * @param texCoords Aggregated texture coordinates
     * @param indices Aggregated indices
     * @param vertexCount Total vertex count
     * @param indexCount Total index count
     * @param trianglesPerFace Topology hint from parts (null if mixed/unknown, 2 for quads, 1 for triangles)
     */
    record AggregatedGeometry(
            float[] vertices,
            float[] texCoords,
            int[] indices,
            int vertexCount,
            int indexCount,
            Integer trianglesPerFace
    ) {
        /**
         * Check if geometry is empty.
         */
        public boolean isEmpty() {
            return vertexCount == 0;
        }
    }
}
