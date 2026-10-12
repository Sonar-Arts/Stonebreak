package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.PortType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValuesTest {

    @Test
    void literalsParseByPortType() {
        assertEquals(UiValue.TRUE, GraphValues.parse(PortType.BOOL, " True "));
        assertNull(GraphValues.parse(PortType.BOOL, "yes"));
        assertEquals(UiValue.of(12), GraphValues.parse(PortType.INT, "12"));
        assertNull(GraphValues.parse(PortType.INT, "1.5"));
        assertEquals(UiValue.of(1.5), GraphValues.parse(PortType.NUMBER, "1.5"));
        assertNull(GraphValues.parse(PortType.NUMBER, "abc"));
        assertEquals(UiValue.of(""), GraphValues.parse(PortType.STRING, ""));
        assertEquals(UiValue.of("#FF8800"), GraphValues.parse(PortType.COLOR, "#FF8800"));
        assertNull(GraphValues.parse(PortType.COLOR, "red"));
    }

    @Test
    void structuredLiteralsGoThroughJson() {
        assertEquals(new UiValue.Arr(List.of(UiValue.of(1), UiValue.of("a"))), GraphValues.parse(PortType.LIST, "[1,\"a\"]"));
        assertNull(GraphValues.parse(PortType.LIST, "{\"a\":1}"));
        assertTrue(GraphValues.parse(PortType.OBJECT, "{\"a\":1}") instanceof UiValue.Obj);
        assertNull(GraphValues.parse(PortType.OBJECT, "{oops"));
    }

    @Test
    void anyDetectsTheShape() {
        assertEquals(UiValue.of(3), GraphValues.parse(PortType.ANY, "3"));
        assertEquals(UiValue.TRUE, GraphValues.parse(PortType.ANY, "true"));
        assertEquals(UiValue.of("hello"), GraphValues.parse(PortType.ANY, "hello"));
    }

    @Test
    void editTextRoundTripsThroughParse() {
        for (PortType t : new PortType[]{PortType.INT, PortType.NUMBER, PortType.STRING, PortType.BOOL}) {
            UiValue v = switch (t) {
                case INT -> UiValue.of(7);
                case NUMBER -> UiValue.of(2.25);
                case BOOL -> UiValue.FALSE;
                default -> UiValue.of("a b");
            };
            assertEquals(v, GraphValues.parse(t, GraphValues.editText(v)), t.wire());
        }
        UiValue list = new UiValue.Arr(List.of(UiValue.of(1), UiValue.of(2)));
        assertEquals(list, GraphValues.parse(PortType.LIST, GraphValues.editText(list)));
    }

    @Test
    void compactIsShortAndSingleLine() {
        assertEquals("12", GraphValues.compact(UiValue.of(12)));
        assertEquals("\"hi\"", GraphValues.compact(UiValue.of("hi")));
        assertEquals("[2]", GraphValues.compact(new UiValue.Arr(List.of(UiValue.of(1), UiValue.of(2)))));
        String longText = GraphValues.compact(UiValue.of("a very long line of text\nsecond"));
        assertTrue(longText.length() <= 16 && !longText.contains("\n"), longText);
    }
}
