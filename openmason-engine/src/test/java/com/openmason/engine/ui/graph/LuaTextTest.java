package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Literals the graph compiler inlines are single primary expressions (#282 hardening). */
class LuaTextTest {

    @Test
    void negativeNumbersAreParenthesizedSoOperatorsBindAsWritten() {
        // -2 ^ x is -(2 ^ x) in Lua; a pow node with base -2 must mean (-2) ^ x.
        assertEquals("(-2)", LuaText.number(-2));
        assertEquals("(-0.5)", LuaText.number(-0.5));
        assertEquals("2", LuaText.number(2));
        assertEquals("0.25", LuaText.number(0.25));
    }

    @Test
    void nonFiniteNumbersAreArithmeticNotGlobals() {
        // Double.toString gives Infinity/NaN: bare identifiers a script could define.
        assertEquals("(1/0)", LuaText.number(Double.POSITIVE_INFINITY));
        assertEquals("(-1/0)", LuaText.number(Double.NEGATIVE_INFINITY));
        assertEquals("(0/0)", LuaText.number(Double.NaN));
        assertEquals("{ (-1), 3 }", LuaText.literal(new UiValue.Arr(java.util.List.of(UiValue.of(-1), UiValue.of(3)))));
    }
}
