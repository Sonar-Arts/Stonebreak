package com.stonebreak.world.generation.sdf.primitives;

import com.stonebreak.world.generation.sdf.SdfPrimitive;

import java.util.function.BiFunction;

/**
 * Signed Distance Field for a heightfield surface.
 *
 * <p>Converts a traditional 2D heightmap into an SDF representation. This allows
 * the existing spline-based terrain generation to integrate seamlessly with the
 * SDF system for caves, overhangs, and other 3D features.</p>
 *
 * <p><b>Performance:</b> ~5 operations per evaluation (1 heightmap lookup, 1 subtraction).
 * The heightmap lookup is typically cached by the spline system's bilinear
 * interpolation, making this extremely fast.</p>
 *
 * <p><b>Integration Strategy:</b></p>
 * <pre>
 * // Use existing spline offset as heightfield
 * SdfHeightfield terrain = new SdfHeightfield(
 *     (x, z) -&gt; offsetSplineRouter.getOffset(params, x, z)
 * );
 *
 * // Combine with caves using CSG
 * float terrainDensity = terrain.evaluate(x, y, z);
 * float caveDensity = caveSystem.evaluate(x, y, z);
 * float finalDensity = SdfBlendOperations.subtract(terrainDensity, caveDensity);
 * </pre>
 *
 * <p><b>Thread Safety:</b> Thread-safe if the heightFunction is thread-safe.</p>
 */
public final class SdfHeightfield implements SdfPrimitive {

    private final BiFunction<Float, Float, Float> heightFunction;
    private final float minY;
    private final float maxY;
    private final float[] bounds;

    /**
     * Creates a heightfield SDF using the provided height function.
     *
     * @param heightFunction Function (x, z) -&gt; height that returns terrain height at (x, z)
     * @param minY Minimum possible height (typically 0 or sea level)
     * @param maxY Maximum possible height (typically 256 for world height limit)
     * @throws IllegalArgumentException if minY &gt;= maxY
     */
    public SdfHeightfield(BiFunction<Float, Float, Float> heightFunction,
                          float minY, float maxY) {
        if (minY >= maxY) {
            throw new IllegalArgumentException(
                String.format("minY must be less than maxY, got: minY=%.1f, maxY=%.1f", minY, maxY)
            );
        }

        this.heightFunction = heightFunction;
        this.minY = minY;
        this.maxY = maxY;

        // Bounds are infinite in XZ, but limited in Y
        this.bounds = new float[] {
            Float.NEGATIVE_INFINITY, minY, Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY, maxY, Float.POSITIVE_INFINITY
        };
    }

    /**
     * Convenience constructor with default Minecraft world height bounds (0-256).
     *
     * @param heightFunction Function (x, z) -&gt; height that returns terrain height at (x, z)
     */
    public SdfHeightfield(BiFunction<Float, Float, Float> heightFunction) {
        this(heightFunction, 0.0f, 256.0f);
    }

    @Override
    public float evaluate(float x, float y, float z) {
        // Get terrain height at this XZ position
        float surfaceHeight = heightFunction.apply(x, z);

        // Signed distance: y - surfaceHeight
        // - Negative when y < surfaceHeight (below surface = inside terrain = solid)
        // - Positive when y > surfaceHeight (above surface = outside terrain = air)
        return y - surfaceHeight;
    }

    @Override
    public float[] getBounds() {
        return bounds;
    }

    @Override
    public boolean isOutsideBounds(float x, float y, float z) {
        // Only check Y bounds (XZ is infinite)
        return y < minY || y > maxY;
    }

    /**
     * Sample the heightfield at a specific XZ position.
     *
     * <p>This is a convenience method that directly calls the height function
     * without computing signed distance.</p>
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Surface height at (x, z)
     */
    public float sampleHeight(float x, float z) {
        return heightFunction.apply(x, z);
    }

    @Override
    public String toString() {
        return String.format("SdfHeightfield[minY=%.1f, maxY=%.1f]", minY, maxY);
    }
}
