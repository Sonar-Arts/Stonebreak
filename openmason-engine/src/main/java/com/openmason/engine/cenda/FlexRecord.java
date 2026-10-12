package com.openmason.engine.cenda;

/**
 * Field layout of one Yoga style record, mirroring {@code cenda/flex.h} (ABI 2). A record is
 * {@link #STRIDE} floats; NaN means unset, which resolves to the {@code flex-1} defaults
 * (column, shrink 0, align-items stretch, align-content flex-start, border-box).
 *
 * <p>Lengths are points unless their {@code LEN_*} bit is set in {@link #PCT_MASK} (percent) or
 * {@link #AUTO_MASK} ({@code auto}). Enum fields carry Yoga's own enum values.
 */
public final class FlexRecord {

    public static final int PARENT = 0;
    public static final int DISPLAY = 1;
    public static final int POSITION_TYPE = 2;
    public static final int DIRECTION = 3;
    public static final int WRAP = 4;
    public static final int JUSTIFY = 5;
    public static final int ALIGN_ITEMS = 6;
    public static final int ALIGN_SELF = 7;
    public static final int ALIGN_CONTENT = 8;
    public static final int GROW = 9;
    public static final int SHRINK = 10;
    public static final int BASIS = 11;
    public static final int WIDTH = 12;
    public static final int HEIGHT = 13;
    public static final int MIN_W = 16;
    public static final int MIN_H = 17;
    public static final int MAX_W = 18;
    public static final int MAX_H = 19;
    /** Four floats: left, top, right, bottom. */
    public static final int MARGIN = 20;
    public static final int PADDING = 24;
    public static final int BORDER = 28;
    /** Insets, four floats. */
    public static final int POS = 32;
    public static final int GAP_ROW = 36;
    public static final int GAP_COLUMN = 37;
    public static final int ASPECT = 38;
    public static final int MEASURE_ID = 39;
    public static final int PCT_MASK = 44;
    public static final int AUTO_MASK = 45;
    public static final int OVERFLOW = 46;
    public static final int STRIDE = 48;

    public static final int LEFT = 0;
    public static final int TOP = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 3;

    public static final int LEN_BASIS = 1;
    public static final int LEN_WIDTH = 1 << 1;
    public static final int LEN_HEIGHT = 1 << 2;
    public static final int LEN_MIN_W = 1 << 3;
    public static final int LEN_MIN_H = 1 << 4;
    public static final int LEN_MAX_W = 1 << 5;
    public static final int LEN_MAX_H = 1 << 6;
    /** Shift left by the edge index (0..3). */
    public static final int LEN_MARGIN = 1 << 7;
    public static final int LEN_PADDING = 1 << 11;
    public static final int LEN_POS = 1 << 15;
    public static final int LEN_GAP_ROW = 1 << 19;
    public static final int LEN_GAP_COLUMN = 1 << 20;

    /** Status codes of the retained API. */
    public static final int OK = 0;
    public static final int ERR_ARG = 1;
    public static final int ERR_NODE = 2;
    public static final int ERR_TREE = 3;

    private FlexRecord() {
    }

    /** Fills {@code record[offset .. offset + STRIDE)} with an all-default record. */
    public static void clear(float[] record, int offset) {
        java.util.Arrays.fill(record, offset, offset + STRIDE, Float.NaN);
        record[offset + DISPLAY] = 0;
        record[offset + POSITION_TYPE] = 0;
    }
}
