package com.openmason.main.systems.viewport.util;

/**
  * Utility class for snapping values and positions to grid increments.
  * All grid increments are based on a standard block size of 1.0 world unit.
  * Recommended increments: 1.0 (full), 0.5 (half), 0.25 (quarter), 0.125 (eighth).
  */
public class SnappingUtil {

public static final float STANDARD_BLOCK_SIZE = 1.0f;

public static final float SNAP_FULL_BLOCK = STANDARD_BLOCK_SIZE;

public static final float SNAP_HALF_BLOCK = STANDARD_BLOCK_SIZE / 2.0f;

public static final float SNAP_QUARTER_BLOCK = STANDARD_BLOCK_SIZE / 4.0f;

private SnappingUtil() {
    throw new AssertionError("Utility class should not be instantiated");
}

public static float snapToGrid(float value, float increment) {
    if (increment <= 0) {
        return value;
    }
    return Math.round(value / increment) * increment;
}

}
