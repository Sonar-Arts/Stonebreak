package com.openmason.engine.ui.layoutspike;

/**
 * Intrinsic size of a measured leaf (text, icons). Modes follow Yoga:
 * 0 undefined, 1 exactly, 2 at-most. Writes width/height into {@code out}.
 */
@FunctionalInterface
interface FlexMeasure {

    int UNDEFINED = 0;
    int EXACTLY = 1;
    int AT_MOST = 2;

    void measure(int id, float width, int widthMode, float height, int heightMode, float[] out);
}
