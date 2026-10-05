package com.openmason.engine.ui.runtime.layout;

import com.openmason.engine.cenda.FlexRecord;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues.Length;

import java.util.Map;

/**
 * Turns a computed style into a Yoga record under {@code flex-1} semantics (Yoga v3.2.1
 * defaults frozen by #283). Logical-pixel lengths are multiplied by the device scale here, so
 * Yoga lays out and rounds in device pixels; percentages pass through untouched.
 */
public final class FlexStyleMapper {

    private static final Map<String, Integer> DIRECTION = Map.of("column", 0, "column-reverse", 1, "row", 2,
        "row-reverse", 3);
    private static final Map<String, Integer> WRAP = Map.of("nowrap", 0, "wrap", 1, "wrap-reverse", 2);
    private static final Map<String, Integer> JUSTIFY = Map.of("flex-start", 0, "center", 1, "flex-end", 2,
        "space-between", 3, "space-around", 4, "space-evenly", 5);
    private static final Map<String, Integer> ALIGN = Map.of("auto", 0, "flex-start", 1, "center", 2, "flex-end", 3,
        "stretch", 4, "baseline", 5, "space-between", 6, "space-around", 7, "space-evenly", 8);
    private static final String[] EDGES = {"left", "top", "right", "bottom"};

    private FlexStyleMapper() {
    }

    /**
     * Writes {@code style} as one record at {@code rec[offset]}.
     *
     * @param scale     device pixels per logical pixel
     * @param measureId the leaf's measure id, or -1 for unmeasured elements
     */
    public static void write(ComputedStyle style, float scale, int measureId, float[] rec, int offset) {
        write(style, scale, measureId, false, rec, offset);
    }

    /** @param scrollContainer forces {@code overflow: scroll} (a {@code ScrollView}) */
    public static void write(ComputedStyle style, float scale, int measureId, boolean scrollContainer, float[] rec,
                             int offset) {
        FlexRecord.clear(rec, offset);
        int[] masks = new int[2]; // pct, auto
        rec[offset + FlexRecord.DISPLAY] = style.collapsed() ? 1 : 0;
        rec[offset + FlexRecord.POSITION_TYPE] = "absolute".equals(style.keyword("position", "relative")) ? 1 : 0;
        enumField(style, "flex-direction", DIRECTION, rec, offset + FlexRecord.DIRECTION);
        enumField(style, "flex-wrap", WRAP, rec, offset + FlexRecord.WRAP);
        enumField(style, "justify-content", JUSTIFY, rec, offset + FlexRecord.JUSTIFY);
        enumField(style, "align-items", ALIGN, rec, offset + FlexRecord.ALIGN_ITEMS);
        enumField(style, "align-self", ALIGN, rec, offset + FlexRecord.ALIGN_SELF);
        enumField(style, "align-content", ALIGN, rec, offset + FlexRecord.ALIGN_CONTENT);
        rec[offset + FlexRecord.OVERFLOW] = switch (style.keyword("overflow", "visible")) {
            case "hidden" -> 1;
            case "scroll" -> 2;
            default -> 0;
        };
        if (scrollContainer) {
            rec[offset + FlexRecord.OVERFLOW] = 2;
        }
        number(style, "flex-grow", rec, offset + FlexRecord.GROW);
        number(style, "flex-shrink", rec, offset + FlexRecord.SHRINK);
        number(style, "aspect-ratio", rec, offset + FlexRecord.ASPECT);

        length(style.length("flex-basis"), scale, true, FlexRecord.LEN_BASIS, rec, offset + FlexRecord.BASIS, masks);
        length(style.length("width"), scale, true, FlexRecord.LEN_WIDTH, rec, offset + FlexRecord.WIDTH, masks);
        length(style.length("height"), scale, true, FlexRecord.LEN_HEIGHT, rec, offset + FlexRecord.HEIGHT, masks);
        length(style.length("min-width"), scale, false, FlexRecord.LEN_MIN_W, rec, offset + FlexRecord.MIN_W, masks);
        length(style.length("min-height"), scale, false, FlexRecord.LEN_MIN_H, rec, offset + FlexRecord.MIN_H, masks);
        length(style.length("max-width"), scale, false, FlexRecord.LEN_MAX_W, rec, offset + FlexRecord.MAX_W, masks);
        length(style.length("max-height"), scale, false, FlexRecord.LEN_MAX_H, rec, offset + FlexRecord.MAX_H, masks);
        for (int e = 0; e < 4; e++) {
            String edge = EDGES[e];
            length(style.length("margin-" + edge), scale, true, FlexRecord.LEN_MARGIN << e, rec,
                offset + FlexRecord.MARGIN + e, masks);
            length(style.length("padding-" + edge), scale, false, FlexRecord.LEN_PADDING << e, rec,
                offset + FlexRecord.PADDING + e, masks);
            length(style.length(edge), scale, true, FlexRecord.LEN_POS << e, rec, offset + FlexRecord.POS + e, masks);
            Length border = style.length("border-" + edge + "-width");
            if (border.kind() == Length.Kind.POINTS) {
                rec[offset + FlexRecord.BORDER + e] = border.value() * scale; // Yoga borders have no percent
            }
        }
        length(style.length("row-gap"), scale, false, FlexRecord.LEN_GAP_ROW, rec, offset + FlexRecord.GAP_ROW, masks);
        length(style.length("column-gap"), scale, false, FlexRecord.LEN_GAP_COLUMN, rec,
            offset + FlexRecord.GAP_COLUMN, masks);
        rec[offset + FlexRecord.PCT_MASK] = masks[0];
        rec[offset + FlexRecord.AUTO_MASK] = masks[1];
        if (measureId >= 0) {
            rec[offset + FlexRecord.MEASURE_ID] = measureId;
        }
    }

    private static void enumField(ComputedStyle style, String property, Map<String, Integer> values, float[] rec,
                                  int index) {
        Integer v = values.get(style.keyword(property, ""));
        if (v != null) {
            rec[index] = v;
        }
    }

    private static void number(ComputedStyle style, String property, float[] rec, int index) {
        double v = style.number(property, Double.NaN);
        if (!Double.isNaN(v)) {
            rec[index] = (float) v;
        }
    }

    private static void length(Length l, float scale, boolean autoAllowed, int bit, float[] rec, int index,
                               int[] masks) {
        switch (l.kind()) {
            case POINTS -> rec[index] = l.value() * scale;
            case PERCENT -> {
                rec[index] = l.value();
                masks[0] |= bit;
            }
            case AUTO -> {
                if (autoAllowed) {
                    masks[1] |= bit;
                }
            }
            case UNSET -> {
            }
        }
    }
}
